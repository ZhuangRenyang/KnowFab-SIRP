#!/usr/bin/env node
/**
 * 端口语义门禁的「变异验证」——
 *
 * 判据：**故意注入一个错误 → 门禁必须 exit 非 0，而且失败必须正是这一条造成的**。
 *
 * 为什么要有这个脚本（本项目栽过好几次）：
 *   · 「跑一下 exit 0」不能证明门禁有效 —— 暂存区里那个 117 行空壳校验器，
 *     跑一下就 exit 0，什么都拦不住。
 *   · **绿灯不是「没问题」，是「没人验证过它会不会亮」。**
 *   · 只断「exit 非 0」也不够：门禁可能因为**别的原因**红（上一条变异的残留、
 *     脚本自己崩了），所以还要断**诊断分类**（[解析失败] / [口径不一致]）
 *     **和失败条数**（正好是这一条该产生的那几条，多一条少一条都算不算数）。
 *   · 「注入失败」必须当失败算：我自己的第一版矩阵里三个变体的模式串没匹配上、
 *     注入根本没发生，脚本却照样跑出一句「绿」—— **「没注入成功」和「门禁是绿的」
 *     被混为一谈**，这跟「工具报『没有』其实是扫描失败」是同一句话。
 *
 * 做法（跟 scripts/mutation-check-alarmcodes.mjs 同款）：
 *   把被守的文件拷到临时目录 → 在**副本**上注入错误 →
 *   用 PORT_CHECK_DIALOG / PORT_CHECK_BACKEND / PORT_CHECK_TEMPLATES
 *   把校验器指向副本 → 断言退出码 + 诊断分类 + 失败条数。
 *   **不碰真文件**（共享工作树上多人并行改同一棵树，改真文件必然打架）。
 *   每条变异之后再复跑一次（跑真文件）⇒ 必须回到绿 ⇒ 证明没有残留。
 *
 * 两类变异，缺一不可：
 *   · **正向**（注入真实漂移）⇒ 必须红，且红得对
 *   · **反向对照**（注入不相关的东西）⇒ 必须**仍然绿**
 *     只做正向 ⇒ 门禁可能因为「什么都拦」而红；只做反向 ⇒ 可能因为「什么都不拦」而绿。
 *
 * 覆盖不到的（诚实说明）：
 *   行为断言（跑真实 element-plus，验哨兵 null 不被夹、0 被夹成 1）**无法被变异** ——
 *   它守的是第三方库的内部行为，变异它没有意义。它由常规门禁（npm run check:port）
 *   每次运行直接验证；本脚本只负责验「源码 / 数据 / 一致性」三类断言的鉴别力。
 *
 * 跑法：cd frontend && npm run check:port-mutation
 * 退出码：0 = 全部符合预期；1 = 有变异没被抓住（或基线就不通过）。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const FE = path.resolve(HERE, '..')            // frontend/
const ROOT = path.resolve(FE, '..')             // 仓库根
const CHECKER = path.join(HERE, 'check-port-semantics.mjs')

const SRC = {
  dialog: path.join(FE, 'src/components/DeviceFormDialog.vue'),
  backend: path.join(ROOT, 'src/main/java/com/factory/hub/driver/ProtocolDefaults.java'),
  templates: path.join(FE, 'src/api/deviceTemplates.ts')
}

// 临时副本放 frontend/target/ 下（根 .gitignore 的 target/ 覆盖任意层级，不会进版本库）。
// 覆盖式拷贝、不做 rm -rf：先例里踩到过某些环境按「累计删除文件数」保护会把整条命令打断。
const TMP = path.join(FE, 'target', 'mutation-check-port')

let failed = 0
const fail = (msg) => { console.error('  FAIL ' + msg); failed++ }
const pass = (msg) => console.log('  ok   ' + msg)

/** 把源文件拷到临时目录，返回副本路径；并断言副本与源逐字节相同（无残留） */
function stage(id, src) {
  const dir = path.join(TMP, id)
  fs.mkdirSync(dir, { recursive: true })
  const dst = path.join(dir, path.basename(src))
  fs.copyFileSync(src, dst)          // 覆盖写，不删目录 ⇒ 不会累积垃圾，也不会触发删除保护
  if (!fs.readFileSync(dst).equals(fs.readFileSync(src))) {
    throw new Error('临时副本与源不一致（' + id + '）—— 上一条变异可能有残留')
  }
  return dst
}

/** 跑一次校验器（可覆盖它要读的文件） */
function runChecker(env) {
  const r = spawnSync(process.execPath, [CHECKER], {
    env: { ...process.env, ...env },
    encoding: 'utf8',
    cwd: FE
  })
  if (r.error) return { code: -1, out: String(r.error) }
  return { code: r.status, out: (r.stdout || '') + (r.stderr || '') }
}

