#!/usr/bin/env node
/**
 * i18n 键完整性检查
 * =================
 *
 * 为什么需要这个脚本
 * ------------------
 * 缺 key 的失败模式很特殊：**不报错、不白屏、不影响任何测试**，
 * 只是在界面上显示键路径原文（比如 `common.reqFail`）。
 *
 * 已踩过两次：
 *   ① `common.reqFail` —— 调用 21 处，但这个键只定义在 `alarmNotify` 块里。
 *      工单页 / BOM 页 / 成本页任何一次接口失败，弹出来的都是字面文本
 *      `common.reqFail`，后端给的失败原因（「库存不足：铸铁现有 100…」）全被盖住。
 *   ② `devForm.*` 15 个键 —— 键从 `devForm` 挪进了 `common`，调用点没跟着改。
 *
 * 这两类都只能靠人眼发现。脚本把它们变成 CI 能拦的东西。
 *
 * 为什么用 node 而不是 python
 * ---------------------------
 * locale 文件本身就是纯 JS 对象字面量（没有类型注解），所以用
 * `data:` URL 直接 import 就能拿到**真实的**键结构。
 * 自己写正则解析器踩过两次坑：块缩进不统一（`common` 在 2 空格、`erpRpt` 在 0 空格）、
 * 一行内开闭的嵌套块（`lv: { critical: '停机', ... }`）会算错层级 —— 误报一片。
 * 交给 node 解析，100% 准确。
 *
 * 四类问题分开报
 * --------------
 *   缺失       调用了但 zh-CN 里没有 → 界面显示键名原文
 *   缺失（映射表） 被映射表引用但 locale 里没有 → 同样显示键名原文，
 *              但 t('字面量') 扫不到，只能靠全量字符串扫描发现
 *   英文缺失   zh 有但 en 没有     → 英文界面突然蹦中文（更隐蔽，像"只是没翻译"）
 *   孤儿键     zh 有但没人调用     → 废弃键，不影响用户但会误导后来者
 *
 * 动态前缀的处理分两层：
 *   ALLOWLIST  —— 跳过前缀（防误报），并且该前缀下的键不算孤儿
 *   EXPANSIONS —— 把前缀按**代码实际拼出的值集**展开成具体键，逐个校验
 *              只做前者会漏掉"分隔符/大小写不一致"的错（已踩到 minLevel 一次）
 *
 * 用法
 * ----
 *   node scripts/check-i18n.mjs            # 检查，有问题返回 1
 *   node scripts/check-i18n.mjs --quiet    # 只在有问题时输出
 *
 * 退出码：有「缺失」或「单语言漏配」返回 1；只有孤儿键返回 0。
 */

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(__dirname, '..')
const SRC = path.join(ROOT, 'frontend', 'src')
const LOCALE_DIR = path.join(SRC, 'locales')
const ZH_FILE = path.join(LOCALE_DIR, 'zh-CN.ts')
const EN_FILE = path.join(LOCALE_DIR, 'en-US.ts')

/**
 * 字面量本身不是完整键的例外（拼接但不以 . 或 _ 结尾）。
 * 光看字面量和真缺失键一模一样，只能人工登记。
 *
 * 登记这个动作是有价值的：它会强迫新增动态拼接的人想一下
 * 「我拼出来的那些值，都定义了吗」。
 */
const DYNAMIC_LITERAL_ALLOWLIST = {
  'energyTariff.seg': "t('energyTariff.seg' + v)，v ∈ {seg1,seg2,seg3}",
  'alarmDict.lv.': 't(\'alarmDict.lv.\' + lv)，lv 是告警级别码',
  'alarmDict.ruleType.': 't(\'alarmDict.ruleType.\' + v)，v 是规则类型码',
  'devTpl.': 't(\'devTpl.\' + name)，name 是模板字段名',
  'wo.st_': 't(\'wo.st_\' + status)，status ∈ {DRAFT,RELEASED,RUNNING,DONE,CANCELLED}',
  'qc.enum.': "t('qc.enum.' + group + '.' + code)",
  'qc.display.': 't(\'qc.display.\' + displayStatus)，5 个派生值',
  'auditDict.act.': 't(\'auditDict.act.\' + action)，action 码表见 AuditLog',
  'auditDict.cat.': 't(\'auditDict.cat.\' + category)，分类码表见 AUDIT_CATEGORIES',
  'status.alarm': "t('status.alarm' + state)，state ∈ {OPEN,ACTIVE,ACKED,CLEARED}",
}

