/**
 * 端口「未填」语义的前端回归门禁。
 *
 * 守的是什么坑（这轮刚拆掉的那个）：
 *   前端表单把「用户没填端口」在到达驱动之前就抹成了 502 ——
 *   502 是 **Modbus** 的端口。于是新建一台 OPC UA 设备、端口栏留空，
 *   落库 502 ⇒ 驱动去连 opc.tcp://host:502 ⇒ 必然连不上。
 *
 * 为什么这个门禁要用**源码断言**而不是只跑行为测试：
 *   行为测试（挂载 el-input-number 探针）能证明「现在是对的」，
 *   但防不住有人下次把哨兵值从 null 改回 0、把 `|| 0` 改回 `|| 502`。
 *   源码断言把「不许改回来」钉死，行为断言兜住「el-input-number 的行为变了」。
 *   两条合起来才是完整的回归网。
 *
 * 跑法：
 *   cd frontend && npm run check:port
 *   （从仓库根跑也行：node frontend/scripts/check-port-semantics.mjs）
 *
 * 退出码：0 = 全过；1 = 有断言失败（门禁红）。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const FE = path.resolve(HERE, '..')            // frontend/
const ROOT = path.resolve(FE, '..')             // 仓库根
const DIALOG = path.join(FE, 'src/components/DeviceFormDialog.vue')
const LOCALES = ['zh-CN', 'en-US', 'de-DE', 'es-ES', 'ja-JP', 'ko-KR', 'ru-RU']

let failed = 0
const fail = (msg) => { console.error('  FAIL ' + msg); failed++ }
const pass = (msg) => console.log('  ok   ' + msg)

const src = fs.readFileSync(DIALOG, 'utf8')

console.log('== 源码断言：端口语义不许退回 502 ==')

// ① 提交时绝不能兜成 502。
if (/port:\s*Number\(f\.port\)\s*\|\|\s*502/.test(src)) {
  fail('提交时端口被兜成 502：新建 OPC UA 设备会拿着 502 去连，必然连不上')
} else if (/port:\s*Number\(f\.port\)\s*\|\|\s*0\b/.test(src)) {
  pass('提交时端口留空 ⇒ 传 0（= 未指定），由后端按协议兜底')
} else {
  fail('提交时的端口表达式不是 `Number(f.port) || 0` —— 请确认没被改回 `|| 502`')
}

// ② 表单里的「未填」哨兵必须是 null，不能是 0。
//    el-input-number 的 :min="1" 会把 0 夹成 1 并反向 emit 回父组件，
//    用 0 当哨兵 ⇒ 用户看到端口框里凭空冒出「1」。
if (/port:\s*null\s+as\s+number\s*\|\s*null/.test(src)) {
  pass('表单初始端口哨兵是 null（空框），不是 0')
} else {
  fail('表单初始端口哨兵不是 `null as number | null` —— 0 会被 el-input-number 夹成 1')
}

// ③ 新建（resetDefaults）与编辑回填也不许兜成 502。
const resetBlock = src.slice(src.indexOf('function resetDefaults'))
if (/port:\s*502/.test(resetBlock)) {
  fail('resetDefaults() 里端口仍兜成 502')
} else if (/port:\s*null/.test(resetBlock)) {
  pass('resetDefaults() 里端口留空 ⇒ null')
} else {
  fail('resetDefaults() 里的端口初值既不是 null 也不是 502 —— 请确认')
}
if (/port:\s*c\.port\s*\|\|\s*502/.test(src)) {
  fail('编辑回填仍用 `c.port || 502`')
} else if (/port:\s*normPort\(c\.port\)/.test(src)) {
  pass('编辑回填用 normPort(c.port)（0/null 归一成 null，不会被夹成 1）')
} else {
  fail('编辑回填没用 normPort(c.port) —— 库里的 0/null 会被 el-input-number 夹成 1')
}

// ④ 端口框的 min="1" 必须在 —— 它正是「0 不可用作哨兵」的前提。
if (/el-input-number[^>]*v-model="f\.port"[^>]*:min="1"/.test(src) ||
    /el-input-number[^>]*:min="1"[^>]*v-model="f\.port"/.test(src)) {
  pass('端口框保留 :min="1"（端口不能是 0）')
} else {
  fail('端口框的 el-input-number 缺 :min="1" —— 0 会变成合法端口，语义就乱了')
}

// ⑤ 模板套用路径同样要归一：模板端口可能是 0（未指定）。
if (/f\.port\s*=\s*ct\.port\s*\?\?\s*null/.test(src)) {
  fail('套用「我的模板」时用了 `ct.port ?? null` —— 模板端口是 0 时会被夹成 1')
} else if (/f\.port\s*=\s*normPort\(ct\.port\)/.test(src)) {
  pass('套用「我的模板」用 normPort(ct.port)')
} else {
  fail('套用「我的模板」没用 normPort(ct.port)')
}

console.log('\n== 行为断言：el-input-number 的哨兵行为 ==')

// 用真实的 element-plus 跑一遍：null 原样保留、0 被夹成 1。
// 这条断言的意义是「记住为什么哨兵必须是 null」——
// 如果哪天 element-plus 改了行为，这条会红，提醒我们重新评估（而不是默默失效）。
const require = createRequire(path.join(FE, 'package.json'))
let ElInputNumber = null
try {
  ElInputNumber = require('element-plus').ElInputNumber
} catch (e) {
  console.log('  ! 跳过行为断言（element-plus 不可解析：' + e.code + '）')
}

if (ElInputNumber) {
  const { createSSRApp, h, ref } = require('vue')
  const { renderToString } = require('@vue/server-renderer')

  const probe = async (initial) => {
    const emitted = []
    const App = {
      setup() {
        const v = ref(initial)
        return () => h(ElInputNumber, {
          modelValue: v.value, min: 1, max: 65535,
          'onUpdate:modelValue': (nv) => { emitted.push(nv); v.value = nv }
        })
      }
    }
    await renderToString(createSSRApp(App))
    return emitted
  }

  const nullEmit = await probe(null)
  const zeroEmit = await probe(0)

  if (nullEmit.length === 0) {
    pass('哨兵 null：el-input-number 原样保留、不反向回写（留空保持留空）')
  } else {
    fail('哨兵 null 竟被回写成 ' + JSON.stringify(nullEmit) + ' —— 留空语义已失效')
  }
  if (zeroEmit.length === 1 && zeroEmit[0] === 1) {
    pass('哨兵 0 会被夹成 1（这正是不能用 0 的原因，行为符合预期）')
  } else {
    fail('el-input-number 对 0 的行为变了：' + JSON.stringify(zeroEmit) +
         ' —— 若 0 已能被保留，可重新评估是否改用 0 作哨兵')
  }
}

console.log('\n== i18n 断言：7 语言 portPh 齐 ==')
for (const loc of LOCALES) {
  const file = path.join(FE, 'src/locales', loc + '.ts')
  const txt = fs.readFileSync(file, 'utf8')
  const m = txt.match(/portPh:\s*'([^']*)'/)
  if (!m) { fail(loc + ' 缺 devForm.portPh'); continue }
  if (!m[1].includes('{port}')) { fail(loc + ' 的 portPh 缺 {port} 插值'); continue }
  if (/�/.test(m[1])) { fail(loc + ' 的 portPh 里有损坏字符'); continue }
  pass(loc + ' portPh = "' + m[1] + '"')
}

console.log('')
if (failed) {
  console.error('端口语义门禁失败：' + failed + ' 项')
  process.exit(1)
}
console.log('端口语义门禁通过')
