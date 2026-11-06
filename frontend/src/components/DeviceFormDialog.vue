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
        <el-form-item :label="t('devForm.port')">
          <el-input-number v-model="f.port" :min="1" :max="65535" :placeholder="t('devForm.portPh', { port: protoDefaultPort })" />
        </el-form-item>
      </div>
      <!-- OPC UA 的 host 语义与 Modbus 不同：只填 IP/主机名，协议前缀与端口由系统拼。
           单独占一行（不塞进 el-form-item），否则提示会把 host/port 两个输入框顶得高低不齐。 -->
      <div v-if="isOpcUa" class="hint" style="margin:-6px 0 8px 130px">
        {{ t('devForm.opcuaHostHint') }}
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

      <div class="sec">{{ t('devForm.secMeter') }}{{ t('devForm.secMeterHint') }}</div>
      <el-form-item label-width="90px">
        <el-switch v-model="f.isMeter" :active-text="t('devForm.switchOn')" :inactive-text="t('devForm.switchOff')" />
      </el-form-item>
      <div v-if="f.isMeter" class="frow">
        <el-form-item :label="t('devForm.meterHintLabel')">
          <div class="hint-line">{{ t('devForm.meterHint') }}</div>
        </el-form-item>
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
import { devicesApi, genKey, PROTOCOLS, BRANDS, TPL, TPL_PROTOCOL } from '@/api/devices'
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
  protocol: 'modbus-tcp', host: '', port: null as number | null,
  serialPort: 'COM3', baudRate: 9600, dataBits: 8, stopBits: 1, parity: 0,
  unitId: 1, unitId2: 1, timeoutMs: 800,
  apiKey: '', apiKeyEnabled: true, servoBrand: '',
  pointsText: JSON.stringify(TPL.machine, null, 2),
  prodSkuId: undefined as number | undefined, prodWarehouseId: undefined as number | undefined,
  prodResetHour: 0, prodAutoStockin: false,
  dailyTarget: undefined as number | undefined,
  idealCycleSec: undefined as number | undefined
,  /** 是否总表（进线计量点）：需量与基本电费的全厂口径只累加总表 */
  isMeter: false
})

/* 各协议的默认端口：切协议时预填 + 端口框留空时的提示文案都用它。
   注意：这只是「界面上给个建议值」，不是数据层的兜底：
   用户把端口框清空，提交的就是 null（= 没填），由后端各协议驱动自己回落默认值
   （后端 DriverContext.port=0 即「未指定」，Modbus 502 / OPC UA 4840）。
   真正的默认值只存在于后端驱动里，前端这份只是让用户少查一次手册。 */
const PROTO_DEFAULT_PORT: Record<string, number> = {
  'modbus-tcp': 502,
  'modbus-rtu-tcp': 502,
  opcua: 4840
}

/* 切协议时给行业默认端口：TCP 族 502；RTU 走串口不动端口（模板里 @change 引用，
   此函数 2026-11-01 补上——第一版基座就只有模板引用没有实现）。
   换协议就换端口：旧端口属于旧协议，留着会连错服务。
   （用户自己清空端口框的意图在提交时才生效——那时传的是 null。） */
function onProto() {
  const p = PROTO_DEFAULT_PORT[f.protocol]
  if (p) f.port = p
}

/* 端口框留空时的提示：告诉用户会连哪个端口（按当前协议）。
   口径与后端 ProtocolDefaults.defaultPort 一致：不认识 / null 的协议按 Modbus 502 算。 */
const protoDefaultPort = computed(() => PROTO_DEFAULT_PORT[f.protocol] ?? 502)

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
      props.editCode ? t('common.saveTplFor', { code: props.editCode }) : t('common.saveTplCurrent'),
      t('common.saveTplTitle'),
      { inputPlaceholder: t('common.tplNamePh'), inputValidator: (v) => (!!v && !!v.trim()) || t('common.tplNameRequired') }
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
    if (!r.ok) { ElMessage.warning(r.message || t('common.saveFailed')); return }
    ElMessage.success(r.message || t('common.tplSaved'))
    await loadCustomTpls()
  } catch (e: any) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.warning(e?.message || t('common.saveFailed'))
  }
}

async function delTpl() {
  const id = Number(tplId.value.slice(1))
  if (!id) return
  try {
    await ElMessageBox.confirm(t('common.delTplConfirm'), t('devForm.delTpl'), { type: 'warning' })
  } catch { return }
  const r = await deviceTplApi.remove(id).catch(() => null)
  if (!r?.ok) { ElMessage.warning(r?.message || t('common.delFailed')); return }
  ElMessage.success(t('common.tplDeleted'))
  tplId.value = ''
  tplNote.value = ''
  await loadCustomTpls()
}

