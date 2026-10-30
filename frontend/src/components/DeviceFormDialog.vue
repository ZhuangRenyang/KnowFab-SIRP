<template>
  <el-dialog
    v-model="visible" :title="editCode ? '编辑设备' : '新增设备'"
    width="680px" top="6vh" :close-on-click-modal="false"
  >
    <el-form label-width="130px" label-position="right">
      <!-- 2026-10-29：用户反馈"新增设备没有自动扫描"——扫描在发现页，
           这里给个直达入口，不用退出弹窗后满界面找 -->
      <div class="scan-tip">
        不知道设备的 IP / 点表？<el-link type="primary" :underline="false" @click="goDiscovery">用自动扫描找设备并生成点表 →</el-link>
      </div>
      <div class="sec">基本信息</div>
      <div class="frow">
        <el-form-item label="设备编码"><el-input v-model="f.code" :disabled="!!editCode" placeholder="唯一，如 CNC-01" /></el-form-item>
        <el-form-item label="设备名称"><el-input v-model="f.name" placeholder="留空则用编码" /></el-form-item>
      </div>
      <div class="frow">
        <el-form-item label="位置 / 车间"><el-input v-model="f.location" /></el-form-item>
        <el-form-item label="采集周期（毫秒）"><el-input-number v-model="f.pollIntervalMs" :min="100" :step="100" /></el-form-item>
        <el-form-item label="日目标产量">
          <el-input-number v-model="f.dailyTarget" :min="1" :max="10000000" placeholder="选填" style="width:100%" />
          <div class="hint">选填（件）。设备卡片与车间大屏按「今日产量 ÷ 目标」显示完成率红绿灯</div>
        </el-form-item>
      </div>
      <div class="frow">
        <el-form-item label="伺服/PLC 品牌">
          <el-select v-model="f.servoBrand">
            <el-option v-for="b in BRANDS" :key="b.value" :value="b.value" :label="b.label" />
          </el-select>
        </el-form-item>
      </div>

      <div class="sec">连接参数</div>
      <div class="frow">
        <el-form-item label="协议">
          <el-select v-model="f.protocol" @change="onProto">
            <el-option v-for="p in PROTOCOLS" :key="p.value" :value="p.value" :label="p.label" />
          </el-select>
        </el-form-item>
      </div>
      <div v-if="isNet" class="frow">
        <el-form-item label="IP 地址"><el-input v-model="f.host" placeholder="如 192.168.1.50" /></el-form-item>
        <el-form-item label="端口"><el-input-number v-model="f.port" :min="1" :max="65535" /></el-form-item>
      </div>
      <div v-if="isRtuTcp" class="frow">
        <el-form-item label="从站地址">
          <el-input-number v-model="f.unitId2" :min="1" :max="247" />
          <span class="hint">填盒子后面那台设备的 485 站号</span>
        </el-form-item>
      </div>
      <div v-if="isRtu" class="rtu">
        <div class="frow">
          <el-form-item label="串口"><el-input v-model="f.serialPort" placeholder="如 COM3" /></el-form-item>
          <el-form-item label="波特率">
            <el-select v-model="f.baudRate">
              <el-option v-for="b in [9600, 19200, 38400, 115200]" :key="b" :value="b" :label="String(b)" />
            </el-select>
          </el-form-item>
        </div>
        <div class="frow">
          <el-form-item label="数据位">
            <el-select v-model="f.dataBits"><el-option :value="8" label="8" /><el-option :value="7" label="7" /></el-select>
          </el-form-item>
          <el-form-item label="停止位">
            <el-select v-model="f.stopBits"><el-option :value="1" label="1" /><el-option :value="2" label="2" /></el-select>
          </el-form-item>
          <el-form-item label="校验">
            <el-select v-model="f.parity">
              <el-option :value="0" label="无" /><el-option :value="1" label="奇" /><el-option :value="2" label="偶" />
            </el-select>
          </el-form-item>
          <el-form-item label="从站地址"><el-input-number v-model="f.unitId" :min="1" :max="247" /></el-form-item>
        </div>
      </div>

      <div class="sec">生产入库联动（DH-44：绑定成品后，计数点每 +N 自动入成品仓）</div>
      <el-form-item label-width="90px">
        <el-switch v-model="f.prodAutoStockin" active-text="启用" inactive-text="关闭" />
      </el-form-item>
      <template v-if="f.prodAutoStockin">
        <div class="frow">
          <el-form-item label="成品产品">
            <el-select v-model="f.prodSkuId" filterable clearable placeholder="选这台机器生产的成品（基础资料里维护）" style="width:100%">
              <el-option v-for="s in skuList" :key="s.id" :value="s.id" :label="`${s.code} · ${s.name}`" />
            </el-select>
          </el-form-item>
          <el-form-item label="目标仓库">
            <el-select v-model="f.prodWarehouseId" clearable placeholder="留空 = 默认仓" style="width:100%">
              <el-option v-for="w in whList" :key="w.id" :value="w.id" :label="`${w.code} · ${w.name}`" />
            </el-select>
          </el-form-item>
        </div>
        <div class="frow">
          <el-form-item label="每日清零">
            <el-input-number v-model="f.prodResetHour" :min="0" :max="23" />
            <span class="hint">点：日产量统计从这个时刻重新开始（如早班 8 点交接填 8）</span>
          </el-form-item>
        </div>
        <div class="hint" style="margin:-6px 0 8px 90px">
          前提：点表里有 role=count 的累计产量点。计数器本身的清零是现场的事，系统在读数变小时自动按重置处理，产量不会算成负数。
        </div>
      </template>

      <div class="sec">对外接口</div>
      <div class="frow">
        <el-form-item label="对外 API Key">
          <div class="keyrow">
            <el-input v-model="f.apiKey" placeholder="留空 = 不对外" />
            <el-button @click="f.apiKey = genKey(f.code)">生成</el-button>
          </div>
        </el-form-item>
      </div>
      <el-form-item label-width="130px">
        <el-checkbox v-model="f.apiKeyEnabled">允许通过这个 Key 调用对外接口（停用只挡 /open/v1，采集照常）</el-checkbox>
      </el-form-item>

      <div class="sec">点表（JSON 数组）</div>
      <!-- 设备模板（2026-10-29）：内置模板随版本发布；「我的模板」是现场存下来的，
           同型号设备选一次就能把协议/端口/从站/采集周期/点表全部带过来 -->
      <div class="tpls">
        <el-select v-model="tplId" placeholder="选设备模板（品牌 · 型号 / 我的模板）" clearable
                   style="width:340px" @change="applyTpl">
          <el-option-group v-for="g in tplGroups" :key="g.brand" :label="g.brand">
            <el-option v-for="t in g.list" :key="t.id" :value="t.id"
                       :label="`${t.model}${t.confidence === 'verified' ? ' · 已实测' : ''}`" />
          </el-option-group>
          <el-option-group v-if="customTpls.length" label="我的模板（已存）">
            <el-option v-for="t in customTpls" :key="'c' + t.id" :value="'c' + t.id" :label="t.name" />
          </el-option-group>
        </el-select>
        <el-button size="small" @click="saveAsTpl">存为模板</el-button>
        <el-button v-if="isCustomTpl" size="small" text type="danger" @click="delTpl">删除模板</el-button>
        <span class="hint">{{ tplNote }}</span>
      </div>
      <div class="tpls">
        <el-button size="small" @click="useTpl('machine')">机床通用</el-button>
        <el-button size="small" @click="useTpl('light')">三色灯干接点</el-button>
        <el-button size="small" @click="useTpl('diCounter')">DI 计数（老设备）</el-button>
        <el-button size="small" @click="useTpl('energy')">电表</el-button>
        <el-button size="small" @click="useTpl('empty')">清空</el-button>
      </div>
      <el-input
        v-model="f.pointsText" type="textarea" :rows="12"
        class="mono" placeholder="点表 JSON 数组"
      />
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="busy" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '@/api/http'
import { devicesApi, genKey, PROTOCOLS, BRANDS, TPL } from '@/api/devices'
import { skuApi, warehouseApi } from '@/api/erp'
import { DEVICE_TEMPLATES, deviceTplApi, type DeviceTpl, type CustomTpl } from '@/api/deviceTemplates'
import { useDevicesStore } from '@/stores/devices'

