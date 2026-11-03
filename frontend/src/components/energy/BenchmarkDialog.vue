<template>
  <el-dialog :model-value="modelValue" :title="t('energyBm.title')" width="880px"
             :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)"
             @open="open">
    <!-- 列表 -->
    <template v-if="mode === 'list'">
      <div class="hint lead">{{ t('energyBm.lead') }}</div>

      <div v-if="!rows.length" class="empty">{{ t('energyBm.empty') }}</div>
      <table v-else class="btable">
        <thead>
          <tr>
            <th>{{ t('energyBm.colObject') }}</th>
            <th>{{ t('energyBm.colValues') }}</th>
            <th style="width:78px">{{ t('energyBm.unit') }}</th>
            <th style="width:70px">{{ t('energyBm.enabledShort') }}</th>
            <th style="width:110px"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="b in rows" :key="b.id" :class="{ off: !b.enabled }">
            <td>
              <b>{{ b.name }}</b>
              <div class="sub">{{ b.standardNo || '—' }} · {{ scopeText(b) }}</div>
            </td>
            <td class="vals">
              <span v-if="b.advancedValue != null" class="v adv">{{ t('energyBm.advanced') }} {{ b.advancedValue }}</span>
              <span v-if="b.accessValue != null" class="v acc">{{ t('energyBm.access') }} {{ b.accessValue }}</span>
              <span v-if="b.limitValue != null" class="v lim">{{ t('energyBm.limit') }} {{ b.limitValue }}</span>
            </td>
            <td class="sub">{{ b.unit }}</td>
            <td><el-switch :model-value="b.enabled" size="small" @change="toggle(b, $event)" /></td>
            <td class="ops">
              <el-button text size="small" @click="edit(b)">{{ t('energyBm.edit') }}</el-button>
              <el-button text size="small" type="danger" @click="del(b)">{{ t('energyBm.del') }}</el-button>
            </td>
          </tr>
        </tbody>
      </table>

      <el-alert v-if="warnings.length" type="warning" :closable="false" class="warn">
        <template #title>
          <div v-for="(w, i) in warnings" :key="i">{{ w }}</div>
        </template>
      </el-alert>

    </template>

    <!-- 编辑 -->
    <template v-else>
      <div class="hint lead">{{ t('energyBm.formLead') }}</div>

      <div class="grid2">
        <div class="field">
          <label>{{ t('energyBm.name') }}</label>
          <el-input v-model="form.name" :placeholder="t('energyBm.namePh')" maxlength="80" />
        </div>
        <div class="field">
          <label>{{ t('energyBm.standardNo') }}</label>
          <el-input v-model="form.standardNo" :placeholder="t('energyBm.standardNoPh')" maxlength="60" />
        </div>
      </div>

      <div class="grid3">
        <div class="field">
          <label>{{ t('energyBm.scope') }}</label>
          <el-select v-model="form.scopeType" @change="onScopeType">
            <el-option value="PLANT" :label="t('energyBm.scopePlant')" />
            <el-option value="WORKSHOP" :label="t('energyBm.scopeWorkshop')" />
            <el-option value="LINE" :label="t('energyBm.scopeLine')" />
            <el-option value="DEVICE" :label="t('energyBm.scopeDevice')" />
          </el-select>
        </div>
        <div class="field span2" v-if="form.scopeType !== 'PLANT'">
          <label>{{ t('energyBm.scopeTarget') }}</label>
          <el-select v-if="form.scopeType === 'WORKSHOP'" v-model="form.scopeValue" filterable allow-create
                     default-first-option :placeholder="t('energyBm.scopeWorkshopPh')">
            <el-option v-for="w in workshops" :key="w" :value="w" :label="w" />
          </el-select>
          <el-select v-else-if="form.scopeType === 'LINE'" v-model="form.scopeValue" filterable
                     :placeholder="t('energyBm.scopeLinePh')">
            <el-option v-for="l in lines" :key="l.id" :value="String(l.id)"
                       :label="l.name + (l.workshop ? ' · ' + l.workshop : '')" />
          </el-select>
          <el-select v-else v-model="form.scopeValue" filterable :placeholder="t('energyBm.scopeDevicePh')">
            <el-option v-for="d in devices" :key="d.code" :value="d.code"
                       :label="(d.name || d.code) + ' (' + d.code + ')'" />
          </el-select>
        </div>
      </div>

      <div class="grid4">
        <div class="field">
          <label>{{ t('energyBm.advanced') }}</label>
          <el-input v-model.number="form.advancedValue" type="number" step="0.001" :placeholder="t('energyBm.optional')" />
        </div>
        <div class="field">
          <label>{{ t('energyBm.access') }}</label>
          <el-input v-model.number="form.accessValue" type="number" step="0.001" :placeholder="t('energyBm.optional')" />
        </div>
        <div class="field">
          <label>{{ t('energyBm.limit') }}</label>
          <el-input v-model.number="form.limitValue" type="number" step="0.001" :placeholder="t('energyBm.optional')" />
        </div>
        <div class="field">
          <label>{{ t('energyBm.unit') }}</label>
          <el-input v-model="form.unit" :placeholder="t('energyBm.unitPh')" maxlength="16" />
        </div>
      </div>
      <div class="hint">{{ t('energyBm.valueHint') }}</div>

      <div class="field">
        <label>{{ t('energyBm.note') }}</label>
        <el-input v-model="form.note" type="textarea" :rows="2" :placeholder="t('energyBm.notePh')" maxlength="300" />
      </div>
      <div class="field inline">
        <el-switch v-model="form.enabled" />
        <span class="sub">{{ t('energyBm.enabled') }}</span>
      </div>

    </template>

    <template #footer>
      <template v-if="mode === 'list'">
        <el-button @click="emit('update:modelValue', false)">{{ t('energyBm.close') }}</el-button>
        <el-button type="primary" @click="create">{{ t('energyBm.add') }}</el-button>
      </template>
      <template v-else>
        <el-button @click="mode = 'list'">{{ t('energyBm.back') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('energyBm.save') }}</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { energyApi, type EnergyBenchmark } from '@/api/energy'