/* 端口「未填」在表单里的表示是 null（空框）。
   不能用 0 当这个哨兵：el-input-number 挂了 :min="1"，verifyValue 会把 0
   夹成 min 并**反向 emit 回父组件**（实测 element-plus 2.14.8），
   于是用户会看到端口框里凭空冒出「1」。0 只用于「提交给后端」那一层。
   后端那边 0 与 null 等价（apply 判 `port == null || port <= 0`）。 */
function normPort(v: unknown): number | null {
  const n = Number(v)
  return Number.isFinite(n) && n > 0 ? n : null
}

function applyTpl(key: string) {
  // 我的模板：key = 'c<id>'
  if (key.startsWith('c')) {
    const ct = customTpls.value.find((x) => 'c' + x.id === key)
    if (!ct) { tplNote.value = ''; return }
    f.protocol = ct.protocol
    f.port = normPort(ct.port)
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
  /* 内置模板没写端口 ⇒ 用该协议的默认端口（与后端 ProtocolDefaults 同口径）。
     注意 ?? 的短路顺序：tpl.port 有值就用它（模板是显式指定的，不替它决定），
     为 null/undefined 才看协议默认，协议也不认识才回 null（留空）。
     normPort 再兜一层：把 0 归一成 null —— 0 在表单里会被 el-input-number 夹成 1。 */
  f.port = normPort(tpl.port ?? PROTO_DEFAULT_PORT[tpl.protocol] ?? null)
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
    protocol: 'modbus-tcp', host: '', port: null,
    serialPort: 'COM3', baudRate: 9600, dataBits: 8, stopBits: 1, parity: 0,
    unitId: 1, unitId2: 1, timeoutMs: 800, apiKey: '', apiKeyEnabled: true, servoBrand: '',
    pointsText: JSON.stringify(TPL.machine, null, 2),
    prodSkuId: undefined, prodWarehouseId: undefined, prodResetHour: 0, prodAutoStockin: false,
    dailyTarget: undefined, idealCycleSec: undefined, isMeter: false
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
    protocol: c.protocol || 'modbus-tcp', host: c.host || '', port: normPort(c.port),
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
,    isMeter: !!c.isMeter
  })
  try {
    f.pointsText = JSON.stringify(JSON.parse(c.pointsJson || '[]'), null, 2)
  } catch {
    f.pointsText = c.pointsJson || '[]'
  }
})

function useTpl(k: string) {
  f.pointsText = JSON.stringify(TPL[k], null, 2)
  /* 内置「OPC UA 示例」给的是 OPC UA 的点表，协议与端口要跟着换，
     否则会出现「点表是 OPC UA 的、协议还是 modbus-tcp、端口还是 502」的错配配置。
     其余内置示例都是 Modbus 的（用默认的 modbus-tcp），协议不动。 */
  const proto = TPL_PROTOCOL[k]
  if (proto) {
    f.protocol = proto
    f.port = PROTO_DEFAULT_PORT[proto] ?? null
  }
}

async function save() {
  if (busy.value) return
  const code = f.code.trim()
  if (!code) { ElMessage.warning(t('common.codeRequired')); return }
  let points: any
  try {
    points = JSON.parse(f.pointsText || '[]')
    if (!Array.isArray(points) || !points.length) { ElMessage.warning(t('common.pointsRequired')); return }
  } catch (e: any) {
    ElMessage.warning(t('common.pointsJsonError') + e.message)
    return
  }
  const body = {
    code,
    name: f.name.trim() || code,
    location: f.location.trim(),
    protocol: f.protocol,
    host: f.host.trim(),
    /* 端口没填就传 0（= 未指定），**绝不能兜成 502**：
       502 是 Modbus 的端口，兜成 502 的话，新建 OPC UA 设备会拿着 502 去连，必然连不上。
       后端 apply() 判 `port == null || port <= 0` ⇒ 0 与 null 等价，都按协议给默认端口
       （Modbus 502 / OPC UA 4840，口径在后端 ProtocolDefaults 一处）。 */
    port: Number(f.port) || 0,
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
    isMeter: f.isMeter,
    dailyTarget: f.dailyTarget ? Number(f.dailyTarget) : null,
    // DH-15 理想节拍：0 和不填一样（没配就是没配，性能率算不出来）
    idealCycleSec: f.idealCycleSec ? Number(f.idealCycleSec) : null
  }
  busy.value = true
  try {
    if (props.editCode) {
      const d = store.list.find((x: any) => x.code === props.editCode)
      if (!d) { ElMessage.error(t('common.deviceNotFound')); return }
      await devicesApi.update(d.id, body)
    } else {
      await devicesApi.save(body)
    }
    emit('saved')
  } catch (e: any) {
    if (e instanceof ApiError) {
      ElMessage.error(t('common.saveFailedHttp', { status: e.status }) + (typeof e.body === 'string' ? e.body : e.message))
    } else {
      ElMessage.error(e?.message || t('common.saveFailed'))
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
