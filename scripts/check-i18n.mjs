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
 * 三类问题分开报
 * --------------
 *   缺失       调用了但 zh-CN 里没有 → 界面显示键名原文
 *   英文缺失   zh 有但 en 没有     → 英文界面突然蹦中文（更隐蔽，像"只是没翻译"）
 *   孤儿键     zh 有但没人调用     → 废弃键，不影响用户但会误导后来者
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
 * 孤儿键判定时要忽略的前缀。
 * 这些键是**后端返回的 key 名**，前端用 `t(prefix + key)` 动态取，
 * 静态扫不到调用点 —— 但它们不是废弃键。
 */
const ORPHAN_IGNORE_PREFIXES = ['ai.']

const CALL_RE = /\b(?:t|tc|te|tm)\(\s*['"]([A-Za-z0-9_.\-]+)['"]/g
const CONCAT_RE = /\b(?:t|tc|te|tm)\(\s*['"]([^'"]+)['"]\s*\+/g
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
  const stringLiterals = new Set()
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
      for (const m of text.matchAll(/'([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+)'/g)) {
        stringLiterals.add(m[1])
      }
    }
  }
  walkLiterals(SRC)

  // ---- 孤儿键 ----
  // 动态前缀 = 白名单里的 + 代码里实际扫到的拼接前缀。
  // 这样不用手写维护"哪些嵌套字典是被动态访问的"——
  // 扫出来的自动算已使用，孤儿报告才准。
  const dynamicPrefixes = new Set([
    ...Object.keys(DYNAMIC_LITERAL_ALLOWLIST),
    ...concat.keys(),
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
