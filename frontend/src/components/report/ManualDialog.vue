<template>
  <el-dialog :model-value="modelValue" :title="t('manual.title')" width="920px"
             :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)"
             @open="open">
    <template v-if="mode === 'list'">
      <div class="hint lead">{{ t('manual.lead') }}</div>

      <div class="bar">
        <span class="lbl">{{ t('manual.monthLabel') }}</span>
        <el-date-picker v-model="month" type="month" value-format="YYYY-MM" style="width:130px"
                        @change="loadList" />
        <span class="spacer"></span>
        <span class="sub">{{ t('manual.count', { n: rows.length }) }}</span>
      </div>

      <div v-if="!rows.length" class="empty">{{ t('manual.empty') }}</div>
      <table v-else class="mtable">
        <thead>
          <tr>
            <th style="width:96px">{{ t('manual.colDate') }}</th>
            <th>{{ t('manual.colDevice') }}</th>
            <th class="r" style="width:92px">{{ t('manual.colKwh') }}</th>
            <th class="r" style="width:84px">{{ t('manual.colCost') }}</th>
            <th class="r" style="width:82px">{{ t('manual.colOutput') }}</th>
            <th class="r" style="width:78px">{{ t('manual.colDefect') }}</th>
            <th>{{ t('manual.colReason') }}</th>
            <th style="width:82px">{{ t('manual.colOperator') }}</th>
            <th style="width:74px">{{ t('manual.colStatus') }}</th>
            <th style="width:60px"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.id" :class="{ off: r.revoked }">
            <td>{{ r.day }}</td>
            <td>
              {{ r.deviceName || r.deviceCode }}
              <div class="sub">{{ r.deviceCode }}</div>
            </td>
            <td class="r num">{{ r.kwh ? num(r.kwh, 2) : '—' }}</td>
            <td class="r num">{{ r.kwhCost ? '¥' + num(r.kwhCost, 2) : '—' }}</td>
            <td class="r num">{{ r.output ? num(r.output, 0) : '—' }}</td>
            <td class="r num">{{ r.defect === null || r.defect === undefined ? '—' : num(r.defect, 0) }}</td>
            <td class="reason">{{ r.reason || '—' }}</td>
            <td>{{ r.operator || '—' }}</td>
            <td>
              <el-tag size="small" :type="r.revoked ? 'info' : 'success'">
                {{ r.revoked ? t('manual.revoked') : t('manual.active') }}
              </el-tag>
            </td>
            <td>
              <el-button v-if="!r.revoked" text size="small" type="danger" @click="revoke(r)">
                {{ t('manual.revoke') }}
              </el-button>
            </td>
          </tr>
        </tbody>
      </table>
    </template>

    <template v-else>
      <div class="hint lead">{{ t('manual.formLead') }}</div>

      <div class="field">
        <label>{{ t('manual.device') }}</label>
        <el-select v-model="form.deviceCode" filterable :placeholder="t('manual.devicePh')" style="width:100%">
          <el-option v-for="d in devices" :key="d.code" :value="d.code"
                     :label="(d.name || d.code) + ' (' + d.code + ')'" />
        </el-select>
      </div>

      <div class="grid2">
        <div class="field">
          <label>{{ t('manual.range') }}</label>
          <el-date-picker v-model="form.range" type="daterange" value-format="YYYY-MM-DD"
                          :start-placeholder="t('manual.rangeStart')" :end-placeholder="t('manual.rangeEnd')"
                          style="width:100%" />
        </div>
        <div class="field">
          <label>{{ t('manual.kwh') }}</label>
          <el-input v-model.number="form.kwh" type="number" step="0.01" :placeholder="t('manual.optional')" />
        </div>
        <div class="field">
          <label>{{ t('manual.output') }}</label>
          <el-input v-model.number="form.output" type="number" step="1" :placeholder="t('manual.optional')" />
        </div>
        <div class="field">
          <label>{{ t('manual.defect') }}</label>
          <el-input v-model.number="form.defect" type="number" step="1" :placeholder="t('manual.defectPh')" />
          <div class="hint">{{ t('manual.defectHint') }}</div>
        </div>
      </div>

      <div class="field">
        <label>{{ t('manual.reason') }}</label>
        <el-input v-model="form.reason" type="textarea" :rows="2" maxlength="200"
                  :placeholder="t('manual.reasonPh')" />
      </div>

      <div class="hint">{{ t('manual.valueHint') }}</div>
    </template>

    <template #footer>
      <template v-if="mode === 'list'">
        <el-button @click="emit('update:modelValue', false)">{{ t('manual.close') }}</el-button>
        <el-button type="primary" @click="create">{{ t('manual.add') }}</el-button>
      </template>
      <template v-else>
        <el-button @click="mode = 'list'">{{ t('manual.back') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('manual.save') }}</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { manualApi, type ManualEntryRow } from '@/api/manual'
import { devicesApi } from '@/api/devices'

const { t } = useI18n()

defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'changed'): void }>()

const mode = ref<'list' | 'edit'>('list')
const month = ref(todayMonth())
const rows = ref<ManualEntryRow[]>([])
const devices = ref<any[]>([])
const saving = ref(false)

