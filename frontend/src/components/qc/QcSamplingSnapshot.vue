<template>
  <!--
    抽样参数快照条。
    提交后这些值永久冻结，判定只读这里，永不回查方案表——
    所以它是"当时抽了多少"的凭据，不能被后来的方案表改动影响。
  -->
  <div class="snap">
    <div class="snap-cell">
      <span class="sc-lb">{{ t('qc.aql.boxLot') }}</span>
      <span class="sc-v num">{{ qty(lotQty, 0) }}</span>
    </div>
    <div class="snap-cell">
      <span class="sc-lb">{{ t('qc.aql.boxLetter') }}</span>
      <span class="sc-v num">{{ codeLetter || '--' }}</span>
    </div>
    <div class="snap-cell">
      <span class="sc-lb">{{ t('qc.aql.boxSample') }}</span>
      <span class="sc-v num">{{ sampleSize == null ? '--' : qty(sampleSize, 0) }}</span>
    </div>
    <div class="snap-cell" :class="{ danger: zeroTolerance }">
      <span class="sc-lb">{{ t('qc.aql.boxAc') }}</span>
      <span class="sc-v num">{{ acText }}</span>
    </div>
    <div class="snap-cell">
      <span class="sc-lb">{{ t('qc.insp.sampleSource') }}</span>
      <span class="sc-v">{{ t('qc.sampleSource.' + sampleSource) }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { qty } from '@/utils/format'

const props = defineProps<{
  lotQty: number
  codeLetter?: string | null
  sampleSize?: number | null
  ac?: number | null
  re?: number | null
  zeroTolerance?: boolean
  sampleSource: string
}>()

const { t } = useI18n()

/**
 * Ac 的显示。
 * ac = -1 是零容忍（CR 致命缺陷），显示「0 件（零容忍）」——
 * 归一化成 0 的话，判定岗看不出这批是"发现一件就整批退"。
 */
const acText = computed(() => {
  if (props.zeroTolerance || props.ac === -1) return t('qc.aql.acZeroTol')
  if (props.ac == null) return '--'
  return t('qc.aql.acValue', { ac: props.ac, re: props.re ?? props.ac + 1 })
})
</script>

<style scoped>
.snap {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
}
.snap-cell {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  padding: 7px 10px;
}
.snap-cell.danger {
  border-color: var(--alarm);
  background: var(--alarm-soft);
}
.sc-lb {
  display: block;
  font-size: var(--text-2xs);
  color: var(--muted);
}
.sc-v {
  display: block;
  margin-top: 2px;
  font-size: 15px;
  font-weight: 600;
  color: var(--fg);
}
.snap-cell.danger .sc-v { color: var(--alarm); }
</style>