/**
 * 动态前缀的**值集展开**。
 *
 * 上面的 ALLOWLIST 只是"跳过这个前缀"——它保证不误报，但**不检查拼出来的
 * 键到底存不存在**。这里补上后半截：把前缀按已知的值集展开成具体键，逐个校验。
 *
 * 为什么必须展开：前缀跳过会漏掉**分隔符/大小写不一致**这类错。
 * 已踩到一次 —— `alarmDict.minLevel.` 的 locale 是小写
 * （alarmDict.minLevel.critical），但代码拼的是大写
 * （MIN_LEVEL_KEYS = ['CRITICAL', ...]），于是通知配置弹窗的
 * 「最低通知级别」下拉显示的是字面文本 `alarmDict.minLevel.CRITICAL`。
 * 前缀跳过式检查永远发现不了它，因为前缀本身是对的。
 *
 * 值集必须和**代码里实际产生的字符串**一致（不是和 locale 一致）——
 * 写展开表的过程本身就是在核对"代码拼出来的到底是什么"。
 *
 * 新增动态拼接时，必须来这里登记一条：
 *   - 值集封闭（枚举）→ 写进本表，逐个校验
 *   - 值集开放（任意字符串，如 devTpl. 的模板字段名）→ 写进上面的 ALLOWLIST 并注明
 */