/** 输出里 FAIL 的条数（多一条 ⇒ 可能还有别的失败混进来了） */
const countFails = (out) => (out.match(/^\s*FAIL /gm) || []).length

const MAP_BLOCK = '    private static final Map<String, Integer> PROTOCOL_PORTS = Map.ofEntries(\n' +
                  '            Map.entry("opcua", OPC_UA_PORT));'

// 变异清单：每条 = 一处「真实漂移」的注入方式 + 期望门禁给出的诊断分类 + 期望的失败条数。
// marker 是**必须在输出里出现**的片段 —— 断言的是「红得对」，不只是「红了」。
// expectFails 是**必须正好等于**的失败条数 —— 多一条就说明「还有别的东西让它红」。
//   刻意的耦合：校验器以后新增断言 ⇒ 这里必须同步更新条数。
//   这是故意的 —— 改条数时人会顺手看一眼「新增的断言对不对」，而不是默默跟着红。
const MUTATIONS = [
  // ---------- 源码断言：表单不许把未填端口兜成 502 / 哨兵不许变 0 ----------
  { id: 'S1', file: 'dialog', desc: '提交时端口被兜成 502',
    from: 'port: Number(f.port) || 0,', to: 'port: Number(f.port) || 502,',
    marker: '提交时端口被兜成 502', expectFails: 1 },
  { id: 'S2', file: 'dialog', desc: '表单哨兵从 null 改成 0',
    from: 'port: null as number | null,', to: 'port: 0 as number | number,',
    marker: '表单初始端口哨兵不是', expectFails: 1 },
  { id: 'S3', file: 'dialog', desc: '端口框去掉 :min="1"',
    from: '<el-input-number v-model="f.port" :min="1"', to: '<el-input-number v-model="f.port" :min="0"',
    marker: '缺 :min="1"', expectFails: 1 },

  // ---------- 一致性断言：前端那份漂移 ----------
  { id: 'A', file: 'dialog', desc: '前端端口表 opcua 改成 502',
    from: 'opcua: 4840', to: 'opcua: 502', marker: '[口径不一致]', expectFails: 1 },
  { id: 'D', file: 'dialog', desc: '前端端口表改名',
    from: 'const PROTO_DEFAULT_PORT', to: 'const PROTO_PORT_DEFAULT', marker: '[解析失败]', expectFails: 2 },
  { id: 'G', file: 'dialog', desc: '前端端口值带算术',
    from: 'opcua: 4840', to: 'opcua: 4840 + 0', marker: '[解析失败]', expectFails: 2 },

  // ---------- 一致性断言：后端那份漂移 ----------
  { id: 'B', file: 'backend', desc: '后端 Map.entry 的协议名改成变量',
    from: 'Map.entry("opcua", OPC_UA_PORT)', to: 'Map.entry(PROTO_OPCUA, OPC_UA_PORT)',
    marker: '[解析失败]', expectFails: 1 },
  { id: 'C', file: 'backend', desc: '后端 OPC_UA_PORT 改成 4841',
    from: 'int OPC_UA_PORT = 4840;', to: 'int OPC_UA_PORT = 4841;', marker: '[口径不一致]', expectFails: 1 },
  { id: 'E', file: 'backend', desc: '后端兜底值带算术',
    from: 'getOrDefault(p, MODBUS_TCP_PORT)', to: 'getOrDefault(p, MODBUS_TCP_PORT + 1)',
    marker: '[解析失败]', expectFails: 1 },
  { id: 'F', file: 'backend', desc: '后端端口常量带算术',
    from: 'int OPC_UA_PORT = 4840;', to: 'int OPC_UA_PORT = 4840 + 1;', marker: '[解析失败]', expectFails: 2 },
  { id: 'I', file: 'backend', desc: '后端删掉 opcua 的映射（映射变空）',
    from: MAP_BLOCK,
    to: '    private static final Map<String, Integer> PROTOCOL_PORTS = Map.ofEntries();',
    marker: '没有单列', expectFails: 1 },
  { id: 'J', file: 'backend', desc: '后端换成 HashMap + put（不认识的写法）',
    from: MAP_BLOCK,
    to: '    private static final Map<String, Integer> PROTOCOL_PORTS = new java.util.HashMap<>();\n' +
        '    static {\n        PROTOCOL_PORTS.put("opcua", OPC_UA_PORT);\n    }',
    marker: '[解析失败]', expectFails: 1 },

  // ---------- 反向对照：注释不是代码，不该影响结论 ⇒ 必须仍然绿 ----------
  // 锚点挂在**类声明**上，不挂 javadoc 里的某句话 ——
  // 后端会改那段 javadoc 的措辞，挂措辞等于给自己埋一个「明天就注入失败」的雷。
  { id: 'H', file: 'backend', desc: '后端注释里塞一条假 Map.entry（不该影响结论）',
    from: 'public final class ProtocolDefaults {',
    to: '    // 举例：Map.entry("opcua", 9999) —— 注释不是代码，解析时会被剥掉\n' +
        'public final class ProtocolDefaults {',
    expectGreen: true },

  // ---------- 数据断言：内置模板的 port 必须与协议默认一致 ----------
  { id: 'T1', file: 'templates', desc: '内置模板的 port 抄成 4840',
    from: 'port: 502, unitId: 1', to: 'port: 4840, unitId: 1',
    marker: '与协议 modbus-tcp 的默认端口 502 不一致', expectFails: 1 }
]