const props = defineProps<{
  editCode: string
  /** 从设备发现带过来的预填（扫描→探测→添加设备，2026-10-29） */
  prefill?: {
    protocol?: string; host?: string; port?: number; unitId?: number; pointsText?: string
  } | null
}>()
const emit = defineEmits<{ saved: [] }>()
const visible = defineModel<boolean>({ default: false })

const router = useRouter()
const store = useDevicesStore()
const busy = ref(false)

/** 关掉弹窗去发现页：扫描 → 探测 → 生成点表，回来再贴进点表框 */
function goDiscovery() {
  visible.value = false
  router.push('/discovery')
}

const f = reactive({
  code: '', name: '', location: '', pollIntervalMs: 1000,
  protocol: 'modbus-tcp', host: '', port: 502,
  serialPort: 'COM3', baudRate: 9600, dataBits: 8, stopBits: 1, parity: 0,
  unitId: 1, unitId2: 1,
  apiKey: '', apiKeyEnabled: true, servoBrand: '',
  pointsText: JSON.stringify(TPL.machine, null, 2),
  prodSkuId: undefined as number | undefined, prodWarehouseId: undefined as number | undefined,
  prodResetHour: 0, prodAutoStockin: false,
  dailyTarget: undefined as number | undefined
})

const skuList = ref<any[]>([])
const whList = ref<any[]>([])