import { lineApi, type ProdLineRow } from '@/api/lines'
import { devicesApi } from '@/api/devices'

const { t } = useI18n()

defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'saved'): void }>()

const mode = ref<'list' | 'edit'>('list')
const rows = ref<EnergyBenchmark[]>([])
const warnings = ref<string[]>([])
const saving = ref(false)
const lines = ref<ProdLineRow[]>([])
const devices = ref<any[]>([])

const form = reactive<any>({
  id: '', name: '', standardNo: '', scopeType: 'PLANT', scopeValue: '',
  unit: 'kWh/件', advancedValue: '', accessValue: '', limitValue: '',
  enabled: true, note: ''
})

const workshops = computed(() => {
  const set = new Set<string>()
  lines.value.forEach((l) => { if (l.workshop && l.workshop.trim()) set.add(l.workshop.trim()) })
  if (lines.value.some((l) => !l.workshop || !l.workshop.trim())) set.add(t('energyBm.noWorkshop'))
  return Array.from(set)
})

function scopeText(b: EnergyBenchmark) {
  if (b.scopeType === 'PLANT') return t('energyBm.scopePlant')
  const label = b.scopeType === 'WORKSHOP' ? t('energyBm.scopeWorkshop')
    : b.scopeType === 'LINE' ? t('energyBm.scopeLine') : t('energyBm.scopeDevice')
  return label + ' · ' + (scopeName(b.scopeType, b.scopeValue) || b.scopeValue)
}

function scopeName(type: string, value: string) {
  if (type === 'LINE') {
    const l = lines.value.find((x) => String(x.id) === value)
    return l?.name || ''
  }
  if (type === 'DEVICE') {
    const d = devices.value.find((x) => x.code === value)
    return d?.name || ''
  }
  return value
}

async function open() {
  mode.value = 'list'
  warnings.value = []
  await Promise.all([loadList(), loadRefs()])
}

async function loadList() {
  try {
    const j = await energyApi.benchmarks()
    rows.value = j.rows || []
    warnings.value = j.warnings || []
  } catch (e: any) {
    ElMessage.error(t('energyBm.loadFail') + (e?.message || ''))
  }
}

async function loadRefs() {
  try {
    const [l, d] = await Promise.all([lineApi.list(), devicesApi.list()])
    lines.value = l.rows || []
    devices.value = d || []
  } catch { /* 引用数据加载失败不影响已有配置的展示 */ }
}

function create() {
  Object.assign(form, {
    id: '', name: '', standardNo: '', scopeType: 'PLANT', scopeValue: '',
    unit: 'kWh/件', advancedValue: '', accessValue: '', limitValue: '',
    enabled: true, note: ''
  })
  mode.value = 'edit'
}

function edit(b: EnergyBenchmark) {
  Object.assign(form, {
    id: b.id, name: b.name, standardNo: b.standardNo, scopeType: b.scopeType,
    scopeValue: b.scopeValue || '', unit: b.unit,
    advancedValue: b.advancedValue ?? '', accessValue: b.accessValue ?? '',
    limitValue: b.limitValue ?? '', enabled: b.enabled, note: b.note || ''
  })
  mode.value = 'edit'
}

