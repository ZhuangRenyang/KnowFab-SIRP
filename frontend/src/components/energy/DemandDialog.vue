<template>
  <el-dialog :model-value="modelValue" :title="t('energyDemand.title')" width="620px"
             :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)"
             @open="open">
    <div class="hint lead">
      {{ t('energyDemand.lead') }}<b>{{ t('energyDemand.leadA') }}</b>{{ t('energyDemand.leadM') }}<b>{{ t('energyDemand.leadB') }}</b>{{ t('energyDemand.leadN') }}
    </div>

    <div class="sect">{{ t('energyDemand.sect1') }}</div>
    <div class="row c2">
      <div class="field"><label>{{ t('energyDemand.contractKw') }}</label>
        <el-input v-model.number="fm.contractDemandKw" type="number" step="1" placeholder="500" /></div>
      <div class="field"><label>{{ t('energyDemand.demandPrice') }}</label>
        <el-input v-model.number="fm.demandPrice" type="number" step="0.01" placeholder="38" /></div>
      <div class="field"><label>{{ t('energyDemand.transformer') }}</label>
        <el-input v-model.number="fm.transformerKva" type="number" step="1" placeholder="800" /></div>
      <div class="field"><label>{{ t('energyDemand.capacityPrice') }}</label>
        <el-input v-model.number="fm.capacityPrice" type="number" step="0.01" placeholder="23" /></div>
    </div>

    <div class="sect">{{ t('energyDemand.sect2') }}</div>
    <div class="row c2">
      <div class="field"><label>{{ t('energyDemand.billingMode') }}</label>
        <el-select v-model="fm.billingMode">
          <el-option value="AUTO" :label="t('energyDemand.modeAuto')" />
          <el-option value="DEMAND" :label="t('energyDemand.modeDemand')" />
          <el-option value="CAPACITY" :label="t('energyDemand.modeCapacity')" />
        </el-select></div>
      <div class="field"><label>{{ t('energyDemand.warnPct') }}</label>
        <el-input v-model.number="warnPct" type="number" step="1" placeholder="90" /></div>
    </div>
    <el-checkbox v-model="fm.enabled">{{ t('energyDemand.enable') }}</el-checkbox>

    <el-alert v-if="warn" :title="warn" type="warning" :closable="false" class="warn" />

    <div class="hint tail">
      {{ t('energyDemand.tail') }}
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t('energyDemand.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ t('energyDemand.save') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { energyApi, type DemandConfig } from '@/api/energy'

const { t } = useI18n()

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'saved'): void }>()

const fm = reactive<DemandConfig>({
  contractDemandKw: 500, demandPrice: 38, transformerKva: 800, capacityPrice: 23,
  billingMode: 'AUTO', warnRatio: 0.9, enabled: true
})
const warnPct = computed({
  get: () => Math.round((fm.warnRatio ?? 0.9) * 100),
  set: (v: number) => { fm.warnRatio = (Number(v) || 0) / 100 }
})
const warn = ref('')
const saving = ref(false)

watch(() => props.modelValue, (v) => { if (v) open() })

async function open() {
  warn.value = ''
  try {
    const j = await energyApi.demandConfig()
    const c = j.config || {}
    Object.assign(fm, {
      contractDemandKw: c.contractDemandKw || 500,
      demandPrice: c.demandPrice || 38,
      transformerKva: c.transformerKva || 800,
      capacityPrice: c.capacityPrice || 23,
      billingMode: c.billingMode || 'AUTO',
      warnRatio: c.warnRatio == null ? 0.9 : c.warnRatio,
      enabled: c.enabled !== false
    })
    warn.value = (j.warnings || []).join(' · ')
  } catch (e: any) { console.error('demand config load failed', e) }
}

async function save() {
  saving.value = true
  try {
    const r = await energyApi.saveDemandConfig({ ...fm })
    if (r && r.ok === false) { warn.value = r.message || t('energyDemand.saveFail'); return }
    emit('saved')
    emit('update:modelValue', false)
  } catch (e: any) {
    warn.value = t('energyDemand.saveFailC') + (e?.message || '')
  } finally { saving.value = false }
}
</script>

<style scoped>
.hint { font-size: var(--text-2xs); color: var(--meta); line-height: 1.7; }
.hint.lead { margin-bottom: var(--space-3); }
.hint.tail { margin-top: var(--space-3); }
.sect { font-size: var(--text-xs); color: var(--muted); letter-spacing: .1em; margin: var(--space-3) 0 var(--space-2); }
.row.c2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); }
.field { margin-bottom: var(--space-2); min-width: 0; }
.field label { display: block; font-size: var(--text-xs); color: var(--muted); margin-bottom: 4px; }
.warn { margin-top: var(--space-3); border-radius: var(--radius-sm); }
</style>
