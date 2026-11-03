<template>
  <div>
    <div class="dt-sec">
      <span class="dt-sec-title">{{ t('qc.board.severityTitle') }}</span>
      <span class="dt-sec-sub">{{ t('qc.board.severityHint') }}</span>
    </div>
    <div class="sev-list">
      <div v-for="s in mix" :key="s.severity" class="sev-item">
        <el-tag size="small" effect="light" :type="gradeType(s.severity)">
          {{ t('qc.severity.' + s.severity) }}
        </el-tag>
        <div class="sev-bar">
          <span class="sev-fill" :class="s.severity" :style="{ width: pct(s.qty) }" />
        </div>
        <span class="sev-val num">{{ qty(s.qty, 0) }}</span>
        <span class="sev-pct num muted">{{ pct(s.qty) }}</span>
      </div>
      <p v-if="!mix.length" class="sev-empty">{{ t('qc.board.severityEmpty') }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { gradeType, type QcSeverity } from '@/api/qc'
import { qty } from '@/utils/format'

const props = defineProps<{
  mix: { severity: QcSeverity; qty: number }[]
}>()

const { t } = useI18n()

const total = computed(() => props.mix.reduce((a, s) => a + Number(s.qty || 0), 0))

function pct(q: number): string {
  if (!total.value) return '0%'
  return `${((Number(q || 0) / total.value) * 100).toFixed(1)}%`
}
</script>

<style scoped>
.sev-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sev-item {
  display: flex;
  align-items: center;
  gap: 10px;
}
.sev-bar {
  flex: 1;
  height: 8px;
  border-radius: 4px;
  background: var(--surface-3);
  overflow: hidden;
}
.sev-fill {
  display: block;
  height: 100%;
  border-radius: 4px;
  transition: width var(--motion-base) var(--ease-standard);
}
.sev-fill.CR { background: var(--alarm); }
.sev-fill.MA { background: var(--idle); }
.sev-fill.MI { background: var(--stop); }
.sev-val {
  min-width: 52px;
  text-align: right;
  color: var(--fg);
  font-weight: 600;
}
.sev-pct {
  min-width: 52px;
  text-align: right;
}
.sev-empty {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--muted);
}
</style>
