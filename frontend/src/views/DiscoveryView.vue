<template>
  <div class="disco">
    <div class="grid">
      <div class="panel">
        <div class="p-head"><h3>第一步 · 扫网段找设备</h3>
          <span>只读探测 · 单次上限 1024 台</span></div>
        <div class="tip">
          并发测一批 C 段里谁开着 Modbus 端口。同一局域网内，千台规模十几秒出结果；
          大厂设备跨多个网段就全填上（逗号隔开），更多分几批。
        </div>
        <div class="row">
          <div class="f wide"><label>网段（多段用英文逗号隔开）</label>
            <el-input v-model="s.prefixes" placeholder="192.168.1,192.168.2" style="width:230px" /></div>
          <div class="f"><label>起始</label><el-input v-model.number="s.from" type="number" style="width:90px" /></div>
          <div class="f"><label>结束</label><el-input v-model.number="s.to" type="number" style="width:90px" /></div>
          <div class="f"><label>端口</label><el-input v-model.number="s.port" type="number" style="width:90px" /></div>
          <div class="f"><label>超时 ms</label><el-input v-model.number="s.timeoutMs" type="number" style="width:90px" /></div>
          <el-button type="primary" :loading="scanning" @click="doScan">{{ scanning ? '扫描中…' : '开始扫描' }}</el-button>
        </div>
        <div class="hint">扫的是本机所在网络——确认服务器和设备网线/WiFi 在同一路由下。</div>
        <div v-if="scanErr" class="bad">{{ scanErr }}</div>
        <div v-else-if="scanNote" class="hint">{{ scanNote }}</div>
        <table v-if="found.length" class="etable">
          <thead><tr><th>IP 地址</th><th>端口</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="f in found" :key="f.ip">
              <td class="num">{{ f.ip }}</td><td class="num">{{ f.port }}</td>
              <td><span class="go" @click="pickIp(f.ip, f.port)">探测这台 →</span></td>
            </tr>
          </tbody>
        </table>
        <div v-else-if="scanDone" class="empty">这个范围里没有开着该端口的设备</div>
      </div>

      <div class="panel">
        <div class="p-head"><h3>第二步 · 读寄存器对拍找点</h3>
          <span>启停机器看哪个值在变</span></div>
        <div class="tip">
          对扫描到的 IP 读一段寄存器，开自动刷新（2 秒一次），然后去设备边：
          <b>启动机器 → 看哪个值变了；停机再看；报警再看</b>。
          跟着状态走的 0/1/2 就是运行状态，单调递增的是产量计数，报警时非 0 的是报警字。
        </div>
        <div class="row">
          <div class="f"><label>IP 地址</label><el-input v-model="p.ip" placeholder="192.168.1.50" style="width:150px" /></div>
          <div class="f"><label>端口</label><el-input v-model.number="p.port" type="number" style="width:86px" /></div>
          <div class="f"><label>从站号</label><el-input v-model.number="p.unitId" type="number" style="width:86px" /></div>
          <div class="f"><label>功能码</label>
            <el-select v-model="p.fc" style="width:172px">
              <el-option :value="3" label="03 保持寄存器" />
              <el-option :value="4" label="04 输入寄存器" />
              <el-option :value="2" label="02 离散输入（DI）" />
            </el-select></div>
          <div class="f"><label>帧格式</label>
            <el-select v-model="p.framing" style="width:170px">
              <el-option value="tcp" label="Modbus TCP（网口）" />
              <el-option value="rtu" label="RTU 透传（串口服务器）" />
            </el-select></div>
          <div class="f"><label>起始地址</label><el-input v-model.number="p.from" type="number" style="width:90px" /></div>
          <div class="f"><label>数量</label><el-input v-model.number="p.count" type="number" style="width:80px" /></div>
        </div>
        <div class="row">
          <el-button type="primary" :loading="probing" @click="probeOnce">读一次</el-button>
          <el-button :type="autoOn ? 'warning' : 'default'" @click="toggleAuto">
            {{ autoOn ? '⏸ 停止自动刷新' : '▶ 自动刷新（2 秒）' }}
          </el-button>
          <el-button @click="genJson(true)">生成点表（全部地址）</el-button>
          <el-button @click="genJson(false)">生成点表（仅变化过的）</el-button>
        </div>
        <div class="row units">
          <el-button size="small" :loading="unitScanning" @click="scanUnits">扫从站</el-button>
          <span class="lbl">范围</span>
          <el-input v-model.number="uFrom" type="number" style="width:70px" size="small" />
          <span class="lbl">到</span>
          <el-input v-model.number="uTo" type="number" style="width:70px" size="small" />
          <span class="hint inline">一台盒子后面挂多台 485 设备时，先扫出哪些从站号是活的</span>
        </div>
        <div v-if="unitNote" class="hint">{{ unitNote }}</div>
        <div v-if="alive.length" class="chips">
          <el-button v-for="u in alive" :key="u" size="small" @click="pickUnit(u)">从站 {{ u }}</el-button>
        </div>
        <div class="hint">
          地址是 0 基的（厂家手册写 40001 时这里填 0）。RTU 透传设备生成点表后，
          添加设备时协议要选 <b>modbus-rtu-tcp</b>，IP/端口填盒子的。
          <span class="bad">工具只读不写——绝不碰写功能码，写错地址会改掉设备参数。</span>
        </div>
        <table v-if="rows.length" class="etable">
          <thead><tr><th>地址</th><th>十进制</th><th>十六进制</th><th>变化</th></tr></thead>
          <tbody>
            <tr v-for="r in rows" :key="r.addr" :class="{ chg: !!r.chg }">
              <td class="num">{{ r.addr }}</td><td class="num">{{ r.v }}</td>
              <td class="num">{{ r.hex }}</td><td class="chg-cell">{{ r.chg }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="probeErr" class="bad">{{ probeErr }}</div>
      </div>

      <div class="panel wide">
        <div class="p-head"><h3>第三步 · 点表 JSON（贴到「新增设备」的点表框）</h3>
          <span>生成后复制 → 设备监控 → 新增设备 → 粘贴</span></div>
        <div class="tip">
          把 name 改成人话、role 改成实际角色：状态点 role=status、产量计数 role=count、报警字 role=alarm、
          普通数值 role=value。dataType 先按 u16 试，值超过 65535 或对不上再查手册（可能是 u32/浮点）。
        </div>
        <el-input v-model="jsonText" type="textarea" :rows="9" spellcheck="false" class="json"
                  placeholder="点第二步「生成点表」后出现在这里" />
        <div class="row" style="margin-top:10px">
          <el-button @click="copyJson">复制 JSON</el-button>
          <span class="hint">{{ jsonNote }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onScopeDispose, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { discoveryApi, type ProbeArgs } from '@/api/discovery'

const s = reactive({ prefixes: '192.168.1', from: 1, to: 254, port: 502, timeoutMs: 300 })
const scanning = ref(false)
const scanErr = ref('')
const scanNote = ref('')
const scanDone = ref(false)
const found = ref<{ ip: string; port: number }[]>([])

const p = reactive<ProbeArgs & { ip: string }>({
  ip: '', port: 502, unitId: 1, fc: 3, from: 0, count: 20, framing: 'tcp'
})
const probing = ref(false)
const probeErr = ref('')
const rows = ref<{ addr: number; v: number; hex: string; chg: string }[]>([])
const lastVals = ref<number[] | null>(null)
const changedSet = new Set<string>()
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
  scanNote.value = '正在扫，最多十几秒，取决于超时设置和范围…'
  scanDone.value = false
  try {
    const d = await discoveryApi.scan({ ...s })
    if (!d.ok) { scanErr.value = d.error || '扫描失败'; scanNote.value = ''; return }
    found.value = d.found || []
    scanDone.value = true
    scanNote.value = `扫了 ${d.scanned} 个地址，用时 ${d.elapsedMs}ms，找到 ${found.value.length} 台开着 ${d.port} 端口：`
  } catch (e: any) {
    scanErr.value = e?.message || '扫描失败'; scanNote.value = ''
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
  lastProbeArgs = args
  probing.value = true
  try {
    const d = await discoveryApi.probe(args)
    if (!d.ok) { probeErr.value = '读取失败：' + (d.error || ''); return }
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
      return { addr, v, hex: hex[i] || '', chg }
    })
    lastVals.value = [...vals]
  } catch (e: any) {
    probeErr.value = e?.message || '读取失败'
  } finally { probing.value = false }
}