const form = reactive<any>({
  deviceCode: '', range: [] as string[], kwh: '', output: '', defect: '', reason: ''
})

function pad(n: number) {
  return String(n).padStart(2, '0')
}

function todayMonth() {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}`
}

function monthEnd(m: string) {
  const [y, mo] = m.split('-').map(Number)
  const d = new Date(y, mo, 0)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

async function open() {
  mode.value = 'list'
  await Promise.all([loadList(), loadDevices()])
}

async function loadDevices() {
  try {
    devices.value = await devicesApi.list()
  } catch { /* 设备列表失败不阻塞查看已有补录 */ }
}

async function loadList() {
  try {
    const j = await manualApi.list(`${month.value}-01`, monthEnd(month.value))
    rows.value = j.rows || []
  } catch (e: any) {
    ElMessage.error(t('manual.loadFail') + (e?.message || ''))
  }
}

function create() {
  Object.assign(form, { deviceCode: '', range: [], kwh: '', output: '', defect: '', reason: '' })
  mode.value = 'edit'
}

function toNum(v: any): number | undefined {
  if (v === '' || v === null || v === undefined) return undefined
  const n = Number(v)
  return Number.isFinite(n) ? n : undefined
}

async function save() {
  if (!form.deviceCode) {
    ElMessage.warning(t('manual.deviceRequired'))
    return
  }
  if (!Array.isArray(form.range) || form.range.length !== 2) {
    ElMessage.warning(t('manual.rangeRequired'))
    return
  }
  const kwh = toNum(form.kwh)
  const output = toNum(form.output)
  if ((kwh == null || kwh <= 0) && (output == null || output <= 0)) {
    ElMessage.warning(t('manual.valueRequired'))
    return
  }
  if (!form.reason || !String(form.reason).trim()) {
    ElMessage.warning(t('manual.reasonRequired'))
    return
  }
  saving.value = true
  try {
    const r = await manualApi.create({
      deviceCode: form.deviceCode,
      fromDay: form.range[0],
      toDay: form.range[1],
      kwh: kwh ?? 0,
      output: output ?? 0,
      // DH-15：不良数留空 = 没录（数据缺失，良率算不出来）；
      // 填 0 = 确实是零不良。这两者要分得开，后端才能判
      defect: form.defect === '' || form.defect === null || form.defect === undefined
        ? null : Number(form.defect),
      reason: String(form.reason).trim()
    })
    if (!r.ok) {
      ElMessage.error(r.message || t('manual.saveFail'))
      return
    }
    ElMessage.success(t('manual.saved', { days: r.days || 1 }))
    mode.value = 'list'
    // 补到别的月份时自动跳过去，免得客户以为没存上
    const m = String(form.range[0]).slice(0, 7)
    if (m !== month.value) month.value = m
    await loadList()
    emit('changed')
  } catch (e: any) {
    ElMessage.error(t('manual.saveFail') + (e?.message || ''))
  } finally {
    saving.value = false
  }
}

async function revoke(r: ManualEntryRow) {
  try {
    await ElMessageBox.confirm(t('manual.revokeConfirm', { name: r.deviceName || r.deviceCode, day: r.day }),
      t('manual.revoke'), { type: 'warning', confirmButtonText: t('common.ok'), cancelButtonText: t('common.cancel') })
  } catch {
    return
  }
  try {
    const res = await manualApi.revoke(r.id)
    if (!res.ok) {
      ElMessage.error(res.message || t('manual.revokeFail'))
      return
    }
    ElMessage.success(t('manual.revokedOk'))
    await loadList()
    emit('changed')
  } catch (e: any) {
    ElMessage.error(t('manual.revokeFail') + (e?.message || ''))
  }
}

function num(v: number | null | undefined, digits = 1) {
  return Number(v || 0).toFixed(digits)
}
</script>

<style scoped>
.hint { color: var(--muted); font-size: var(--fs-2); line-height: 1.7; }
.lead { margin-bottom: var(--space-3); }
.bar { display: flex; align-items: center; gap: var(--space-2); margin-bottom: var(--space-3); }
.lbl { font-size: var(--fs-2); color: var(--muted); }
.spacer { flex: 1; }
.sub { color: var(--muted); font-size: var(--fs-2); }
.empty { padding: 26px 0; color: var(--muted); text-align: center; }
.mtable { width: 100%; border-collapse: collapse; table-layout: fixed; }
.mtable th { text-align: left; font-weight: 500; color: var(--muted); font-size: var(--fs-2); padding: 6px 8px; border-bottom: 1px solid var(--border); }
.mtable td { padding: 8px; border-bottom: 1px solid var(--border); vertical-align: top; font-size: var(--fs-2); }
.mtable tr.off { opacity: .5; }
.mtable .r { text-align: right; }
.reason { word-break: break-all; }
.field { display: flex; flex-direction: column; gap: 6px; margin-bottom: var(--space-3); }
.field label { font-size: var(--fs-2); color: var(--muted); }
.grid2 { display: grid; grid-template-columns: 1.6fr 1fr 1fr; gap: var(--space-3); }
</style>