function onScopeType() {
  form.scopeValue = ''
}

/** 空字符串转 null；数字原样返回 */
function toNum(v: any): number | null {
  if (v === '' || v === null || v === undefined) return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

async function save() {
  if (!form.name || !String(form.name).trim()) {
    ElMessage.warning(t('energyBm.nameRequired'))
    return
  }
  const advanced = toNum(form.advancedValue)
  const access = toNum(form.accessValue)
  const limit = toNum(form.limitValue)
  if (advanced == null && access == null && limit == null) {
    ElMessage.warning(t('energyBm.valueRequired'))
    return
  }
  if (form.scopeType !== 'PLANT' && !form.scopeValue) {
    ElMessage.warning(t('energyBm.scopeRequired'))
    return
  }
  saving.value = true
  try {
    const r = await energyApi.saveBenchmark({
      id: form.id || undefined,
      name: String(form.name).trim(),
      standardNo: String(form.standardNo || '').trim(),
      scopeType: form.scopeType,
      scopeValue: form.scopeType === 'PLANT' ? '' : form.scopeValue,
      unit: String(form.unit || '').trim() || 'kWh/件',
      advancedValue: advanced,
      accessValue: access,
      limitValue: limit,
      enabled: !!form.enabled,
      note: String(form.note || '').trim()
    })
    if (!r.ok) {
      ElMessage.error(r.message || t('energyBm.saveFail'))
      return
    }
    ElMessage.success(t('energyBm.saved'))
    if ((r.warnings || []).length) {
      warnings.value = r.warnings
      ElMessage.warning(r.warnings.join('；'))
    }
    mode.value = 'list'
    await loadList()
    emit('saved')
  } catch (e: any) {
    ElMessage.error(t('energyBm.saveFail') + (e?.message || ''))
  } finally {
    saving.value = false
  }
}

async function toggle(b: EnergyBenchmark, v: any) {
  try {
    const r = await energyApi.saveBenchmark({ ...b, enabled: !!v })
    if (!r.ok) {
      ElMessage.error(r.message || t('energyBm.saveFail'))
      return
    }
    await loadList()
    emit('saved')
  } catch (e: any) {
    ElMessage.error(t('energyBm.saveFail') + (e?.message || ''))
  }
}

async function del(b: EnergyBenchmark) {
  try {
    await ElMessageBox.confirm(t('energyBm.delConfirm', { name: b.name }), t('energyBm.del'),
      { type: 'warning', confirmButtonText: t('common.ok'), cancelButtonText: t('common.cancel') })
  } catch {
    return
  }
  try {
    const r = await energyApi.deleteBenchmark(b.id)
    if (!r.ok) {
      ElMessage.error(r.message || t('energyBm.delFail'))
      return
    }
    ElMessage.success(t('energyBm.deleted'))
    await loadList()
    emit('saved')
  } catch (e: any) {
    ElMessage.error(t('energyBm.delFail') + (e?.message || ''))
  }
}
</script>

<style scoped>
.hint { color: var(--muted); font-size: 12px; line-height: 1.7; }
.lead { margin-bottom: var(--space-3); }
.empty { padding: 26px 0; color: var(--muted); text-align: center; }
.btable { width: 100%; border-collapse: collapse; }
.btable th { text-align: left; font-weight: 500; color: var(--muted); font-size: 12px; padding: 6px 8px; border-bottom: 1px solid var(--border); }
.btable td { padding: 10px 8px; border-bottom: 1px solid var(--border); vertical-align: middle; }
.btable tr.off { opacity: .55; }
.sub { color: var(--muted); font-size: 12px; margin-top: 2px; }
.vals { display: flex; gap: 6px; flex-wrap: wrap; }
.v { font-size: 12px; padding: 1px 7px; border-radius: 10px; border: 1px solid var(--border); }
.v.adv { color: #2e8b57; border-color: #2e8b5755; }
.v.acc { color: #236de9; border-color: #236de955; }
.v.lim { color: #c77700; border-color: #c7770055; }
.ops { text-align: right; white-space: nowrap; }
.warn { margin-top: var(--space-3); }
.grid2, .grid3, .grid4 { display: grid; gap: var(--space-3); margin-bottom: var(--space-3); }
.grid2 { grid-template-columns: 1.4fr 1fr; }
.grid3 { grid-template-columns: 1fr 2fr; }
.grid4 { grid-template-columns: repeat(4, 1fr); }
.span2 { grid-column: span 1; }
.field { display: flex; flex-direction: column; gap: 6px; }
.field label { font-size: 12px; color: var(--muted); }
.field.inline { flex-direction: row; align-items: center; gap: 10px; margin-top: var(--space-3); }
</style>