const EXPANSIONS = [
  // 前缀                        值集（必须与代码实际拼出的字符串完全一致）
  { prefix: 'wo.st_',             values: ['DRAFT', 'RELEASED', 'RUNNING', 'DONE', 'CANCELLED'] },
  { prefix: 'status.alarm',        values: ['Active', 'Acked', 'Cleared'] },
  { prefix: 'alarmDict.lv.',       values: ['critical', 'major', 'minor'] },
  { prefix: 'alarmDict.stateOpt.', values: ['open', 'active', 'acked', 'cleared', 'all'] },
  { prefix: 'alarmDict.levelOpt.', values: ['all', 'critical', 'major', 'minor'] },
  { prefix: 'alarmDict.rangeOpt.', values: ['today', '7d', '30d', 'all'] },
  { prefix: 'alarmDict.chType.',   values: ['WECOM_BOT', 'SERVERCHAN', 'PUSHPLUS', 'DINGTALK', 'FEISHU', 'WEBHOOK', 'LOG'] },
  { prefix: 'alarmDict.minLevel.', values: ['CRITICAL', 'MAJOR', 'MINOR'] },
  { prefix: 'alarmDict.ruleType.', values: ['offline', 'stop', 'flag', 'threshold'] },
  { prefix: 'energyTariff.seg',    values: ['TIP', 'PEAK', 'FLAT', 'VALLEY'] },
  { prefix: 'auditDict.cat.',      values: ['DEVICE', 'ENERGY', 'ALARM', 'REPORT', 'SYSTEM', 'MANUAL', 'USER', 'QUALITY'] },
  { prefix: 'auditDict.act.',      values: ['CREATE', 'UPDATE', 'DELETE', 'WRITE', 'PURGE', 'REVOKE', 'RESET', 'LOGIN', 'JUDGE', 'DISPOSE', 'TRIAGE', 'AQL_MANUAL', 'VERIFY', 'CONCESSION_ACK', 'RECON_RESOLVE'] },
  // ↓ 以下 6 组来自 components/system/SettingsPanel.vue 的本地 tr(key, fallback)：
  //   function tr(key, fallback) { const full = 'sys.' + key; return te(full) ? t(full) : fallback }
  // 键的后缀是**后端下发的中文设置项名**，所以值集含中文。
  // 注意 tr 带 fallback：查不到就显示中文原文，不会显示 key —— 但英文界面会蹦中文，
  // 所以照样要盯着这些键齐不齐。
  { prefix: 'sys.group.',    values: ['告警', '审计', '更新与升级', '演示数据'] },
  { prefix: 'sys.item.',     values: ['alarm_retain-days', 'alarm_storm-threshold', 'audit_retain-days', 'demand_demo-seed', 'energy_demo-seed', 'report_demo-seed', 'update_allow-web-restart'] },
  { prefix: 'sys.itemDesc.', values: ['alarm_retain-days', 'alarm_storm-threshold', 'audit_retain-days', 'demand_demo-seed', 'energy_demo-seed', 'report_demo-seed', 'update_allow-web-restart'] },
  { prefix: 'sys.purge.',    values: ['demand', 'energy', 'report'] },
  { prefix: 'sys.ro.',       values: ['升级暂存目录', '数据库', '更新源', '服务端口', '采集数据目录'] },
  { prefix: 'sys.roWhy.',    values: ['升级暂存目录', '数据库', '更新源', '服务端口', '采集数据目录'] },
  // ↓ 以下 14 组来自 frontend/src/api/qcEnums.ts 的 QC_ENUM_PREFIX + QC_ENUM_VALUES。
  // 前端做的是**通用枚举系统**：qc.<group>.<value>，配一个统一的 qc.enum.unknown 兜底。
  // 这个结构比"每个枚举各写一套"好，所以以它为准（详见 2026-11-03 的设计对齐）。
  { prefix: 'qc.type.',          values: ['IQC', 'FAI', 'IPQC', 'FQC'] },
  { prefix: 'qc.status.',        values: ['DRAFT', 'SUBMITTED', 'JUDGED', 'CANCELLED', 'PENDING', 'DONE', 'VOID'] },
  { prefix: 'qc.verdict.',       values: ['PASS', 'FAIL', 'CONDITIONAL'] },
  { prefix: 'qc.ncrStatus.',     values: ['PENDING', 'DISPOSED', 'CANCELLED'] },
  { prefix: 'qc.displayStatus.', values: ['PENDING', 'DISPOSED', 'CANCELLED', 'REWORK_WAIT_FQC', 'REWORK_FQC_FAIL'] },
  { prefix: 'qc.source.',        values: ['DEVICE', 'INSPECTION', 'MANUAL', 'RETURN'] },
  { prefix: 'qc.severity.',      values: ['CR', 'MA', 'MI'] },
  { prefix: 'qc.disposition.',   values: ['SCRAP', 'REWORK', 'CONCESSION'] },
  { prefix: 'qc.level.',         values: ['GENERAL_I', 'GENERAL_II', 'GENERAL_III', 'SPECIAL_S1', 'SPECIAL_S2', 'SPECIAL_S3', 'SPECIAL_S4'] },
  { prefix: 'qc.strictLevel.',   values: ['NORMAL', 'TIGHTENED', 'REDUCED'] },
  { prefix: 'qc.sampleSource.',  values: ['AQL', 'FIXED', 'MANUAL'] },
  { prefix: 'qc.blockReason.',   values: ['NO_ACK', 'NO_RELEASE', 'unknown'] },
  { prefix: 'qc.blockAction.',   values: ['ack', 'dispose', 'unknown'] },
  { prefix: 'qc.returnReason.',  values: ['QUALITY', 'OTHER'] },
  { prefix: 'qc.skipReason.',    values: ['DISPOSED', 'CONFLICT', 'NOT_FOUND', 'INVALID_STATE'] },
  { prefix: 'qc.enum.',          values: ['unknown'] },
  // ↓ 来自 composables/useErpLabel.ts（模板字符串 + 中文 fallback）
  { prefix: 'erpPurStatus.',     values: ['DRAFT', 'CONFIRMED', 'PARTIAL', 'DONE', 'CANCELLED'] },
  { prefix: 'erpSalesStatus.',   values: ['DRAFT', 'CONFIRMED', 'PARTIAL', 'DONE', 'CANCELLED'] },
]

