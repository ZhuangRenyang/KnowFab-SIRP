<template>
  <el-dialog :model-value="modelValue" :title="t('energyTariff.title')" width="720px"
             :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)"
             @open="open">
    <div class="hint lead">
      {{ t('energyTariff.lead') }}<b>{{ t('energyTariff.leadBold') }}</b>。
    </div>

    <div class="field">
      <label>{{ t('energyTariff.planName') }}</label>
      <el-input v-model="planName" :placeholder="t('energyTariff.planNamePh')" />
    </div>

    <div class="sect">{{ t('energyTariff.sect') }}</div>
    <div class="thead"><div>{{ t('energyTariff.colName') }}</div><div>{{ t('energyTariff.colType') }}</div><div>{{ t('energyTariff.colStart') }}</div><div>{{ t('energyTariff.colEnd') }}</div><div>{{ t('energyTariff.colPrice') }}</div><div></div></div>
    <div v-for="(s, i) in rows" :key="i" class="trow">
      <el-input v-model="s.name" :placeholder="t('energyTariff.namePh')" />
      <el-select v-model="s.type">
        <el-option v-for="[v] in SEG_TYPES" :key="v" :label="t('energyTariff.seg' + v)" :value="v" />
      </el-select>
      <el-input v-model="s.start" placeholder="08:00" />
      <el-input v-model="s.end" placeholder="11:00" />
      <el-input v-model.number="s.price" type="number" step="0.001" />
      <el-button text type="danger" @click="rows.splice(i, 1)">×</el-button>
    </div>
    <el-button style="margin-top:var(--space-2)" @click="addRow">{{ t('energyTariff.add') }}</el-button>

    <el-alert v-if="warn" type="warning" :closable="false" class="warn">
      <template #title><div v-html="warn"></div></template>
    </el-alert>

    <div class="hint tail">
      {{ t('energyTariff.tail') }}
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t('energyTariff.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ t('energyTariff.save') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { energyApi, SEG_TYPES, type TariffPlan } from '@/api/energy'

const { t } = useI18n()

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'saved'): void }>()

const planName = ref('')
const rows = reactive<TariffPlan['segments']>([])
const warn = ref('')
const saving = ref(false)

watch(() => props.modelValue, (v) => { if (v) open() })

function addRow(s?: TariffPlan['segments'][number]) {
  rows.push({
    name: s?.name || t('energyTariff.namePh'), type: s?.type || 'FLAT',
    start: s?.start || '08:00', end: s?.end || '11:00',
    price: s?.price == null ? 0.7 : s.price
  })
}

async function open() {
  warn.value = ''
  rows.splice(0)
  try {
    const j = await energyApi.tariff()
    const plan = j.plan || { name: '', segments: [] }
    planName.value = plan.name || ''
    ;(plan.segments || []).forEach((s) => addRow(s))
  } catch (e: any) { ElMessage.error(t('energyTariff.loadFail') + (e?.message || '')) }
}

async function save() {
  if (!rows.length) { ElMessage.warning(t('energyTariff.needOne')); return }
  saving.value = true
  try {
    const j = await energyApi.saveTariff({
      name: planName.value.trim(),
      segments: rows.map((r) => ({
        name: r.name.trim(), type: r.type,
        start: r.start.trim(), end: r.end.trim(),
        price: Number(r.price) || 0
      }))
    })
    if (j && j.ok === false) {
      ElMessage.error(j.message || t('energyTariff.saveFail'))
      return
    }
    if (j.warnings && j.warnings.length) {
      /* 配漏了时段不阻止保存，但必须让人看见，否则电费对不上会赖软件 */
      warn.value = t('energyTariff.savedWarn') + '<br>' + j.warnings.map((x: string) => '· ' + x).join('<br>')
      emit('saved')
      return
    }
    emit('saved')
    emit('update:modelValue', false)
  } catch (e: any) {
    ElMessage.error(e?.message || t('energyTariff.saveFail'))
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
.thead, .trow { display: grid; grid-template-columns: 1.1fr 1fr .8fr .8fr .9fr 32px; gap: var(--space-2); }
.thead { font-size: var(--text-2xs); color: var(--meta); margin-bottom: 6px; }
.trow { margin-bottom: var(--space-2); align-items: center; }
.warn { margin-top: var(--space-3); border-radius: var(--radius-sm); }
</style>