function toggleAuto() {
  if (autoTimer) {
    window.clearInterval(autoTimer)
    autoTimer = null
    autoOn.value = false
    return
  }
  if (!p.ip.trim()) { ElMessage.warning('先填 IP 地址'); return }
  autoTimer = window.setInterval(probeOnce, 2000)
  autoOn.value = true
  probeOnce()
}

async function scanUnits() {
  const ip = p.ip.trim()
  if (!ip) { ElMessage.warning('先填盒子的 IP 地址'); return }
  unitScanning.value = true
  unitNote.value = '正在扫从站，串口线是独占的，最多等十几秒…'
  alive.value = []
  try {
    const d = await discoveryApi.units({
      ip, port: p.port, framing: p.framing, from: uFrom.value, to: uTo.value, timeoutMs: 600
    })
    if (!d.ok) { unitNote.value = d.error || '扫描失败'; return }
    if (!(d.alive || []).length) {
      unitNote.value = `扫了 ${d.scanned} 个从站号（${d.elapsedMs}ms），没有应答——` +
        '查盒子是否透传模式、波特率是否和设备一致、A/B 线是否接反。'
      return
    }
    alive.value = d.alive || []
    unitNote.value = `扫了 ${d.scanned} 个从站号（${d.elapsedMs}ms），活着的从站：点一下直接用它探测`
  } catch (e: any) {
    unitNote.value = e?.message || '扫描失败'
  } finally { unitScanning.value = false }
}

