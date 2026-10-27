<template>
  <el-dialog
    v-model="visible" :title="editCode ? '编辑设备' : '新增设备'"
    width="680px" top="6vh" :close-on-click-modal="false"
  >
    <el-form label-width="130px" label-position="right">
      <div class="sec">基本信息</div>
      <div class="frow">
        <el-form-item label="设备编码"><el-input v-model="f.code" :disabled="!!editCode" placeholder="唯一，如 CNC-01" /></el-form-item>
        <el-form-item label="设备名称"><el-input v-model="f.name" placeholder="留空则用编码" /></el-form-item>
      </div>
      <div class="frow">
        <el-form-item label="位置 / 车间"><el-input v-model="f.location" /></el-form-item>
        <el-form-item label="采集周期（毫秒）"><el-input-number v-model="f.pollIntervalMs" :min="100" :step="100" /></el-form-item>
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
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/http'
import { devicesApi, genKey, PROTOCOLS, BRANDS, TPL } from '@/api/devices'
import { useDevicesStore } from '@/stores/devices'

const props = defineProps<{ editCode: string }>()
const emit = defineEmits<{ saved: [] }>()
const visible = defineModel<boolean>({ default: false })

const store = useDevicesStore()
const busy = ref(false)

const f = reactive({
  code: '', name: '', location: '', pollIntervalMs: 1000,
  protocol: 'modbus-tcp', host: '', port: 502,
  serialPort: 'COM3', baudRate: 9600, dataBits: 8, stopBits: 1, parity: 0,
  unitId: 1, unitId2: 1,
  apiKey: '', apiKeyEnabled: true, servoBrand: '',
  pointsText: JSON.stringify(TPL.machine, null, 2)
})

const isNet = computed(() => f.protocol === 'modbus-tcp' || f.protocol === 'modbus-rtu-tcp')
const isRtuTcp = computed(() => f.protocol === 'modbus-rtu-tcp')
const isRtu = computed(() => f.protocol === 'modbus-rtu')

function resetDefaults() {
  Object.assign(f, {
    code: '', name: '', location: '', pollIntervalMs: 1000,
    protocol: 'modbus-tcp', host: '', port: 502,
    serialPort: 'COM3', baudRate: 9600, dataBits: 8, stopBits: 1, parity: 0,
    unitId: 1, unitId2: 1, apiKey: '', apiKeyEnabled: true, servoBrand: '',
    pointsText: JSON.stringify(TPL.machine, null, 2)
  })
}

watch(visible, async (v) => {
  if (!v) return
  if (!props.editCode) {
    resetDefaults()
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
    points
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
</style>