const isNet = computed(() => f.protocol === 'modbus-tcp' || f.protocol === 'modbus-rtu-tcp')
const isRtuTcp = computed(() => f.protocol === 'modbus-rtu-tcp')
const isRtu = computed(() => f.protocol === 'modbus-rtu')

/* 设备模板：内置（随版本） + 我的模板（落库）。选中即预填通信参数 + 点表 */
const tplId = ref('')
const tplNote = ref('')
const customTpls = ref<CustomTpl[]>([])
const isCustomTpl = computed(() => tplId.value.startsWith('c'))

const tplGroups = computed(() => {
  const m = new Map<string, DeviceTpl[]>()
  for (const t of DEVICE_TEMPLATES) {
    if (!m.has(t.brand)) m.set(t.brand, [])
    m.get(t.brand)!.push(t)
  }
  return [...m.entries()].map(([brand, list]) => ({ brand, list }))
})

async function loadCustomTpls() {
  try {
    const r = await deviceTplApi.list()
    customTpls.value = r.ok ? (r.items || []) : []
  } catch { customTpls.value = [] }
}

/** 存为模板：编辑现有设备时直接抓设备配置（点表里的 role 最完整）；
 *  新增场景则按当前表单内容存 */
async function saveAsTpl() {
  try {
    const { value: name } = await ElMessageBox.prompt(
      props.editCode ? `把设备 ${props.editCode} 的完整配置存成模板` : '把当前填写的配置存成模板',
      '存为设备模板',
      { inputPlaceholder: '模板名，如：汇川 H3U 注塑机', inputValidator: (v) => (!!v && !!v.trim()) || '模板名不能为空' }
    )
    let r
    if (props.editCode) {
      r = await deviceTplApi.fromDevice(props.editCode, { name: name.trim() })
    } else {
      r = await deviceTplApi.create({
        name: name.trim(), brand: '', model: f.name || f.code,
        protocol: f.protocol, port: f.port, unitId: f.unitId,
        pollIntervalMs: f.pollIntervalMs, timeoutMs: f.timeoutMs,
        serialPort: f.serialPort, baudRate: f.baudRate, dataBits: f.dataBits,
        stopBits: f.stopBits, parity: f.parity,
        pointsJson: f.pointsText, note: ''
      })
    }
    if (!r.ok) { ElMessage.warning(r.message || '保存失败'); return }
    ElMessage.success(r.message || '模板已保存')
    await loadCustomTpls()
  } catch (e: any) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.warning(e?.message || '保存失败')
  }
}