function pickUnit(u: number) {
  p.unitId = u
  probeOnce()
}

function genJson(all: boolean) {
  if (!lastProbeArgs) { ElMessage.warning('先在第二步读一次寄存器'); return }
  const args = lastProbeArgs
  const addrList: number[] = []
  for (let i = 0; i < args.count; i++) {
    const addr = args.from + i
    if (all || changedSet.has(args.fc + ':' + addr)) addrList.push(addr)
  }
  if (!addrList.length) {
    jsonNote.value = '还没有观察到变化的地址——先开着自动刷新去设备边启停几次再回来。'
    return
  }
  const isBit = args.fc === 2
  const pts = addrList.map((a) => ({
    key: (isBit ? 'di' : 'r') + a,
    name: (isBit ? 'DI 通道' : '寄存器') + a,
    address: a, functionCode: args.fc,
    dataType: isBit ? 'bool' : 'u16', scale: 1, unit: '', role: 'value'
  }))
  jsonText.value = JSON.stringify(pts, null, 2)
  jsonNote.value = `生成了 ${pts.length} 个点（${isBit ? '离散输入' : '寄存器'}）。`
    + '记得改 name 和 role：DI 通道按含义配 status/alarm，计数寄存器配 count。'
}

async function copyJson() {
  if (!jsonText.value) { ElMessage.warning('还没有生成点表'); return }
  try {
    await navigator.clipboard.writeText(jsonText.value)
    jsonNote.value = '已复制，去「新增设备」的点表框粘贴。'
  } catch {
    jsonNote.value = '复制失败，请手动全选复制。'
  }
}

onScopeDispose(() => { if (autoTimer) window.clearInterval(autoTimer) })
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
.go { color: var(--accent); cursor: pointer; }
.go:hover { text-decoration: underline; }
.empty { color: var(--meta); font-size: var(--text-sm); padding: var(--space-3) 0; }
.json :deep(textarea) { font-family: var(--font-mono); font-size: var(--text-xs); }
@media (max-width: 1100px) { .grid { grid-template-columns: 1fr; } }
</style>
