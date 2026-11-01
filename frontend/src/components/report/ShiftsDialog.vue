<template>
  <el-dialog :model-value="modelValue" :title="t('shift.title')" width="620px"
             :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)"
             @open="open">
    <div class="hint lead">
      {{ t('shift.intro1') }}
      {{ t('shift.intro2') }}
      <b>{{ t('shift.intro3') }}</b>
    </div>

    <div class="field">
      <label>{{ t('shift.planName') }}</label>
      <el-input v-model="planName" :placeholder="t('shift.planPh')" />
    </div>

    <div class="sect">{{ t('shift.sect') }}</div>
    <div class="thead"><div>{{ t('shift.colCode') }}</div><div>{{ t('shift.colName') }}</div><div>{{ t('shift.colStart') }}</div><div>{{ t('shift.colEnd') }}</div><div></div></div>
    <div v-for="(s, i) in rows" :key="i" class="trow">
      <el-input v-model="s.id" :placeholder="String.fromCharCode(65 + i)" />
      <el-input v-model="s.name" :placeholder="t('shift.shiftPh', { n: i + 1 })" />
      <el-input v-model="s.start" placeholder="08:00" />
      <el-input v-model="s.end" placeholder="16:00" />
      <el-button text type="danger" @click="rows.splice(i, 1)">×</el-button>
    </div>
    <el-button style="margin-top:var(--space-2)" @click="addRow">{{ t('shift.addRow') }}</el-button>

    <el-alert v-if="warn" :title="warn" type="warning" :closable="false" class="warn" />

    <div class="hint tail">
      {{ t('shift.warnOverlap') }}
      {{ t('shift.warnCode') }}
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t('shift.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ t('shift.save') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { reportsApi, type ShiftDef, type ShiftPlan } from '@/api/reports'

const { t } = useI18n()

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'saved'): void }>()

const planName = ref('')
const rows = reactive<ShiftDef[]>([])
const warn = ref('')
const saving = ref(false)

watch(() => props.modelValue, (v) => { if (v) open() })

function addRow(s?: ShiftDef) {
  rows.push({
    id: s?.id || '', name: s?.name || '', start: s?.start || '08:00', end: s?.end || '16:00'
  })
}

async function open() {
  warn.value = ''
  rows.splice(0)
  try {
    const j = await reportsApi.shifts()
    const plan = j.plan || { name: t('shift.defaultPlan'), shifts: [] }
    planName.value = plan.name || ''
    ;(plan.shifts || []).forEach((s) => addRow(s))
    if (!(plan.shifts || []).length) addRow()
  } catch {
    planName.value = ''
    addRow()
  }
}

async function save() {
  const shifts = rows.map((r, i) => ({
    id: (r.id || String.fromCharCode(65 + i)).trim(),
    name: r.name.trim() || t('shift.defaultShiftName', { n: i + 1 }),
    start: r.start.trim(),
    end: r.end.trim()
  }))
  if (!shifts.length) { warn.value = t('shift.atLeastOne'); return }
  saving.value = true
  try {
    const j = await reportsApi.saveShifts({
      name: planName.value.trim() || t('shift.defaultPlanName'), shifts
    })
    if (j && j.ok === false) { warn.value = j.message || t('shift.saveFailed'); return }
    emit('saved')
    emit('update:modelValue', false)
  } catch (e: any) {
    warn.value = t('shift.saveFailed') + '：' + (e?.message || '')
    ElMessage.error(warn.value)
  } finally { saving.value = false }
}
</script>

<style scoped>
.hint { font-size: var(--text-2xs); color: var(--meta); line-height: 1.7; }
.hint.lead { margin-bottom: var(--space-3); }
.hint.tail { margin-top: var(--space-3); }
.field { margin-bottom: var(--space-3); }
.field label { display: block; font-size: var(--text-xs); color: var(--muted); margin-bottom: 4px; }
.sect { font-size: var(--text-xs); color: var(--muted); letter-spacing: .1em; margin-bottom: var(--space-2); }
.thead, .trow { display: grid; grid-template-columns: 1.2fr 1fr .9fr .9fr 32px; gap: var(--space-2); }
.thead { font-size: var(--text-2xs); color: var(--meta); margin-bottom: 6px; }
.trow { margin-bottom: var(--space-2); align-items: center; }
.warn { margin-top: var(--space-3); border-radius: var(--radius-sm); }
</style>