async function delTpl() {
  const id = Number(tplId.value.slice(1))
  if (!id) return
  try {
    await ElMessageBox.confirm('删除后不可恢复，已套用该模板创建的设备不受影响。继续？', '删除模板', { type: 'warning' })
  } catch { return }
  const r = await deviceTplApi.remove(id).catch(() => null)
  if (!r?.ok) { ElMessage.warning(r?.message || '删除失败'); return }
  ElMessage.success('模板已删除')
  tplId.value = ''
  tplNote.value = ''
  await loadCustomTpls()
}

function applyTpl(key: string) {
  // 我的模板：key = 'c<id>'
  if (key.startsWith('c')) {
    const t = customTpls.value.find((x) => 'c' + x.id === key)
    if (!t) { tplNote.value = ''; return }
    f.protocol = t.protocol
    f.port = t.port
    f.unitId = t.unitId
    f.pollIntervalMs = t.pollIntervalMs
    f.timeoutMs = t.timeoutMs
    if (t.serialPort) f.serialPort = t.serialPort
    if (t.baudRate) f.baudRate = t.baudRate
    if (t.dataBits) f.dataBits = t.dataBits
    if (t.stopBits) f.stopBits = t.stopBits
    if (t.parity != null) f.parity = t.parity
    f.pointsText = t.pointsJson
    tplNote.value = `${t.name}${t.note ? '：' + t.note : ''}`
    return
  }
  const t = DEVICE_TEMPLATES.find((x) => x.id === key)
  if (!t) { tplNote.value = ''; return }
  f.protocol = t.protocol
  f.port = t.port
  f.unitId = t.unitId
  f.pollIntervalMs = t.pollIntervalMs
  f.timeoutMs = t.timeoutMs
  if (t.serialPort) f.serialPort = t.serialPort
  if (t.baudRate) f.baudRate = t.baudRate
  if (t.dataBits) f.dataBits = t.dataBits
  if (t.stopBits) f.stopBits = t.stopBits
  if (t.parity != null) f.parity = t.parity
  f.pointsText = JSON.stringify(t.points, null, 2)
  tplNote.value = t.note
}

function resetDefaults() {
  Object.assign(f, {
    code: '', name: '', location: '', pollIntervalMs: 1000,
    protocol: 'modbus-tcp', host: '', port: 502,
    serialPort: 'COM3', baudRate: 9600, dataBits: 8, stopBits: 1, parity: 0,
    unitId: 1, unitId2: 1, apiKey: '', apiKeyEnabled: true, servoBrand: '',
    pointsText: JSON.stringify(TPL.machine, null, 2),
    prodSkuId: undefined, prodWarehouseId: undefined, prodResetHour: 0, prodAutoStockin: false,
    dailyTarget: undefined
  })
  tplId.value = ''
  tplNote.value = ''
}

watch(visible, async (v) => {
  if (!v) return
  loadCustomTpls()               // 我的模板：不阻塞主流程，失败静默
  /* 生产入库联动下拉数据（失败静默，主流程不受影响） */
  try {
    const [ss, ws] = await Promise.all([skuApi.list(), warehouseApi.list()])
    skuList.value = ss
    whList.value = ws
  } catch { /* 忽略 */ }
  if (!props.editCode) {
    resetDefaults()
    // 从设备发现带过来的预填：IP/端口/从站号/点表直接填好，用户只补编码名称
    const pf = props.prefill
    if (pf) {
      if (pf.protocol) f.protocol = pf.protocol
      if (pf.host) f.host = pf.host
      if (pf.port) f.port = pf.port
      if (pf.unitId) f.unitId = pf.unitId
      if (pf.pointsText) f.pointsText = pf.pointsText
    }
    return
  }
  /* 编辑：config 接口回填，编码是主键禁改（PRD §1.1） */
  const c: any = await devicesApi.config(props.editCode).catch(() => null)
  if (!c) { visible.value = false; return }
  Object.assign(f, {
    code: c.code || '', name: c.name || '', location: c.location || '',
    pollIntervalMs: c.pollIntervalMs || 1000,
    protocol: c.protocol || 'modbus-tcp', host: c.host || '', port: c.port || 502,
    serialPort: c.serialPort || 'COM3', baudRate: c.baudRate || 9600,
    dataBits: c.dataBits || 8, stopBits: c.stopBits || 1, parity: c.parity == null ? 0 : c.parity,
    unitId: c.unitId || 1, unitId2: c.unitId || 1,
    apiKey: c.apiKey || '', apiKeyEnabled: c.apiKeyEnabled !== false,
    servoBrand: c.servoBrand || ''
  })
  Object.assign(f, {
    prodSkuId: c.prodSkuId ?? undefined, prodWarehouseId: c.prodWarehouseId ?? undefined,
    prodResetHour: c.prodResetHour ?? 0, prodAutoStockin: !!c.prodAutoStockin,
    dailyTarget: c.dailyTarget ?? undefined
  })
  try {
    f.pointsText = JSON.stringify(JSON.parse(c.pointsJson || '[]'), null, 2)
  } catch {
    f.pointsText = c.pointsJson || '[]'
  }
})

