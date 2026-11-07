#!/usr/bin/env node
/**
 * i18n 语言范围门禁（R5）的「变异验证」——
 *
 * 判据：**故意注入一个错误 → 校验器必须 exit 非 0，而且红得正是这一条**。
 *
 * 「跑一下 exit 0」不能证明门禁有效。只断「exit 非 0」也不够 ——
 * 别的因素让它红，你这条变异就会**假通过**。所以每条变异要**同时**满足：
 *   ① exit 非 0
 *   ② 输出里出现**期望的诊断文案**（红得对，不是红在别的地方）
 *   ③ **R5 违规条数正好等于预期**（多一条 ⇒ 还有别的东西混进来了）
 *
 * 做法：把 scripts/ + frontend/src 拷到临时目录 → 注入一个错误 →
 *       用 I18N_CHECK_ROOT 指向副本跑校验器 → 断言上面三条。**不碰真文件**。
 *       每条之后再跑一次真文件 ⇒ 必须回到绿。
 *
 * ⚠️ **锚点必须挂在结构上，不能挂在文案上**：
 *   界面上任何一个词改了，写死文案的锚点就会静默失效 ⇒ 变异变成「装饰性的」。
 *   所以下面用**结构谓词**去挑目标（LOCALES 里的 code 条目、locales 目录下的文件），
 *   挑不到就报错退出 —— 宁可响，不要哑。
 *
 * ⚠️ **副本必须复制「当前工作树」而不是 `git archive HEAD`**：
 *   HEAD 比工作区旧 ⇒ 变异跑的是旧代码 ⇒ 报出「已经修好的错误」（本项目栽过两次）。
 *
 * ⚠️ **临时目录每次用新的**，不做「固定目录 + rm -rf」：
 *   有些环境有批量删除保护 ⇒ rm -rf 被打断 ⇒ 旧残留与新拷贝混成「新旧混合」
 *   ⇒ 后续变异以「文件不存在」失败，看起来像门禁坏了，其实是我的锅。
 */
import { cpSync, readFileSync, writeFileSync, mkdirSync, readdirSync, existsSync, rmSync } from 'node:fs'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(__dirname, '..')
const TMP_BASE = join(ROOT, 'target', 'mutation-check-i18n')

/** 每次运行一个新子目录 —— 不删任何东西，绕开环境的删除保护 */
/**
 * ⚠️ **每条变异一个全新子目录**（不是整个脚本共用一个）：
 *   cpSync 是「覆盖式拷贝」，**不会删除目标里已存在的文件** ⇒ 共用目录时，
 *   上一条变异的产物（例如 A 注入的 ja-JP.ts）会**残留**到后面所有轮次
 *   ⇒ 后面每条都在测「上一条的残留」⇒ 全军覆没且诊断完全对不上。
 *   （这正是先例里警告过的「新旧混合」坑，我第一版就踩了。）
 */
let seq = 0
function fresh() {
  seq += 1
  const dir = join(TMP_BASE, `run-${Date.now()}-${process.pid}-${seq}`)
  mkdirSync(dir, { recursive: true })
  // scripts/：校验器本身（副本里跑它，被改的代码与被测代码在同一棵树里）
  cpSync(join(ROOT, 'scripts'), join(dir, 'scripts'), { recursive: true })
  // frontend/src：locales + i18n + 所有 .vue/.ts（调用点扫描需要它们，
  // 少拷会让「缺失键」之类的噪音混进诊断里）
  cpSync(join(ROOT, 'frontend', 'src'), join(dir, 'frontend', 'src'), { recursive: true })
  for (const must of [
    join('scripts', 'check-i18n.mjs'),
    join('frontend', 'src', 'locales', 'zh-CN.ts'),
    join('frontend', 'src', 'locales', 'en-US.ts'),
    join('frontend', 'src', 'i18n', 'index.ts')
  ]) {
    if (!existsSync(join(dir, must))) {
      console.error(`  ✗ 副本不完整：缺少 ${must} —— 拷贝被截断，先修副本，别去查门禁`)
      process.exit(2)
    }
  }
  return dir
}

