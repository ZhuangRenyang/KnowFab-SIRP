#!/usr/bin/env node
/**
 * 构建期检查：除白名单外，src/main/java 里不许再出现硬编码的 502。
 *
 * 为什么要有这条门禁
 * ------------------
 * 502 是 **Modbus TCP** 的端口。历史上它被当成「所有协议的默认端口」写死了 9 处
 * （实体、控制器、模板、上下文、大屏、驱动……）。后果：
 *
 *   · 新建一台 OPC UA 设备不填端口 ⇒ 落库 502 ⇒ 驱动去连 502 ⇒ **必然连不上**
 *   · 每接一个新协议就要改一遍那 9 处，漏改的那个就去连错端口
 *
 * 现在口径收进了 `ProtocolDefaults`（唯一真相）。但**口径收好了不代表不会再漂** ——
 * 有人新写一个 `? 502 :`、或把 `ProtocolDefaults` 改回硬编码，没有东西拦着他。
 *
 * 前端那边已经有对称的门禁（`frontend/scripts/check-port-semantics.mjs`），
 * 但**它只看前端自己的文件**，抓不到后端新写的 502。
 * ⇒ 这条是后端的对称护栏。
 *
 * 为什么放在 scripts/ 而不是 src/test
 * -----------------------------------
 * 静态检查不该依赖编译（冷缓存 CI 上更慢），也不该混进单测
 * （跑 mvn 才跑到，漏跑一次就静默失效）。
 *
 * 白名单
 * ------
 * 白名单里的每一处都写了**为什么合法** —— 白名单本身也是需要解释的，
 * 不然过两年就变成「这里为什么有 502？不知道，但删了测试会红」。
 *
 * 退出码：0 = 通过；1 = 有白名单外的硬编码 / 白名单过期
 */

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const SRC = path.join(ROOT, 'src', 'main', 'java')

// 端口字面量：**端口语境**里的 3-4 位数字。
//
// 为什么是「端口语境」而不是「所有数字」：
//   · 扫所有 3-4 位数字 ⇒ 订单号、超时值、寄存器地址全被扫进来 ⇒ 误报满天飞
//   · 只找 502 一个数 ⇒ **加第三个协议（默认端口比如 9600）时，那行根本不会被扫到**
//     ⇒ 门禁悄悄失去保护 —— 这正是这道门禁当初被建起来要防的那类漂移。
//   · 折中：只扫**提到端口的行**（架构师实测：8 处命中、零误报）。
//
// 「端口语境」的判定（架构师给的 8 处实测数据）：
//   int port = req.port() == null ? 502 : req.port();      ← 变量名 port
//   public static final int MODBUS_TCP_PORT = 502;          ← 常量名后缀 _PORT
//   static final int DEFAULT_PORT = 4840;                   ← 同上
// ⚠️ 残留缺口（不 over-promise）：它依赖**命名约定** —— 端口常量带 port/PORT。
//   将来有人写 `private static final int OPC_UA_DEFAULT = 9600;`（名字里没有 port）就扫不到。
//   约定靠「格式契约 + 这道门禁」双保险（本项目的端口常量命名约定很强）。
const PORT_CONTEXT = /(^|[^A-Za-z0-9_])port([^A-Za-z0-9_]|$)|_PORT\b|\bPort\b/
// 端口字面量：独立的 3-4 位数字（不认 15021 / 0.502 / x502）
const PORT_LITERAL = /(?<![\w.])(\d{3,4})(?![\w.])/g

// 白名单：{ file, pattern, max, reason }
//   file    —— 相对 src/main/java 的路径（用 / 分隔）
//   pattern —— 该行**代码**必须匹配的正则（注释与字符串已被剔除）
//   max     —— 最多允许几处
//   reason  —— 为什么合法（白名单本身也需要解释）
const WHITELIST = [
  {
    file: 'com/factory/hub/driver/ProtocolDefaults.java',
    pattern: /MODBUS_TCP_PORT\s*=\s*\d{3,4}/,
    max: 1,
    reason: '协议→默认端口的唯一真相（Modbus TCP / RTU-over-TCP 用 502）'
  },
  {
    file: 'com/factory/hub/driver/ProtocolDefaults.java',
    pattern: /OPC_UA_PORT\s*=\s*\d{3,4}/,
    max: 1,
    reason: '同上（OPC UA 的标准端口 4840）'
  },
  {
    file: 'com/factory/hub/driver/modbus/ModbusTcpDriver.java',
    pattern: /DEFAULT_PORT\s*=\s*\d{3,4}/,
    max: 1,
    reason: 'Modbus TCP 驱动自己的防御兜底（上游漏了解析时不至于拼出 host:0）'
  },
  {
    file: 'com/factory/hub/driver/modbus/ModbusRtuOverTcpDriver.java',
    pattern: /DEFAULT_PORT\s*=\s*\d{3,4}/,
    max: 1,
    reason: 'Modbus RTU over TCP 驱动自己的防御兜底（同上）'
  },
  {
    file: 'com/factory/hub/driver/opcua/OpcUaEndpointUrl.java',
    pattern: /DEFAULT_PORT\s*=\s*\d{3,4}/,
    max: 1,
    reason: 'OPC UA 端点 URL 构造器的协议级兜底（上游漏了解析时不至于拼出 host:0）'
  },
  {
    file: 'com/factory/hub/discovery/DiscoveryController.java',
    pattern: /\?\s*\d{3,4}\s*:/,
    max: 3,
    reason: 'Modbus 专用探测端点的默认端口：那几个接口带 unitId/functionCode/framing 的 ' +
      'Modbus 语义，且 DiscoveryController 里没有建设备的路径 ⇒ 那里 502 是对的'
  }
]