/**
 * 孤儿键判定时要忽略的前缀。
 * 这些键是**后端返回的 key 名**，前端用 `t(prefix + key)` 动态取，
 * 静态扫不到调用点 —— 但它们不是废弃键。
 */
const ORPHAN_IGNORE_PREFIXES = ['ai.']

const CALL_RE = /\b(?:t|tc|te|tm)\(\s*['"]([A-Za-z0-9_.\-]+)['"]/g
const CONCAT_RE = /\b(?:t|tc|te|tm)\(\s*['"]([^'"]+)['"]\s*\+/g
/**
 * 模板字符串形式：`erpPurStatus.${code}` / `${PREFIX[group]}.${value}`
 * 前者是静态前缀 + 动态后缀，能扫；后者前缀本身是变量，扫不出来
 * （那种只能靠 EXPANSIONS 手工登记，见下）。
 * 已踩到：composables/useErpLabel.ts 用 `erpPurStatus.${code}` 取订单状态文案，
 * 引号形式的正则完全看不到它 —— 导致 10 个键被误报成孤儿。
 */
const TEMPLATE_RE = /\b(?:t|tc|te|tm|label)\(\s*`([A-Za-z][A-Za-z0-9_]*\.)\$\{/g
const GLOBAL_RE = /i18n\.global\.t\(\s*['"]([A-Za-z0-9_.\-]+)['"]/g

/** 加载 locale：用 data: URL import，拿到真实的键结构 */
async function loadLocale(file) {
  const src = fs.readFileSync(file, 'utf8')
  const b64 = Buffer.from(src, 'utf8').toString('base64')
  const mod = await import(`data:text/javascript;base64,${b64}`)
  return mod.default
}

/** 把嵌套对象展平成 a.b.c 路径 */
function flatten(obj, prefix = '', out = new Set()) {
  for (const [k, v] of Object.entries(obj ?? {})) {
    const key = prefix ? `${prefix}.${k}` : k
    if (v && typeof v === 'object' && !Array.isArray(v)) flatten(v, key, out)
    else out.add(key)
  }
  return out
}

/** 扫全部 .vue / .ts，找出对 i18n 的调用 */
function collectCalls() {
  const literal = new Map()   // key -> Set(文件:行号)
  const concat = new Map()
  const skipDirs = new Set(['node_modules', 'dist', '.git'])

  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) {
        if (!skipDirs.has(entry.name)) walk(full)
        continue
      }
      if (!/\.(vue|ts)$/.test(entry.name) || entry.name.endsWith('.d.ts')) continue
      if (full.startsWith(LOCALE_DIR)) continue
      const text = fs.readFileSync(full, 'utf8')
      const rel = path.relative(SRC, full).split(path.sep).join('/')
      const lines = text.split('\n')
      lines.forEach((line, idx) => {
        for (const m of line.matchAll(CALL_RE)) {
          if (!literal.has(m[1])) literal.set(m[1], new Set())
          literal.get(m[1]).add(`${rel}:${idx + 1}`)
        }
        for (const m of line.matchAll(GLOBAL_RE)) {
          if (!literal.has(m[1])) literal.set(m[1], new Set())
          literal.get(m[1]).add(`${rel}:${idx + 1}`)
        }
        for (const m of line.matchAll(CONCAT_RE)) {
          if (!concat.has(m[1])) concat.set(m[1], new Set())
          concat.get(m[1]).add(`${rel}:${idx + 1}`)
        }
        for (const m of line.matchAll(TEMPLATE_RE)) {
          if (!concat.has(m[1])) concat.set(m[1], new Set())
          concat.get(m[1]).add(`${rel}:${idx + 1}`)
        }
      })
    }
  }
  walk(SRC)
  return { literal, concat }
}

/** 以 . 或 _ 结尾 → 一定是拼接前缀，自动跳过 */
const isDynamicPrefix = (k) => k.endsWith('.') || k.endsWith('_')

async function main() {
  const quiet = process.argv.includes('--quiet')

  const zh = flatten(await loadLocale(ZH_FILE))
  const en = flatten(await loadLocale(EN_FILE))
  const { literal, concat } = collectCalls()

  // ---- 缺失：调用了但 zh 里没有 ----
  const missing = new Map()
  for (const [key, places] of literal) {
    if (isDynamicPrefix(key)) continue
    if (key in DYNAMIC_LITERAL_ALLOWLIST) continue
    if (!zh.has(key)) missing.set(key, places)
  }

  // ---- 缺失：动态前缀按值集展开后的具体键 ----
  // 前缀对不代表拼出来的键存在。分隔符或大小写不一致时，
  // 前缀跳过式检查永远发现不了（见 EXPANSIONS 里的 minLevel 案例）。
  const expandedMissing = new Set()
  for (const { prefix, values } of EXPANSIONS) {
    for (const v of values) {
      const key = prefix + v
      if (zh.has(key)) continue
      expandedMissing.add(key)
      const sites = [...(concat.get(prefix) ?? [])]
      const where = sites.length
        ? sites.map((s) => `${s}（展开自 ${prefix}）`)
        : [`（展开自 ${prefix}，未找到字面量调用点）`]
      missing.set(key, new Set(where))
    }
  }

  // ---- 单语言漏配 ----
  const onlyZh = [...zh].filter((k) => !en.has(k)).sort()
  const onlyEn = [...en].filter((k) => !zh.has(k)).sort()

  // ---- 疑似动态拼接但没登记白名单 ----
  const unresolved = new Map()
  for (const [prefix, places] of concat) {
    if (prefix in DYNAMIC_LITERAL_ALLOWLIST) continue
    if (isDynamicPrefix(prefix)) continue
    unresolved.set(prefix, places)
  }

  // ---- 间接引用的键 ----
  // 有些键是通过映射表间接引用的，例如 api/devices.ts 里
  //   { mitsubishi: 'brand.mitsubishi' }
  // 然后 t(mapping[code])。这种调用点扫不到（字面量是变量），
  // 但键确实在用 —— 用"代码里出现过这个完整键名字符串"来识别，避免误报成孤儿。
  //
  // 同时这里也暴露一个**漏洞**：这类键既不在 CALL_RE 里（没有 t('字面量')），
  // 又因为出现在 stringLiterals 里被排除出孤儿 —— 也就是说
  // **"引用了但 locale 里没有"的映射表键是完全隐形的**。
  // 所以下面要主动检查它们存不存在。
  const stringLiterals = new Map()
  const walkLiterals = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) {
        if (!['node_modules', 'dist', '.git'].includes(entry.name)) walkLiterals(full)
        continue
      }
      if (!/\.(vue|ts)$/.test(entry.name) || entry.name.endsWith('.d.ts')) continue
      if (full.startsWith(LOCALE_DIR)) continue
      const text = fs.readFileSync(full, 'utf8')
      const rel = path.relative(SRC, full).split(path.sep).join('/')
      text.split('\n').forEach((line, idx) => {
        for (const m of line.matchAll(/'([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+)'/g)) {
          if (!stringLiterals.has(m[1])) stringLiterals.set(m[1], new Set())
          stringLiterals.get(m[1]).add(`${rel}:${idx + 1}`)
        }
      })
    }
  }
  walkLiterals(SRC)

  // ---- 缺失：被代码引用（尤其是映射表）但 locale 里没有 ----
  // 过滤条件：首段必须是 locale 里已存在的顶层块名。
  // 这样 'sirp.locale'（localStorage 键）这类非 i18n 串不会被算进来，
  // 同时漏检的风险仅限于「整个新块都还没建」——那种情况 t('字面量') 会先报出来。
  const localeRoots = new Set(Object.keys(zh))
  const mappedMissing = new Map()
  for (const [key, sites] of stringLiterals) {
    if (zh.has(key)) continue
    if (!localeRoots.has(key.split('.')[0])) continue
    if (isDynamicPrefix(key)) continue
    if (key in DYNAMIC_LITERAL_ALLOWLIST) continue
    if (EXPANSIONS.some((e) => key.startsWith(e.prefix))) continue
    mappedMissing.set(key, sites)
  }

  // ---- 孤儿键 ----
  // 动态前缀 = 白名单里的 + 代码里实际扫到的拼接前缀 + 展开表里的前缀。
  // 这样不用手写维护"哪些嵌套字典是被动态访问的"——
  // 扫出来的自动算已使用，孤儿报告才准。
  const dynamicPrefixes = new Set([
    ...Object.keys(DYNAMIC_LITERAL_ALLOWLIST),
    ...concat.keys(),
    ...EXPANSIONS.map((e) => e.prefix),
  ])
  const called = new Set(literal.keys())
  const orphan = [...zh]
    .filter((k) => !called.has(k))
    .filter((k) => !ORPHAN_IGNORE_PREFIXES.some((p) => k.startsWith(p)))
    .filter((k) => ![...dynamicPrefixes].some((p) => k.startsWith(p)))
    .filter((k) => !stringLiterals.has(k))   // 通过映射表间接引用的，不算孤儿
    .sort()

  let ok = true

  if (missing.size) {
    ok = false
    console.log(`=== 缺失（界面会显示键名原文）===  ${missing.size} 个键`)
    for (const key of [...missing.keys()].sort()) {
      const where = [...missing.get(key)].slice(0, 3).join(', ')
      console.log(`  ${key.padEnd(44)} ${where}`)
    }
  }

  if (mappedMissing.size) {
    ok = false
    console.log(`\n=== 缺失（被映射表引用，locale 里没有）===  ${mappedMissing.size} 个键`)
    console.log('  这些键通过映射表间接取用，t(\'字面量\') 扫不到，只能靠全量字符串扫描发现。')
    for (const key of [...mappedMissing.keys()].sort()) {
      const where = [...mappedMissing.get(key)].slice(0, 3).join(', ')
      console.log(`  ${key.padEnd(44)} ${where}`)
    }
  }

  if (onlyZh.length) {
    ok = false
    console.log(`\n=== 英文缺失（英文界面会蹦中文）===  ${onlyZh.length} 个键`)
    for (const key of onlyZh.slice(0, 40)) console.log(`  ${key}`)
    if (onlyZh.length > 40) console.log(`  ... 还有 ${onlyZh.length - 40} 个`)
  }

  if (onlyEn.length) {
    ok = false
    console.log(`\n=== 中文缺失（中文界面会蹦英文）===  ${onlyEn.length} 个键`)
    for (const key of onlyEn.slice(0, 40)) console.log(`  ${key}`)
    if (onlyEn.length > 40) console.log(`  ... 还有 ${onlyEn.length - 40} 个`)
  }

  if (unresolved.size) {
    console.log(`\n=== 疑似动态拼接但没登记白名单 ===  ${unresolved.size} 处（登记后可消除误报）`)
    for (const [prefix, places] of [...unresolved.entries()].sort()) {
      console.log(`  ${prefix.padEnd(38)} ${[...places].slice(0, 2).join(', ')}`)
    }
  }

  if (orphan.length && !quiet) {
    console.log(`\n=== 孤儿键（定义了没人用）===  ${orphan.length} 个`)
    for (const key of orphan.slice(0, 20)) console.log(`  ${key}`)
    if (orphan.length > 20) console.log(`  ... 还有 ${orphan.length - 20} 个`)
  }

  if (ok) {
    console.log(`\n✓ i18n 键检查通过：zh ${zh.size} 键 / en ${en.size} 键，无缺失、无单语言漏配`)
    if (orphan.length) console.log(`  （另有 ${orphan.length} 个孤儿键，不影响用户）`)
    process.exit(0)
  }
  process.exit(1)
}

main().catch((e) => {
  console.error('检查脚本自身出错：', e.message)
  process.exit(2)
})
