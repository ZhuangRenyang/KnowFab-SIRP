<template>
  <el-dialog
    v-model="visible" :title="editCode ? t('devForm.titleEdit') : t('devForm.titleNew')"
    width="680px" top="6vh" :close-on-click-modal="false"
  >
    <el-form label-width="130px" label-position="right">
      <!-- 2026-10-29：用户反馈"新增设备没有自动扫描"——扫描在发现页，
           这里给个直达入口，不用退出弹窗后满界面找 -->
      <div class="scan-tip">
        {{ t('devForm.scanTip') }}<el-link type="primary" :underline="false" @click="goDiscovery">{{ t('devForm.scanLink') }}</el-link>
      </div>
      <div class="sec">{{ t('devForm.secBasic') }}</div>
      <div class="frow">
        <el-form-item :label="t('devForm.code')"><el-input v-model="f.code" :disabled="!!editCode" :placeholder="t('devForm.codePh')" /></el-form-item>
        <el-form-item :label="t('devForm.name')"><el-input v-model="f.name" :placeholder="t('devForm.namePh')" /></el-form-item>
      </div>
      <div class="frow">
        <el-form-item :label="t('devForm.location')"><el-input v-model="f.location" /></el-form-item>
        <el-form-item :label="t('devForm.pollInterval')"><el-input-number v-model="f.pollIntervalMs" :min="100" :step="100" /></el-form-item>
        <el-form-item :label="t('devForm.dailyTarget')">
          <el-input-number v-model="f.dailyTarget" :min="1" :max="10000000" :placeholder="t('devForm.optional')" style="width:100%" />
          <div class="hint">{{ t('devForm.dailyTargetHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('devForm.idealCycle')">
          <el-input-number v-model="f.idealCycleSec" :min="0.1" :step="0.5" :precision="2" :placeholder="t('devForm.optional')" style="width:100%" />
          <div class="hint">{{ t('devForm.idealCycleHint') }}</div>
        </el-form-item>
      </div>
      <div class="frow">
        <el-form-item :label="t('devForm.brand')">
          <el-select v-model="f.servoBrand">
            <el-option v-for="b in BRANDS" :key="b.value" :value="b.value" :label="t(b.labelKey)" />
          </el-select>
        </el-form-item>
      </div>

      <div class="sec">{{ t('devForm.secConn') }}</div>
      <div class="frow">
        <el-form-item :label="t('devForm.protocol')">
          <el-select v-model="f.protocol" @change="onProto">
            <el-option v-for="p in PROTOCOLS" :key="p.value" :value="p.value" :label="t(p.labelKey)" />
          </el-select>
        </el-form-item>
      </div>
      <div v-if="isNet" class="frow">
        <el-form-item :label="t('devForm.host')"><el-input v-model="f.host" :placeholder="t('devForm.hostPh')" /></el-form-item>
        <el-form-item :label="t('devForm.port')"><el-input-number v-model="f.port" :min="1" :max="65535" /></el-form-item>
      </div>
      <div v-if="isRtuTcp" class="frow">
        <el-form-item :label="t('devForm.unitId')">
          <el-input-number v-model="f.unitId2" :min="1" :max="247" />
          <span class="hint">{{ t('devForm.slaveHint') }}</span>
        </el-form-item>
      </div>
      <div v-if="isRtu" class="rtu">
        <div class="frow">
          <el-form-item :label="t('devForm.serialPort')"><el-input v-model="f.serialPort" :placeholder="t('devForm.serialPortPh')" /></el-form-item>
          <el-form-item :label="t('devForm.baudRate')">
            <el-select v-model="f.baudRate">
              <el-option v-for="b in [9600, 19200, 38400, 115200]" :key="b" :value="b" :label="String(b)" />
            </el-select>
          </el-form-item>
        </div>
        <div class="frow">
          <el-form-item :label="t('devForm.dataBits')">
            <el-select v-model="f.dataBits"><el-option :value="8" label="8" /><el-option :value="7" label="7" /></el-select>
          </el-form-item>
          <el-form-item :label="t('devForm.stopBits')">
            <el-select v-model="f.stopBits"><el-option :value="1" label="1" /><el-option :value="2" label="2" /></el-select>
          </el-form-item>
          <el-form-item :label="t('devForm.parity')">
            <el-select v-model="f.parity">
              <el-option :value="0" :label="t('devForm.parityNone')" /><el-option :value="1" :label="t('devForm.parityOdd')" /><el-option :value="2" :label="t('devForm.parityEven')" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('devForm.unitId')"><el-input-number v-model="f.unitId" :min="1" :max="247" /></el-form-item>
        </div>
      </div>

      <div class="sec">{{ t('devForm.secStock') }}{{ t('devForm.secStockHint') }}</div>
      <el-form-item label-width="90px">
        <el-switch v-model="f.prodAutoStockin" :active-text="t('devForm.switchOn')" :inactive-text="t('devForm.switchOff')" />
      </el-form-item>
      <template v-if="f.prodAutoStockin">
        <div class="frow">
          <el-form-item :label="t('devForm.sku')">
            <el-select v-model="f.prodSkuId" filterable clearable :placeholder="t('devForm.skuPh')" style="width:100%">
              <el-option v-for="s in skuList" :key="s.id" :value="s.id" :label="`${s.code} · ${s.name}`" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('devForm.warehouse')">
            <el-select v-model="f.prodWarehouseId" clearable :placeholder="t('devForm.warehousePh')" style="width:100%">
              <el-option v-for="w in whList" :key="w.id" :value="w.id" :label="`${w.code} · ${w.name}`" />
            </el-select>
          </el-form-item>
        </div>
        <div class="frow">
          <el-form-item :label="t('devForm.resetHour')">
            <el-input-number v-model="f.prodResetHour" :min="0" :max="23" />
            <span class="hint">{{ t('devForm.resetHourHint') }}</span>
          </el-form-item>
        </div>
        <div class="hint" style="margin:-6px 0 8px 90px">
          {{ t('devForm.stockPrereq') }}
        </div>
      </template>

      <div class="sec">{{ t('devForm.secApi') }}</div>
      <div class="frow">
        <el-form-item :label="t('devForm.apiKeyLabel')">
          <div class="keyrow">
            <el-input v-model="f.apiKey" :placeholder="t('devForm.apiKeyPh')" />
            <el-button @click="f.apiKey = genKey(f.code)">{{ t('devForm.gen') }}</el-button>
          </div>
        </el-form-item>
      </div>
      <el-form-item label-width="130px">
        <el-checkbox v-model="f.apiKeyEnabled">{{ t('devForm.apiKeyEnable') }}</el-checkbox>
      </el-form-item>

      <div class="sec">{{ t('devForm.secPoints') }}</div>
      <!-- 设备模板（2026-10-29）：内置模板随版本发布；「我的模板」是现场存下来的，
           同型号设备选一次就能把协议/端口/从站/采集周期/点表全部带过来 -->
      <div class="tpls">
        <el-select v-model="tplId" :placeholder="t('devForm.tplPh')" clearable
                   style="width:340px" @change="applyTpl">
          <el-option-group v-for="g in tplGroups" :key="g.brand" :label="g.brand">
            <el-option v-for="tp in g.list" :key="tp.id" :value="tp.id"
                       :label="`${modelText(tp)}${tp.confidence === 'verified' ? ' · ' + verifiedLabel : ''}`" />
          </el-option-group>
          <el-option-group v-if="customTpls.length" :label="t('devForm.myTpls')">
            <el-option v-for="tp in customTpls" :key="'c' + tp.id" :value="'c' + tp.id" :label="tp.name" />
          </el-option-group>
        </el-select>
        <el-button size="small" @click="saveAsTpl">{{ t('devForm.saveTpl') }}</el-button>
        <el-button v-if="isCustomTpl" size="small" text type="danger" @click="delTpl">{{ t('devForm.delTpl') }}</el-button>
        <span class="hint">{{ tplNote }}</span>
      </div>
      <div class="tpls">
        <el-button size="small" @click="useTpl('machine')">{{ t('devForm.tplMachine') }}</el-button>
        <el-button size="small" @click="useTpl('light')">{{ t('devForm.tplLight') }}</el-button>
        <el-button size="small" @click="useTpl('diCounter')">{{ t('devForm.tplDi') }}</el-button>
        <el-button size="small" @click="useTpl('energy')">{{ t('devForm.tplEnergy') }}</el-button>
        <el-button size="small" @click="useTpl('opcua')">{{ t('devForm.tplOpcua') }}</el-button>
        <el-button size="small" @click="useTpl('relay')">{{ t('devForm.tplRelay') }}</el-button>
        <el-button size="small" @click="useTpl('empty')">{{ t('devForm.tplEmpty') }}</el-button>
      </div>
      <div class="hint" style="margin-bottom:6px">
        {{ t('devForm.writableHintA') }} <code>"writable": true</code>{{ t('devForm.writableHintB') }}
        <b>{{ t('devForm.writableHintC') }}</b>{{ t('devForm.writableHintD') }}
      </div>
      <div v-if="isOpcUa" class="hint" style="margin-bottom:6px;color:var(--accent)">
        {{ t('devForm.opcuaHint') }}
      </div>
      <el-input
        v-model="f.pointsText" type="textarea" :rows="12"
        class="mono" :placeholder="t('devForm.pointsPh')"
      />
    </el-form>
    <template #footer>
      <el-button @click="visible = false">{{ t('devForm.cancel') }}</el-button>
      <el-button type="primary" :loading="busy" @click="save">{{ t('devForm.save') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '@/api/http'
import { devicesApi, genKey, PROTOCOLS, BRANDS, TPL } from '@/api/devices'
import { skuApi, warehouseApi } from '@/api/erp'
import { DEVICE_TEMPLATES, deviceTplApi, TPL_I18N, type DeviceTpl, type CustomTpl } from '@/api/deviceTemplates'
import { useDevicesStore } from '@/stores/devices'

const { t } = useI18n()

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
  unitId: 1, unitId2: 1, timeoutMs: 800,
  apiKey: '', apiKeyEnabled: true, servoBrand: '',
  pointsText: JSON.stringify(TPL.machine, null, 2),
  prodSkuId: undefined as number | undefined, prodWarehouseId: undefined as number | undefined,
  prodResetHour: 0, prodAutoStockin: false,
  dailyTarget: undefined as number | undefined,
  idealCycleSec: undefined as number | undefined
})

/* 切协议时给行业默认端口：TCP 族 502；RTU 走串口不动端口（模板里 @change 引用，
   此函数 2026-11-01 补上——第一版基座就只有模板引用没有实现） */
function onProto() {
  if (f.protocol === 'modbus-tcp' || f.protocol === 'modbus-rtu-tcp') f.port = 502
  if (f.protocol === 'opcua') f.port = 4840
}

const skuList = ref<any[]>([])
const whList = ref<any[]>([])

const isNet = computed(() => f.protocol === 'modbus-tcp' || f.protocol === 'modbus-rtu-tcp' || f.protocol === 'opcua')
const isRtuTcp = computed(() => f.protocol === 'modbus-rtu-tcp')
const isRtu = computed(() => f.protocol === 'modbus-rtu')
const isOpcUa = computed(() => f.protocol === 'opcua')

/* 设备模板：内置（随版本） + 我的模板（落库）。选中即预填通信参数 + 点表 */
const tplId = ref('')
const tplNote = ref('')
const customTpls = ref<CustomTpl[]>([])
const isCustomTpl = computed(() => tplId.value.startsWith('c'))

/* 模板展示文本走 i18n（数据中文保留，TPL_I18N 映射见 deviceTemplates.ts） */
const verifiedLabel = computed(() => t('devForm.verified'))
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
      props.editCode ? t('devForm.saveTplFor', { code: props.editCode }) : t('devForm.saveTplCurrent'),
      t('devForm.saveTplTitle'),
      { inputPlaceholder: t('devForm.tplNamePh'), inputValidator: (v) => (!!v && !!v.trim()) || t('devForm.tplNameRequired') }
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
    if (!r.ok) { ElMessage.warning(r.message || t('devForm.saveFailed')); return }
    ElMessage.success(r.message || t('devForm.tplSaved'))
    await loadCustomTpls()
  } catch (e: any) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.warning(e?.message || t('devForm.saveFailed'))
  }
}