/**
 * 去掉注释与字符串字面量，只留代码。
 *
 * 为什么要剔掉：
 *   · 注释里写「Modbus TCP（端口 502）」是**说明**，不是硬编码
 *   · 字符串里的 "502" 可能只是给用户看的文案
 * 为什么要小心处理：字符串里可能含 `//`（如 "opc.tcp://"），朴素的切法会切错。
 */
function stripCommentsAndStrings (line, inBlock) {
  let out = ''
  let i = 0
  let inStr = false
  let inChr = false
  while (i < line.length) {
    const c = line[i]
    const next = line[i + 1] || ''
    if (inBlock) {
      if (c === '*' && next === '/') { inBlock = false; i += 2; continue }
      i++
      continue
    }
    if (inStr) {
      if (c === '\\') { i += 2; continue }
      if (c === '"') inStr = false
      i++
      continue
    }
    if (inChr) {
      if (c === '\\') { i += 2; continue }
      if (c === "'") inChr = false
      i++
      continue
    }
    if (c === '/' && next === '*') { inBlock = true; i += 2; continue }
    if (c === '/' && next === '/') break
    if (c === '"') { inStr = true; i++; continue }
    if (c === "'") { inChr = true; i++; continue }
    out += c
    i++
  }
  return { code: out, inBlock }
}

function listJavaFiles (dir) {
  const out = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) out.push(...listJavaFiles(full))
    else if (entry.name.endsWith('.java')) out.push(full)
  }
  return out.sort()
}

function main () {
  if (!fs.existsSync(SRC)) {
    console.log('✗ 找不到 src/main/java（当前工作目录：' + process.cwd() + '）')
    return 1
  }

  const files = listJavaFiles(SRC)
  const violations = []
  const used = new Map()   // 白名单下标 → 用了几次（键用全局下标，两边一致）
  let totalHits = 0

  for (const file of files) {
    const rel = path.relative(SRC, file).split(path.sep).join('/')
    const content = fs.readFileSync(file, 'utf8')
    let inBlock = false
    const lines = content.split(/\r?\n/)
    for (let i = 0; i < lines.length; i++) {
      const raw = lines[i]
      const stripped = stripCommentsAndStrings(raw, inBlock)
      inBlock = stripped.inBlock
      const code = stripped.code
      // 先判「这一行是不是在谈端口」：不是 ⇒ 这一行的数字与端口无关（订单号、超时值…）
      if (!PORT_CONTEXT.test(code)) continue
      const nums = code.match(PORT_LITERAL)
      if (!nums || !nums.length) continue
      totalHits++
      let matched = false
      for (let gi = 0; gi < WHITELIST.length; gi++) {
        const rule = WHITELIST[gi]
        if (rule.file !== rel) continue
        const n = used.get(gi) || 0
        if (n < rule.max && rule.pattern.test(code)) {
          used.set(gi, n + 1)
          matched = true
          break
        }
      }
      if (!matched) violations.push({ rel, lineno: i + 1, text: raw.trim() })
    }
  }

  if (violations.length > 0) {
    console.log('✗ 发现白名单之外的端口字面量（' + violations.length + ' 处）')
    console.log('  协议→默认端口的口径只在 com/factory/hub/driver/ProtocolDefaults.java 定义一处，')
    console.log('  其它地方一律查 ProtocolDefaults.defaultPort(protocol)。')
    console.log('  （扫描范围：提到端口的行里的 3-4 位数字 —— 只找 502 的话，')
    console.log('    加第三个协议时那行根本不会被扫到 ⇒ 门禁悄悄失去保护。）')
    console.log('')
    for (const v of violations) {
      console.log('  ' + v.rel + ':' + v.lineno)
      console.log('      ' + v.text)
      console.log('      ⇒ 不在白名单里。白名单里合法的那几处及原因：')
      for (const rule of WHITELIST) {
        console.log('        - ' + rule.file + '：' + rule.reason)
      }
      console.log('')
    }
    console.log('  如果这处确实是合法的（新的协议级兜底、Modbus 专用探测端点等），')
    console.log('  请在 scripts/check-protocol-port-literals.mjs 的 WHITELIST 里显式加一条，')
    console.log('  并写清为什么合法 —— 白名单本身也需要解释。')
    return 1
  }

  // 白名单里「允许的处数」没被用满也算异常：说明白名单过期了（那处被删了/改了写法），
  // 过期的白名单会让后来的人以为那里本来就可以随便写 502。
  const stale = []
  for (let gi = 0; gi < WHITELIST.length; gi++) {
    const rule = WHITELIST[gi]
    const n = used.get(gi) || 0
    if (n < rule.max) stale.push({ rule, n })
  }
  if (stale.length > 0) {
    console.log('✗ 白名单与代码对不上（' + stale.length + ' 条过期）')
    for (const s of stale) {
      console.log('  ' + s.rule.file + '：白名单允许 ' + s.rule.max + ' 处，实际只匹配到 ' + s.n + ' 处')
      console.log('      （原理由：' + s.rule.reason + '）')
    }
    console.log('  ⇒ 白名单过期会让后来的人以为那里本来就可以随便写 502。')
    console.log('  请把代码改回去，或更新/删除这条白名单。')
    return 1
  }

  console.log('✓ 端口字面量检查通过：src/main/java 共 ' + files.length + ' 个文件，' +
    '端口语境里的 3-4 位数字共 ' + totalHits + ' 处，全部在白名单内（' +
    WHITELIST.length + ' 条白名单规则）')
  for (const rule of WHITELIST) {
    console.log('  · ' + rule.file + '（' + rule.max + ' 处）：' + rule.reason)
  }
  return 0
}

process.exit(main())
