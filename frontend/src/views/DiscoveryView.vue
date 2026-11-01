<template>
  <div class="disco">
    <div class="grid">
      <div class="panel">
        <div class="p-head"><h3>{{ t('disc.title1') }}</h3>
          <span>{{ t('disc.sub1') }}</span></div>
        <div class="tip">{{ t('disc.tip1') }}</div>
        <div class="row">
          <div class="f wide"><label>{{ t('disc.prefixLabel') }}</label>
            <el-input v-model="s.prefixes" placeholder="192.168.1,192.168.2" style="width:230px" /></div>
          <div class="f"><label>{{ t('disc.from') }}</label><el-input v-model.number="s.from" type="number" style="width:90px" /></div>
          <div class="f"><label>{{ t('disc.to') }}</label><el-input v-model.number="s.to" type="number" style="width:90px" /></div>
          <div class="f"><label>{{ t('disc.port') }}</label><el-input v-model.number="s.port" type="number" style="width:90px" /></div>
          <div class="f"><label>{{ t('disc.timeout') }}</label><el-input v-model.number="s.timeoutMs" type="number" style="width:90px" /></div>
          <el-button type="primary" :loading="scanning" @click="doScan">{{ scanning ? t('disc.scanning') : t('disc.scanStart') }}</el-button>
        </div>
        <div class="hint">{{ t('disc.scanHint') }}</div>
        <div v-if="scanErr" class="bad">{{ scanErr }}</div>
        <div v-else-if="scanNote" class="hint">{{ scanNote }}</div>
        <table v-if="found.length" class="etable">
          <thead><tr><th>{{ t('disc.thIp') }}</th><th>{{ t('disc.thPort') }}</th><th>{{ t('disc.thAction') }}</th></tr></thead>
          <tbody>
            <tr v-for="f in found" :key="f.ip">
              <td class="num">{{ f.ip }}</td><td class="num">{{ f.port }}</td>
              <td><span class="go" @click="pickIp(f.ip, f.port)">{{ t('disc.probeThis') }}</span></td>
            </tr>
          </tbody>
        </table>
        <div v-else-if="scanDone" class="empty">{{ t('disc.noDevice') }}</div>
      </div>

      <div class="panel">
        <div class="p-head"><h3>{{ t('disc.title2') }}</h3>
          <span>{{ t('disc.sub2') }}</span></div>
        <div class="tip">
          {{ t('disc.tip2a') }}
          <b>{{ t('disc.tip2b') }}</b>{{ t('disc.tip2c') }}
          <br>
          <b>{{ t('disc.tip2d') }}</b>{{ t('disc.tip2e') }} <b>{{ t('disc.tip2f') }}</b>{{ t('disc.tip2g') }}
        </div>
        <div class="row">
          <div class="f"><label>{{ t('disc.ip') }}</label><el-input v-model="p.ip" placeholder="192.168.1.50" style="width:150px" /></div>
          <div class="f"><label>{{ t('disc.port') }}</label><el-input v-model.number="p.port" type="number" style="width:86px" /></div>
          <div class="f"><label>{{ t('disc.unitId') }}</label><el-input v-model.number="p.unitId" type="number" style="width:86px" /></div>
          <div class="f"><label>{{ t('disc.fc') }}</label>
            <el-select v-model="p.fc" style="width:172px">
              <el-option :value="3" :label="t('disc.fc3')" />
              <el-option :value="4" :label="t('disc.fc4')" />
              <el-option :value="2" :label="t('disc.fc2')" />
            </el-select></div>
          <div class="f"><label>{{ t('disc.framing') }}</label>
            <el-select v-model="p.framing" style="width:170px">
              <el-option value="tcp" :label="t('disc.fTcp')" />
              <el-option value="rtu" :label="t('disc.fRtu')" />
            </el-select></div>
          <div class="f"><label>{{ t('disc.addrFrom') }}</label><el-input v-model.number="p.from" type="number" style="width:90px" /></div>
          <div class="f"><label>{{ t('disc.count') }}</label><el-input v-model.number="p.count" type="number" style="width:80px" /></div>
        </div>
        <div class="row">
          <el-button type="primary" :loading="probing" @click="probeOnce">{{ t('disc.readOnce') }}</el-button>
          <el-button :type="autoOn ? 'warning' : 'default'" @click="toggleAuto">
            {{ autoOn ? t('disc.autoStop') : t('disc.autoStart') }}
          </el-button>
          <el-button @click="genJson('smart')">{{ t('disc.genSmart') }}</el-button>
          <el-button @click="genJson('all')">{{ t('disc.genAll') }}</el-button>
        </div>
        <div class="row units">
          <el-button size="small" :loading="unitScanning" @click="scanUnits">{{ t('disc.scanUnits') }}</el-button>
          <span class="lbl">{{ t('disc.range') }}</span>
          <el-input v-model.number="uFrom" type="number" style="width:70px" size="small" />
          <span class="lbl">{{ t('disc.toLabel') }}</span>
          <el-input v-model.number="uTo" type="number" style="width:70px" size="small" />
          <span class="hint inline">{{ t('disc.unitHint') }}</span>
        </div>
        <div v-if="unitNote" class="hint">{{ unitNote }}</div>
        <div v-if="alive.length" class="chips">
          <el-button v-for="u in alive" :key="u" size="small" @click="pickUnit(u)">{{ t('disc.unitChip', { n: u }) }}</el-button>
        </div>
        <div class="hint">
          {{ t('disc.addrHintA') }} <b>modbus-rtu-tcp</b>{{ t('disc.addrHintB') }}
          <span class="bad">{{ t('disc.addrWarn') }}</span>
        </div>
        <table v-if="rows.length" class="etable">
          <thead><tr><th>{{ t('disc.thAddr') }}</th><th>{{ t('disc.thDec') }}</th><th>{{ t('disc.thHex') }}</th><th>{{ t('disc.thChange') }}</th><th>{{ t('disc.thGuess') }}</th></tr></thead>
          <tbody>
            <tr v-for="r in rows" :key="r.addr" :class="{ chg: !!r.chg }">
              <td class="num">{{ r.addr }}</td><td class="num">{{ r.v }}</td>
              <td class="num">{{ r.hex }}</td><td class="chg-cell">{{ r.chg }}</td>
              <td><span v-if="r.hint" class="role-tag" :class="'role-' + r.hintRole">{{ r.hint }}</span></td>
            </tr>
          </tbody>
        </table>
        <div v-if="probeErr" class="bad">{{ probeErr }}</div>
      </div>

      <div class="panel wide">
        <div class="p-head"><h3>{{ t('disc.title3') }}</h3>
          <span>{{ embed ? t('disc.sub3Embed') : t('disc.sub3Copy') }}</span></div>
        <div class="tip">{{ t('disc.tip3') }}</div>
        <!-- 知道型号就套模板：点表语义（名称/单位/角色）直接用模板的，
             IP/端口/从站号仍来自刚才的扫描结果 -->
        <div class="row" style="margin-bottom:8px">
          <span class="hint" style="margin-left:0">{{ t('disc.tplLabel') }}</span>
          <el-select v-model="tplId" :placeholder="t('disc.tplPh')" clearable style="width:300px" @change="applyTpl">
            <el-option-group v-for="g in tplGroups" :key="g.brand" :label="g.brand">
              <el-option v-for="tp in g.list" :key="tp.id" :value="tp.id"
                         :label="`${modelText(tp)}${tp.confidence === 'verified' ? t('disc.verified') : ''}`" />
            </el-option-group>
          </el-select>
          <span class="hint">{{ tplNote }}</span>
        </div>
        <el-input v-model="jsonText" type="textarea" :rows="9" spellcheck="false" class="json"
                  :placeholder="t('disc.jsonPh')" />
        <div class="row" style="margin-top:10px">
          <el-button @click="copyJson">{{ t('disc.copyJson') }}</el-button>
          <!-- 弹框模式（设备监控页）：一键带着 IP/端口/从站号/点表去新增设备，
               不用再复制粘贴。独立页面模式保持原复制流程 -->
          <el-button v-if="embed" type="primary" @click="addAsDevice">{{ t('disc.addAsDevice') }}</el-button>
          <span class="hint">{{ jsonNote }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">

import { onMounted, onScopeDispose, reactive, ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { discoveryApi, type ProbeArgs } from '@/api/discovery'

const { t } = useI18n()
import { DEVICE_TEMPLATES, TPL_I18N, type DeviceTpl } from '@/api/deviceTemplates'

// prefixes 初始留空：打开页面就用后端探测的本机网段预填（2026-10-29）。
// 原来写死 192.168.1，设备在 192.168.0.x 时点扫描扫的是空段，
// 看着就像"没有自动扫描这个功能"。探测失败才退回 192.168.1 手填兜底。
const s = reactive({ prefixes: '', from: 1, to: 254, port: 502, timeoutMs: 300 })

/** 弹框模式（设备监控页内嵌）：多显示「直接添加为设备」按钮 */
const props = defineProps<{ embed?: boolean }>()
const emit = defineEmits<{
  addDevice: [p: { protocol: string; host: string; port: number; unitId: number; pointsText: string }]
}>()

const scanning = ref(false)
const scanErr = ref('')
const scanNote = ref('')
const scanDone = ref(false)
const found = ref<{ ip: string; port: number }[]>([])

/* 扫描结果套模板：IP/端口/从站号来自扫描，点表语义来自模板（2026-10-29） */
const tplId = ref('')
const tplNote = ref('')
/* 模板展示文本走 i18n（数据中文保留，TPL_I18N 映射见 deviceTemplates.ts） */
const modelText = (tp: DeviceTpl) => TPL_I18N[tp.id] ? t('devTpl.' + TPL_I18N[tp.id].model) : tp.model
const noteText = (tp: DeviceTpl) => TPL_I18N[tp.id] ? t('devTpl.' + TPL_I18N[tp.id].note) : tp.note

const tplGroups = computed(() => {
  const m = new Map<string, DeviceTpl[]>()
  for (const tp of DEVICE_TEMPLATES) {
    const brand = TPL_I18N[tp.id] ? t('devTpl.' + TPL_I18N[tp.id].brand) : tp.brand
    if (!m.has(brand)) m.set(brand, [])
    m.get(brand)!.push(tp)
  }
  return [...m.entries()].map(([brand, list]) => ({ brand, list }))
})

function applyTpl(id: string) {
  const tpl = DEVICE_TEMPLATES.find((x) => x.id === id)
  if (!tpl) { tplNote.value = ''; return }
  tplId.value = id
  jsonText.value = JSON.stringify(tpl.points, null, 2)
  tplNote.value = noteText(tpl)
}

const p = reactive<ProbeArgs & { ip: string }>({
  ip: '', port: 502, unitId: 1, fc: 3, from: 0, count: 10, framing: 'tcp'
})
const probing = ref(false)
const probeErr = ref('')
const rows = ref<{ addr: number; v: number; hex: string; chg: string; hint?: string; hintRole?: string }[]>([])
const lastVals = ref<number[] | null>(null)
const changedSet = new Set<string>()

/**
 * 每个地址的观察统计（2026-10-29）：用来「自动猜角色」——
 * 用户的核心疑问是"那么多值我怎么知道哪个是哪个"。Modbus 本身没有语义，
 * 但只要在设备边启停/按几下，变化形态就能反推：
 * 单调递增 → 产量计数（role=count）；只在 0/1 之间跳 → 运行状态（role=status）；
 * 其它有变化 → 普通数值；一直非零但不动 → 常量/设定值。
 */
type AddrStat = { first: number; last: number; chg: number; up: number; down: number
  zero: boolean; one: boolean; other: boolean }
const stats = new Map<string, AddrStat>()

function noteStat(fc: number, addr: number, v: number, prev: number | null) {
  const k = fc + ':' + addr
  let s = stats.get(k)
  if (!s) {
    s = { first: v, last: v, chg: 0, up: 0, down: 0,
          zero: v === 0, one: v === 1, other: v !== 0 && v !== 1 }
    stats.set(k, s)
    return
  }
  if (prev != null && prev !== v) {
    s.chg++; if (v > prev) s.up++; else s.down++
  }
  s.last = v
  if (v === 0) s.zero = true
  else if (v === 1) s.one = true
  else s.other = true
}

/** 推测角色 + 中文标签；dataType=bool（FC2）的一律按开关量处理 */
function infer(fc: number, addr: number, v: number): { role: string; label: string } {
  const s = stats.get(fc + ':' + addr)
  const isBit = fc === 2
  if (isBit) return { role: 'status', label: t('disc.roleBit') }
  if (!s) return { role: 'value', label: '' }
  // 单调递增且变化 ≥2 次 → 计数器特征（产量/累计流量/运行小时数）
  if (s.chg >= 2 && s.up >= Math.ceil(s.chg * 0.8) && s.last > s.first) {
    return { role: 'count', label: t('disc.roleCount') }
  }
  // 只在 0/1（或偶尔 2）之间跳 → 状态位
  if (s.chg >= 1 && !s.other && (s.zero || s.one)) {
    return { role: 'status', label: t('disc.roleStatus') }
  }
  if (s.chg >= 1) return { role: 'value', label: t('disc.roleValue') }
  if (v !== 0) return { role: 'value', label: t('disc.roleConst') }
  return { role: 'value', label: '' }
}
let lastProbeArgs: ProbeArgs | null = null

const uFrom = ref(1)
const uTo = ref(32)
const unitScanning = ref(false)
const unitNote = ref('')
const alive = ref<number[]>([])

const jsonText = ref('')
const jsonNote = ref('')

/* 自动刷新为用户开关式轮询：usePolling 是「挂载即启」契约，这里用局部定时器 +
   onScopeDispose 清理（唯一例外，路由离开必清） */
const autoOn = ref(false)
let autoTimer: number | null = null

async function doScan() {
  scanning.value = true
  scanErr.value = ''
  scanNote.value = t('disc.scanningNote')
  scanDone.value = false
  try {
    const d = await discoveryApi.scan({ ...s })
    if (!d.ok) { scanErr.value = d.error || t('disc.scanFailed'); scanNote.value = ''; return }
    found.value = d.found || []
    scanDone.value = true
    scanNote.value = t('disc.scanDone', { n: d.scanned, ms: d.elapsedMs, found: found.value.length, port: d.port })
  } catch (e: any) {
    scanErr.value = e?.message || t('disc.scanFailed'); scanNote.value = ''
  } finally { scanning.value = false }
}

function pickIp(ip: string, port: number) {
  p.ip = ip
  p.port = port
  probeOnce()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function probeOnce() {
  const args: ProbeArgs = {
    ip: p.ip.trim(), port: p.port, unitId: p.unitId,
    fc: p.fc, from: p.from, count: p.count, framing: p.framing
  }
  // 换了目标（IP/从站/功能码/地址段）就重置观察统计，
  // 否则上一台设备的"变化/推测"会串到这台来（多设备现场必踩）
  const prevArgs = lastProbeArgs
  if (prevArgs && (prevArgs.ip !== args.ip || prevArgs.port !== args.port
      || prevArgs.unitId !== args.unitId || prevArgs.fc !== args.fc
      || prevArgs.from !== args.from || prevArgs.count !== args.count)) {
    stats.clear(); changedSet.clear(); lastVals.value = null; jsonText.value = ''
  }
  lastProbeArgs = args
  probing.value = true
  try {
    const d = await discoveryApi.probe(args)
    if (!d.ok) { probeErr.value = t('disc.readFailedPrefix') + (d.error || ''); return }
    probeErr.value = ''
    const vals = d.values || []
    const hex = d.hex || []
    const prev = lastVals.value
    rows.value = vals.map((v, i) => {
      const addr = args.from + i
      let chg = ''
      if (prev && prev.length === vals.length && prev[i] !== v) {
        chg = (v > prev[i] ? '↑ ' : '↓ ') + prev[i] + ' → ' + v
        changedSet.add(args.fc + ':' + addr)
      }
      noteStat(args.fc, addr, v, prev && prev.length === vals.length ? prev[i] : null)
      const inf = infer(args.fc, addr, v)
      return { addr, v, hex: hex[i] || '', chg, hint: inf.label, hintRole: inf.role }
    })
    lastVals.value = [...vals]
  } catch (e: any) {
    probeErr.value = e?.message || t('disc.readFailed')
  } finally { probing.value = false }
}

function toggleAuto() {
  if (autoTimer) {
    window.clearInterval(autoTimer)
    autoTimer = null
    autoOn.value = false
    return
  }
  if (!p.ip.trim()) { ElMessage.warning(t('disc.needIp')); return }
  autoTimer = window.setInterval(probeOnce, 2000)
  autoOn.value = true
  probeOnce()
}

async function scanUnits() {
  const ip = p.ip.trim()
  if (!ip) { ElMessage.warning(t('disc.needBoxIp')); return }
  unitScanning.value = true
  unitNote.value = t('disc.unitScanning')
  alive.value = []
  try {
    const d = await discoveryApi.units({
      ip, port: p.port, framing: p.framing, from: uFrom.value, to: uTo.value, timeoutMs: 600
    })
    if (!d.ok) { unitNote.value = d.error || t('disc.scanFailed'); return }
    if (!(d.alive || []).length) {
      unitNote.value = t('disc.unitNone', { n: d.scanned, ms: d.elapsedMs }) + t('disc.unitNoneTail')
      return
    }
    alive.value = d.alive || []
    unitNote.value = t('disc.unitAlive', { n: d.scanned, ms: d.elapsedMs })
  } catch (e: any) {
    unitNote.value = e?.message || t('disc.scanFailed')
  } finally { unitScanning.value = false }
}

function pickUnit(u: number) {
  p.unitId = u
  probeOnce()
}

/**
 * 生成点表。三种口径（2026-10-29 用户反馈"一扫就灌 10 个空寄存器，太乱"）：
 * - smart（默认）：只保留「对拍时变化过」或「当前值非零」的地址——
 *   空寄存器、没接的点不会被搬进设备卡片
 * - changed：只保留变化过的（严格对拍）
 * - all：全部地址（排查用，慎用于添加设备）
 */
function genJson(mode: 'all' | 'changed' | 'smart') {
  if (!lastProbeArgs) { ElMessage.warning(t('disc.needProbe')); return }
  const args = lastProbeArgs
  jsonText.value = ''          // 先清空：生成不出来时不能留着上一次的旧内容
  tplId.value = ''             // 生成会覆盖点表，模板选择一并清掉
  tplNote.value = ''
  const isBit = args.fc === 2
  const addrList: number[] = []
  for (let i = 0; i < args.count; i++) {
    const addr = args.from + i
    const changed = changedSet.has(args.fc + ':' + addr)
    const val = lastVals.value?.[i] ?? 0
    const keep = mode === 'all'
      ? true
      : mode === 'changed'
        ? changed
        : (changed || (isBit ? val === 1 : val !== 0))
    if (keep) addrList.push(addr)
  }
  if (!addrList.length) {
    jsonNote.value = mode === 'smart' ? t('disc.allZero') : t('disc.noChange')
    return
  }
  const pts = addrList.map((a) => {
    const idx = a - args.from
    const v = lastVals.value?.[idx] ?? 0
    const inf = infer(args.fc, a, v)
    const role = isBit ? 'status' : inf.role
    // 名字带上推测结论：加进设备后一眼能看出每个点是什么（可再手改）
    const base = isBit ? t('disc.baseDi') : t('disc.baseReg')
    const name = role === 'count' ? t('disc.pointCount', { a })
      : role === 'status' ? t('disc.pointStatus', { base, a })
      : `${base}${a}`
    return {
      key: (isBit ? 'di' : 'r') + a,
      name, address: a, functionCode: args.fc,
      dataType: isBit ? 'bool' : 'u16', scale: 1, unit: '', role
    }
  })
  jsonText.value = JSON.stringify(pts, null, 2)
  const nCount = pts.filter((p) => p.role === 'count').length
  const nStatus = pts.filter((p) => p.role === 'status').length
  jsonNote.value = t('disc.genDone', { n: pts.length, kind: isBit ? t('disc.kindDi') : t('disc.kindReg') })
    + (nCount ? t('disc.genDoneCount', { n: nCount }) : '')
    + (nStatus ? t('disc.genDoneStatus', { n: nStatus }) : '')
    + t('disc.genDoneTail')
}

async function copyJson() {
  if (!jsonText.value) { ElMessage.warning(t('disc.needGen')); return }
  try {
    await navigator.clipboard.writeText(jsonText.value)
    jsonNote.value = t('disc.copied')
  } catch {
    jsonNote.value = t('disc.copyFailed')
  }
}

/**
 * 一键带到「新增设备」表单（2026-10-29 用户口径：扫到就要能加进来，且不能灌一堆空点）。
 * 点表一律按「智能」口径重新生成：只含有变化 / 非零的地址。
 * 全是 0 且没变化时直接拦下来——那种点表加进去只会是一屏"寄存器x = 0"。
 */
function addAsDevice() {
  if (!lastProbeArgs) { ElMessage.warning(t('disc.needProbe')); return }
  if (!tplId.value) genJson('smart')   // 选了模板就用模板点表，别把语义覆盖掉
  if (!jsonText.value) {
    ElMessage.warning(t('disc.allZeroWarn'))
    return
  }
  emit('addDevice', {
    protocol: p.framing === 'rtu' ? 'modbus-rtu-tcp' : 'modbus-tcp',
    host: p.ip.trim(), port: p.port, unitId: p.unitId,
    pointsText: jsonText.value
  })
}

onScopeDispose(() => { if (autoTimer) window.clearInterval(autoTimer) })

// 打开页面自动预填本机网段（后端枚举网卡），用户只需点「开始扫描」
onMounted(async () => {
  try {
    const r = await discoveryApi.localSubnets()
    if (r.ok && r.subnets?.length) {
      s.prefixes = r.subnets.join(',')
    } else if (!s.prefixes) {
      s.prefixes = '192.168.1'          // 探测失败兜底：手填也能扫
    }
  } catch {
    if (!s.prefixes) s.prefixes = '192.168.1'
  }
})
</script>

<style scoped>
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-4); }
.panel { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--elev-raised); padding: var(--space-4); min-width: 0; }
.panel.wide { grid-column: 1 / -1; }
.p-head { display: flex; align-items: baseline; gap: var(--space-2); margin-bottom: var(--space-2); }
.p-head h3 { font-size: var(--text-md); font-weight: 600; }
.p-head > span { font-size: var(--text-2xs); color: var(--meta); }
.tip { font-size: var(--text-xs); color: var(--muted); line-height: 1.8; margin-bottom: var(--space-3); }
.row { display: flex; gap: var(--space-2); flex-wrap: wrap; align-items: flex-end; margin-bottom: var(--space-3); }
.row.units { align-items: center; }
.f { display: flex; flex-direction: column; gap: 4px; }
.f label { font-size: var(--text-2xs); color: var(--muted); }
.hint { font-size: var(--text-2xs); color: var(--meta); line-height: 1.7; }
.hint.inline { line-height: 1.4; }
.lbl { font-size: var(--text-2xs); color: var(--muted); }
.bad { color: var(--alarm); font-size: var(--text-xs); }
.chips { display: flex; gap: var(--space-2); flex-wrap: wrap; margin: var(--space-2) 0; }
.etable { width: 100%; border-collapse: collapse; font-size: var(--text-sm); margin-top: var(--space-2); }
.etable th { text-align: left; font-size: var(--text-2xs); color: var(--meta); font-weight: 400; padding: 6px 8px; }
.etable td { padding: 6px 8px; border-bottom: 1px solid var(--border-soft); }
.etable tr.chg td { background: var(--idle-soft, rgba(234, 179, 8, .13)); }
.chg-cell { color: var(--muted); font-size: var(--text-2xs); }
/* 自动推测标签：绿=疑似产量计数，蓝=疑似状态，灰=数值/常量 */
.role-tag {
  display: inline-block; padding: 1px 7px; border-radius: 999px;
  font-size: var(--text-2xs); color: var(--muted); background: var(--surface-2);
  white-space: nowrap;
}
.role-tag.role-count { color: var(--accent-on); background: var(--accent); }
.role-tag.role-status { color: var(--accent); background: var(--accent-soft, var(--surface-2)); }
.go { color: var(--accent); cursor: pointer; }
.go:hover { text-decoration: underline; }
.empty { color: var(--meta); font-size: var(--text-sm); padding: var(--space-3) 0; }
.json :deep(textarea) { font-family: var(--font-mono); font-size: var(--text-xs); }
@media (max-width: 1100px) { .grid { grid-template-columns: 1fr; } }
</style>
