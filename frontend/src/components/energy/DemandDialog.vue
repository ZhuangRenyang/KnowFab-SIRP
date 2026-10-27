<template>
  <el-dialog :model-value="modelValue" title="基本电费 / 需量设置" width="620px"
             :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)"
             @open="open">
    <div class="hint lead">
      大工业电费 = 电度电费（用了多少度）+ 基本电费（占了多少容量或需量）+ 力调电费。
      基本电费每月固定交，两种算法：<b>按需量</b>按当月最大 15 分钟平均功率算，
      <b>按容量</b>按变压器容量算。数字都在供电合同和电费单上。
    </div>

    <div class="sect">计费参数</div>
    <div class="row c2">
      <div class="field"><label>合同（申报）需量 kW</label>
        <el-input v-model.number="fm.contractDemandKw" type="number" step="1" placeholder="500" /></div>
      <div class="field"><label>需量电价 元/kW·月</label>
        <el-input v-model.number="fm.demandPrice" type="number" step="0.01" placeholder="38" /></div>
      <div class="field"><label>变压器容量 kVA</label>
        <el-input v-model.number="fm.transformerKva" type="number" step="1" placeholder="800" /></div>
      <div class="field"><label>容量电价 元/kVA·月</label>
        <el-input v-model.number="fm.capacityPrice" type="number" step="0.01" placeholder="23" /></div>
    </div>

    <div class="sect">计费方式与预警</div>
    <div class="row c2">
      <div class="field"><label>计费方式</label>
        <el-select v-model="fm.billingMode">
          <el-option value="AUTO" label="自动（两种都算，取便宜的）" />
          <el-option value="DEMAND" label="按需量" />
          <el-option value="CAPACITY" label="按容量" />
        </el-select></div>
      <div class="field"><label>预警比例 %</label>
        <el-input v-model.number="warnPct" type="number" step="1" placeholder="90" /></div>
    </div>
    <el-checkbox v-model="fm.enabled">启用需量预警（到比例就发微信，回落自动解除）</el-checkbox>

    <el-alert v-if="warn" :title="warn" type="warning" :closable="false" class="warn" />

    <div class="hint tail">
      需量取的是<b>当月最大值</b>，冲上去就改不回来了 —— 不像电度电费后面省点还能补。
      所以预警比例建议留 10% 余量。
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存并生效</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { energyApi, type DemandConfig } from '@/api/energy'

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
    warn.value = (j.warnings || []).join('；')
  } catch (e: any) { console.error('需量配置加载失败', e) }
}

async function save() {
  saving.value = true
  try {
    const r = await energyApi.saveDemandConfig({ ...fm })
    if (r && r.ok === false) { warn.value = r.message || '保存失败'; return }
    emit('saved')
    emit('update:modelValue', false)
  } catch (e: any) {
    warn.value = '保存失败：' + (e?.message || '')
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