async function delTpl() {
  const id = Number(tplId.value.slice(1))
  if (!id) return
  try {
    await ElMessageBox.confirm(t('devForm.delTplConfirm'), t('devForm.delTpl'), { type: 'warning' })
  } catch { return }
  const r = await deviceTplApi.remove(id).catch(() => null)
  if (!r?.ok) { ElMessage.warning(r?.message || t('devForm.delFailed')); return }
  ElMessage.success(t('devForm.tplDeleted'))
  tplId.value = ''
  tplNote.value = ''
  await loadCustomTpls()
}

function applyTpl(key: string) {
  // 我的模板：key = 'c<id>'
  if (key.startsWith('c')) {
    const ct = customTpls.value.find((x) => 'c' + x.id === key)
    if (!ct) { tplNote.value = ''; return }
    f.protocol = ct.protocol
    f.port = ct.port
    f.unitId = ct.unitId
    f.pollIntervalMs = ct.pollIntervalMs
    f.timeoutMs = ct.timeoutMs
    if (ct.serialPort) f.serialPort = ct.serialPort
    if (ct.baudRate) f.baudRate = ct.baudRate
    if (ct.dataBits) f.dataBits = ct.dataBits
    if (ct.stopBits) f.stopBits = ct.stopBits
    if (ct.parity != null) f.parity = ct.parity
    f.pointsText = ct.pointsJson
    tplNote.value = `${ct.name}${ct.note ? ' · ' + ct.note : ''}`
    return
  }
  const tpl = DEVICE_TEMPLATES.find((x) => x.id === key)
  if (!tpl) { tplNote.value = ''; return }
  f.protocol = tpl.protocol
  f.port = tpl.port
  f.unitId = tpl.unitId
  f.pollIntervalMs = tpl.pollIntervalMs
  f.timeoutMs = tpl.timeoutMs
  if (tpl.serialPort) f.serialPort = tpl.serialPort
  if (tpl.baudRate) f.baudRate = tpl.baudRate
  if (tpl.dataBits) f.dataBits = tpl.dataBits
  if (tpl.stopBits) f.stopBits = tpl.stopBits
  if (tpl.parity != null) f.parity = tpl.parity
  f.pointsText = JSON.stringify(tpl.points, null, 2)
  tplNote.value = noteText(tpl)
}