function useTpl(k: string) {
  f.pointsText = JSON.stringify(TPL[k], null, 2)
}

async function save() {
  if (busy.value) return
  const code = f.code.trim()
  if (!code) { ElMessage.warning('设备编码必填'); return }
  let points: any
  try {
    points = JSON.parse(f.pointsText || '[]')
    if (!Array.isArray(points) || !points.length) { ElMessage.warning('点表不能为空'); return }
  } catch (e: any) {
    ElMessage.warning('点表 JSON 格式错误：' + e.message)
    return
  }
  const body = {
    code,
    name: f.name.trim() || code,
    location: f.location.trim(),
    protocol: f.protocol,
    host: f.host.trim(),
    port: Number(f.port) || 502,
    serialPort: f.serialPort.trim(),
    baudRate: Number(f.baudRate),
    dataBits: Number(f.dataBits),
    stopBits: Number(f.stopBits),
    parity: Number(f.parity),
    unitId: Number(f.protocol === 'modbus-rtu' ? f.unitId : f.unitId2) || 1,
    pollIntervalMs: Number(f.pollIntervalMs) || 1000,
    enabled: true,
    apiKey: f.apiKey.trim(),
    apiKeyEnabled: f.apiKeyEnabled,
    servoBrand: f.servoBrand,
    points,
    prodSkuId: f.prodSkuId ?? null,
    prodWarehouseId: f.prodWarehouseId ?? null,
    prodResetHour: Number(f.prodResetHour) || 0,
    prodAutoStockin: f.prodAutoStockin,
    dailyTarget: f.dailyTarget ? Number(f.dailyTarget) : null
  }
  busy.value = true
  try {
    if (props.editCode) {
      const d = store.list.find((x: any) => x.code === props.editCode)
      if (!d) { ElMessage.error('找不到待编辑设备，请刷新列表'); return }
      await devicesApi.update(d.id, body)
    } else {
      await devicesApi.save(body)
    }
    emit('saved')
  } catch (e: any) {
    if (e instanceof ApiError) {
      ElMessage.error(`保存失败 HTTP ${e.status}：${typeof e.body === 'string' ? e.body : e.message}`)
    } else {
      ElMessage.error(e?.message || '保存失败')
    }
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.sec {
  font-size: var(--text-xs); color: var(--meta); letter-spacing: 1px;
  margin: var(--space-3) 0 var(--space-2); border-bottom: 1px solid var(--border-soft);
  padding-bottom: 4px;
}
.frow { display: flex; gap: var(--space-4); flex-wrap: wrap; }
.frow .el-form-item { flex: 1; min-width: 220px; }
.hint { margin-left: 8px; font-size: var(--text-xs); color: var(--meta); }
.keyrow { display: flex; gap: 8px; width: 100%; }
.tpls { display: flex; gap: 8px; margin-bottom: var(--space-2); flex-wrap: wrap; }
.mono :deep(textarea) { font-family: var(--font-mono); font-size: var(--text-xs); }
.scan-tip {
  margin: 0 0 var(--space-2) 130px; padding: 6px 10px; font-size: var(--text-xs);
  color: var(--meta); background: var(--bg-soft); border-radius: var(--radius-sm);
}
</style>
