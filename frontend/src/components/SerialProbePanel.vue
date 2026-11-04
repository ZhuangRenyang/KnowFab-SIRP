<template>
  <div class="sp">
    <div class="sp-head">
      <h4>{{ t('disc.serialTitle') }}</h4>
      <span class="hint">{{ t('disc.serialLead') }}</span>
    </div>

    <!-- 串口选择 -->
    <div class="frow">
      <label>{{ t('disc.serialPort') }}</label>
      <el-select v-model="f.serialPort" style="width:220px" :placeholder="t('disc.serialPortPh')">
        <el-option v-for="p in ports" :key="p.port" :value="p.port"
                   :label="p.description ? `${p.port} · ${p.description}` : p.port" />
      </el-select>
      <el-button size="small" @click="loadPorts">{{ t('disc.serialRefresh') }}</el-button>
      <span v-if="!ports.length && loaded" class="hint">{{ t('disc.serialNoPort') }}</span>
    </div>

    <!-- 串口参数 -->
    <div class="frow">
      <label>{{ t('disc.baud') }}</label>
      <el-select v-model.number="f.baudRate" style="width:110px">
        <el-option v-for="b in BAUDS" :key="b" :value="b" :label="b" />
      </el-select>

      <label>{{ t('disc.dataBits') }}</label>
      <el-select v-model.number="f.dataBits" style="width:84px">
        <el-option :value="7" label="7" /><el-option :value="8" label="8" />
      </el-select>

      <label>{{ t('disc.stopBits') }}</label>
      <el-select v-model.number="f.stopBits" style="width:84px">
        <el-option :value="1" label="1" /><el-option :value="2" label="2" />
      </el-select>

      <label>{{ t('disc.parity') }}</label>
      <el-select v-model.number="f.parity" style="width:104px">
        <el-option :value="0" :label="t('devForm.parityNone')" />
        <el-option :value="1" :label="t('devForm.parityOdd')" />
        <el-option :value="2" :label="t('devForm.parityEven')" />
      </el-select>
    </div>

    <!-- 对拍范围 -->
    <div class="frow">
      <label>{{ t('disc.unitId') }}</label>
      <el-input-number v-model.number="f.unitId" :min="1" :max="247" size="small" style="width:100px" />

      <label>{{ t('disc.fc') }}</label>
      <el-select v-model.number="f.functionCode" size="small" style="width:132px">
        <el-option :value="3" :label="t('disc.fc3')" />
        <el-option :value="4" :label="t('disc.fc4')" />
      </el-select>

      <label>{{ t('disc.fromAddr') }}</label>
      <el-input-number v-model.number="f.startAddr" :min="0" :max="65535" size="small" style="width:112px" />

      <label>{{ t('disc.probeCount') }}</label>
      <el-input-number v-model.number="f.count" :min="1" :max="256" size="small" style="width:112px" />

      <el-button type="primary" size="small" :loading="busy" @click="run">{{ t('disc.serialProbeBtn') }}</el-button>
    </div>

    <div class="hint-line">{{ t('disc.serialNote') }}</div>

    <!-- 结果 -->
    <div v-if="res" class="res">
      <div class="res-head">
        <span v-if="res.ok" class="ok">{{ t('disc.serialHit', { hit: res.hit ?? 0, miss: res.miss ?? 0 }) }}</span>
        <span v-else class="bad">{{ res.message || t('disc.serialFailed') }}</span>
        <span v-if="res.elapsedMs != null" class="hint">{{ res.elapsedMs }} ms</span>
        <el-button v-if="res.ok && res.points?.length" size="small" @click="copyPoints">{{ t('disc.serialCopy') }}</el-button>
      </div>
      <div v-if="res.ok && res.message" class="hint-line">{{ res.message }}</div>
      <table v-if="res.found?.length" class="tbl">
        <thead>
          <tr><th>{{ t('disc.addr') }}</th><th>{{ t('disc.value') }}</th></tr>
        </thead>
        <tbody>
          <tr v-for="h in res.found" :key="h.address">
            <td class="num">{{ h.address }}</td>
            <td class="num">{{ h.value }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { discoveryApi, type SerialPortInfo, type SerialProbeResult } from '@/api/discovery'

const { t } = useI18n()

const BAUDS = [1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200]

const ports = ref<SerialPortInfo[]>([])
const loaded = ref(false)
const busy = ref(false)
const res = ref<SerialProbeResult | null>(null)

const f = reactive({
  serialPort: '',
  baudRate: 9600,
  dataBits: 8,
  stopBits: 1,
  parity: 0,
  unitId: 1,
  startAddr: 0,
  count: 32,
  functionCode: 3
})

async function loadPorts() {
  loaded.value = true
  try {
    const list = await discoveryApi.serialPorts()
    ports.value = Array.isArray(list) ? list : []
    if (!f.serialPort && ports.value.length) {
      f.serialPort = ports.value[0].port
    }
  } catch {
    ports.value = []
  }
}

onMounted(loadPorts)

async function run() {
  if (!f.serialPort) {
    ElMessage.warning(t('disc.serialPickFirst'))
    return
  }
  busy.value = true
  res.value = null
  try {
    res.value = await discoveryApi.serialProbe({ ...f })
  } catch (e) {
    res.value = { ok: false, message: (e as Error)?.message || t('disc.serialFailed') }
  } finally {
    busy.value = false
  }
}

/** 把点表复制到剪贴板，用户直接粘到设备编辑的点表里 */
async function copyPoints() {
  const json = JSON.stringify(res.value?.points ?? [], null, 2)
  try {
    await navigator.clipboard.writeText(json)
    ElMessage.success(t('disc.serialCopied'))
  } catch {
    ElMessage.warning(t('disc.serialCopyFailed'))
  }
}
</script>

<style scoped>
.sp { padding: var(--space-3); border: 1px solid var(--line); border-radius: var(--radius-sm); }
.sp-head { margin-bottom: var(--space-2); }
.sp-head h4 { margin: 0 0 2px; font-size: var(--text-sm); color: var(--fg); font-weight: 600; }
.hint { font-size: var(--text-2xs); color: var(--muted); }
.hint-line { font-size: var(--text-2xs); color: var(--muted); margin: var(--space-2) 0; line-height: 1.7; }
.frow { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: var(--space-2); }
.frow label { font-size: var(--text-xs); color: var(--muted); min-width: 52px; }
.res { margin-top: var(--space-3); padding-top: var(--space-3); border-top: 1px dashed var(--line); }
.res-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: var(--space-2); }
.ok { color: var(--run); font-size: var(--text-sm); }
.bad { color: var(--alarm); font-size: var(--text-sm); }
.tbl { width: 100%; border-collapse: collapse; font-size: var(--text-xs); }
.tbl th { text-align: left; color: var(--muted); font-weight: normal; padding: 3px 6px; border-bottom: 1px solid var(--line); }
.tbl td { padding: 3px 6px; border-bottom: 1px dashed var(--line); color: var(--fg); }
.tbl .num { font-variant-numeric: tabular-nums; }
</style>