function resetDefaults() {
  Object.assign(f, {
    code: '', name: '', location: '', pollIntervalMs: 1000,
    protocol: 'modbus-tcp', host: '', port: 502,
    serialPort: 'COM3', baudRate: 9600, dataBits: 8, stopBits: 1, parity: 0,
    unitId: 1, unitId2: 1, timeoutMs: 800, apiKey: '', apiKeyEnabled: true, servoBrand: '',
    pointsText: JSON.stringify(TPL.machine, null, 2),
    prodSkuId: undefined, prodWarehouseId: undefined, prodResetHour: 0, prodAutoStockin: false,
    dailyTarget: undefined, idealCycleSec: undefined
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
    dailyTarget: c.dailyTarget ?? undefined,
    idealCycleSec: c.idealCycleSec ?? undefined
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
  if (!code) { ElMessage.warning(t('devForm.codeRequired')); return }
  let points: any
  try {
    points = JSON.parse(f.pointsText || '[]')
    if (!Array.isArray(points) || !points.length) { ElMessage.warning(t('devForm.pointsRequired')); return }
  } catch (e: any) {
    ElMessage.warning(t('devForm.pointsJsonError') + e.message)
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
    dailyTarget: f.dailyTarget ? Number(f.dailyTarget) : null,
    // DH-15 理想节拍：0 和不填一样（没配就是没配，性能率算不出来）
    idealCycleSec: f.idealCycleSec ? Number(f.idealCycleSec) : null
  }
  busy.value = true
  try {
    if (props.editCode) {
      const d = store.list.find((x: any) => x.code === props.editCode)
      if (!d) { ElMessage.error(t('devForm.deviceNotFound')); return }
      await devicesApi.update(d.id, body)
    } else {
      await devicesApi.save(body)
    }
    emit('saved')
  } catch (e: any) {
    if (e instanceof ApiError) {
      ElMessage.error(t('devForm.saveFailedHttp', { status: e.status }) + (typeof e.body === 'string' ? e.body : e.message))
    } else {
      ElMessage.error(e?.message || t('devForm.saveFailed'))
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
  color: var(--meta); background: var(--surface-2); border-radius: var(--radius-sm);
}
</style>