function read(p) { return readFileSync(p, 'utf8') }
function write(p, s) { writeFileSync(p, s, 'utf8') }

/** 跑某棵树里的校验器；I18N_CHECK_ROOT 指向那棵树 */
function runChecker(root) {
  const r = spawnSync(process.execPath, [join(root, 'scripts', 'check-i18n.mjs')], {
    cwd: root,
    encoding: 'utf8',
    env: { ...process.env, I18N_CHECK_ROOT: root }
  })
  return { code: r.status, out: (r.stdout || '') + (r.stderr || '') }
}

/** 从输出里读 R5 违规条数（不是猜的） */
function r5Count(out) {
  const m = out.match(/语言范围违反 R5[^=]*===\s*(\d+)\s*处/)
  return m ? Number(m[1]) : null
}

// ---------------------------------------------------------------------------
// 变异清单
//   apply(root) => { marker, expectCount }：期望的诊断片段与期望的违规条数
// ---------------------------------------------------------------------------
const MUTATIONS = [
  {
    id: 'A',
    name: 'locales/ 多出第三种语言文件',
    apply(root) {
      const p = join(root, 'frontend', 'src', 'locales', 'ja-JP.ts')
      write(p, "/** 日本語 */\nexport default {\n  common: { ok: 'OK' }\n}\n")
      return { marker: '多出 ja-JP.ts', expectCount: 1 }
    }
  },
  {
    id: 'B',
    name: 'LOCALES 注册第三种语言',
    apply(root) {
      const p = join(root, 'frontend', 'src', 'i18n', 'index.ts')
      const s = read(p)
      // 结构谓词：找 LOCALES 里第一条 code 条目，插一条新的 —— 不写死文案
      const m = s.match(/^(\s*)\{ code: '[a-z]{2}-[A-Z]{2}',[^\n]*\}\n/m)
      if (!m) { console.error('  ✗ 注入失败：没匹配到 LOCALES 的 code 条目（写法变了？）'); process.exit(2) }
      const indent = m[1]
      const ins = `${indent}{ code: 'ja-JP', label: '日本語', ep: epZhCn }\n`
      write(p, s.slice(0, m.index) + ins + s.slice(m.index))
      return { marker: '注册了 ja-JP', expectCount: 1 }
    }
  },
  {
    id: 'C',
    name: 'messages 打包第三种语言',
    apply(root) {
      const p = join(root, 'frontend', 'src', 'i18n', 'index.ts')
      const s = read(p)
      const m = s.match(/^(\s*)'en-US':\s*(\w+)\n/m)
      if (!m) { console.error('  ✗ 注入失败：没匹配到 messages 里的 en-US 条目'); process.exit(2) }
      write(p, s.slice(0, m.index + m[0].length) + `,\n${m[1]}'ja-JP': ${m[2]}\n` + s.slice(m.index + m[0].length))
      return { marker: '打包了 ja-JP', expectCount: 1 }
    }
  },
  {
    id: 'D',
    name: '少了 en-US.ts（中英底线被破坏）',
    apply(root) {
      rmSync(join(root, 'frontend', 'src', 'locales', 'en-US.ts'))
      return { marker: '少了 en-US.ts', expectCount: 1 }
    }
  },
  {
    id: 'E',
    name: '解析器读不出 LOCALES（写法变了）',
    apply(root) {
      const p = join(root, 'frontend', 'src', 'i18n', 'index.ts')
      let s = read(p)
      // 两个都改 ⇒ 一条都读不出来 ⇒ 必须报「解析失败」，
      // 而不是退化成「没发现问题」（本项目栽过最阴的一类坑）。
      for (const code of ['zh-CN', 'en-US']) {
        const needle = `code: '${code}'`
        if (!s.includes(needle)) { console.error(`  ✗ 注入失败：没找到 ${needle}`); process.exit(2) }
        s = s.replace(needle, `code: "${code}"`)
      }
      write(p, s)
      return { marker: '解析不出 LOCALES 的语言代码', expectCount: 1 }
    }
  },
  {
    id: 'F',
    name: '反向对照：中英同时加一个合法键',
    reverse: true,
    apply(root) {
      for (const [name, val] of [['zh-CN', "'新键'"], ['en-US', "'newKey'"]]) {
        const p = join(root, 'frontend', 'src', 'locales', `${name}.ts`)
        let s = read(p)
        // ⚠️ 文件可能是 CRLF 行尾 ⇒ 锚点要容许 \r\n
        const m = s.match(/^  common: \{\r?\n/m)
        if (!m) { console.error(`  ✗ 注入失败：没匹配到 ${name} 的 common 块`); process.exit(2) }
        const eol = s.includes('\r\n') ? '\r\n' : '\n'
        write(p, s.slice(0, m.index + m[0].length) + `    r5Probe: ${val},${eol}` + s.slice(m.index + m[0].length))
      }
      return { marker: null, expectCount: 0 }
    }
  }
]

let failed = 0
console.log('=== i18n 语言范围门禁的变异验证：注入 → 必须红 → 还原 → 必须绿 ===\n')

// ---- 基线：未变异必须通过，否则下面全都不算 ----
{
  const base = fresh()
  const r = runChecker(base)
  const ok = r.code === 0
  console.log(`  ${ok ? '✓' : '✗'} 基线：未变异 → exit ${r.code}${ok ? '' : '（基线就不对，下面全不算）'}`)
  if (!ok) {
    console.log(r.out.split('\n').slice(-12).map((l) => '       ' + l).join('\n'))
    process.exit(1)
  }
}

for (const m of MUTATIONS) {
  const dir = fresh()
  const { marker, expectCount } = m.apply(dir)
  const r = runChecker(dir)
  const n = r5Count(r.out)

  if (m.reverse) {
    const ok = r.code === 0
    console.log(`  ${ok ? '✓' : '✗'} ${m.id} ${m.name} → ${ok ? '仍绿（符合预期：不该影响结论）' : `红了（不该红）exit ${r.code}`}`)
    if (!ok) {
      failed++
      console.log(r.out.split('\n').slice(-8).map((l) => '       ' + l).join('\n'))
    }
  } else {
    const hit = marker && r.out.includes(marker)
    const countOk = n === expectCount
    const ok = r.code !== 0 && hit && countOk
    console.log(`  ${ok ? '✓' : '✗'} ${m.id} ${m.name} → exit ${r.code}  诊断命中=${hit ? '对' : '错'}  违规条数=${n}（期望 ${expectCount}）`)
    if (!ok) {
      failed++
      console.log(r.out.split('\n').slice(-10).map((l) => '       ' + l).join('\n'))
    }
  }

  // 还原后必须回到绿（顺带证明：全程没碰共享工作树）
  const back = runChecker(fresh())
  if (back.code !== 0) {
    console.log(`       ⚠ 还原后仍红（exit ${back.code}）—— 副本不干净`)
    failed++
  }
}

// ---- 收尾：清临时目录。**清不掉也不能让整条门禁红** ----
//     有些环境有批量删除保护（先例里踩过：清目录被拦 ⇒ 门禁中断）。
try {
  rmSync(TMP_BASE, { recursive: true, force: true })
} catch {
  console.log(`  （临时目录没清掉：${TMP_BASE} —— 与「门禁有没有鉴别力」无关，不阻断）`)
}

console.log()
if (failed > 0) {
  console.log(`✗ i18n 语言范围门禁的变异验证失败：${failed} 项`)
  process.exit(1)
}
console.log(`✓ i18n 语言范围门禁的变异验证通过（${MUTATIONS.length} 个变异，门禁的鉴别力成立）`)