console.log('== 基线：未变异必须通过（否则下面全都不算） ==')
{
  const r = runChecker({})
  if (r.code === 0) {
    pass('基线：未变异时门禁 exit 0')
  } else {
    console.error(r.out)
    console.error('\n基线就不通过 —— 下面所有变异结果都不算数，先把基线修好。')
    process.exit(1)
  }
}

console.log('\n== 变异验证：每个漂移都必须被抓住，且失败必须正是这一条造成的 ==')
for (const m of MUTATIONS) {
  // ① 拷贝：与源逐字节相同 ⇒ 没有上一条变异的残留
  const dst = stage(m.id, SRC[m.file])
  const original = fs.readFileSync(dst, 'utf8')
  if (!original.includes(m.from)) {
    // 注入失败 = 这一项不算数。绝不能当成「门禁是绿的」。
    fail(m.id + ' 注入失败：模式没匹配上（' + JSON.stringify(m.from.slice(0, 48)) +
         '…）—— 这一项不算数，不是「通过」')
    continue
  }
  fs.writeFileSync(dst, original.replace(m.from, m.to))
  // ② 注入后必须真的变了（模式匹配上了但内容没变，也是另一种空转）
  if (fs.readFileSync(dst, 'utf8') === original) {
    fail(m.id + ' 注入失败：替换后内容没变 —— 这一项不算数，不是「通过」')
    continue
  }

  const env = {}
  if (m.file === 'dialog') env.PORT_CHECK_DIALOG = dst
  if (m.file === 'backend') env.PORT_CHECK_BACKEND = dst
  if (m.file === 'templates') env.PORT_CHECK_TEMPLATES = dst
  // 快跑：跳过 element-plus 的 SSR 探针。基线那一轮不带这个开关（会跑全量），
  // 这里这 14 个变异动的是表单/模板/协议表，动不到 element-plus 的内部行为 ⇒ 探针跑了也没信息。
  env.PORT_CHECK_FAST = '1'

  const r = runChecker(env)
  if (m.expectGreen) {
    // 反向对照：必须仍然绿，而且**一条 FAIL 都不能有**
    const n = countFails(r.out)
    if (r.code === 0 && n === 0) {
      pass(m.id + ' ' + m.desc + ' ⇒ 门禁仍绿、零 FAIL（符合预期：这一处不该影响结论）')
    } else {
      fail(m.id + ' ' + m.desc + ' ⇒ 门禁不该红却红了（exit ' + r.code + '，FAIL ' + n + ' 条）：\n' + r.out)
    }
  } else {
    const n = countFails(r.out)
    if (r.code !== 0 && r.out.includes(m.marker) && n === m.expectFails) {
      pass(m.id + ' ' + m.desc + ' ⇒ 红了，且诊断分类正确（' + m.marker + '）、失败条数正好 ' +
           m.expectFails + ' 条（不是别的东西让它红）')
    } else {
      fail(m.id + ' ' + m.desc + ' ⇒ 期望门禁红、报「' + m.marker + '」、失败条数正好 ' +
           m.expectFails + ' 条；实际 exit=' + r.code + '、FAIL ' + n + ' 条：\n' + r.out)
    }
  }

  // ③ 还原后必须回到绿：这一条变异没在工作树里留下残留（跑的是真文件）
  const after = runChecker({ PORT_CHECK_FAST: '1' })
  if (after.code === 0) {
    pass(m.id + ' 还原后复跑 ⇒ 回到绿（真文件未被改动，无残留）')
  } else {
    fail(m.id + ' 还原后复跑仍红（exit ' + after.code + '）—— 变异有残留：\n' + after.out)
  }
}

console.log('')
if (failed) {
  console.error('端口语义门禁的变异验证失败：' + failed + ' 项')
  process.exit(1)
}
console.log('端口语义门禁的变异验证通过（' + MUTATIONS.length + ' 个变异，门禁的鉴别力成立）')
