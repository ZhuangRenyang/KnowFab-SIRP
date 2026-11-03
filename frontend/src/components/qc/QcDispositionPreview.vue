<template>
  <!--
    处置后果预告。
    处置动作 × 库存状态 = 六个分支。这一步会动库存，说不清楚用户会以为
    「报废 = 库存一定少」——但没入账的不良品报废是不动库存的。
  -->
  <div class="dp-preview" :class="preview.tone">
    <div class="dpv-head">
      <el-icon><component :is="preview.icon" /></el-icon>
      <span class="dpv-title">{{ t(preview.titleKey) }}</span>
    </div>
    <p class="dpv-text">{{ t(preview.textKey) }}</p>
    <ul class="dpv-list">
      <li v-for="(l, i) in preview.lines" :key="i">{{ l }}</li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Delete, Refresh, Tools } from '@element-plus/icons-vue'
import type { QcDisposition } from '@/api/qc'

const props = defineProps<{
  action: QcDisposition
  /** 货是否已入账。false = 不良从未入账，报废不动库存 */
  inStock: boolean
}>()

const { t } = useI18n()

/**
 * 六分支映射：
 * 报废 + 已入账 → 出库单，库存真的少
 * 报废 + 未入账 → 不动库存，只改状态
 * 返工 + 未入账 → 建返工工单，返工良品再入库
 * 返工 + 已入账 → 先调拨到返工仓，返工后转回
 * 让步 + 未入账 → 以其他入库进禁发仓
 * 让步 + 已入账 → 从原仓调拨到禁发仓
 */
const preview = computed(() => {
  const a = props.action
  if (a === 'SCRAP') {
    return props.inStock
      ? {
          tone: 'danger', icon: Delete,
          titleKey: 'qc.disp.previewScrapIn',
          textKey: 'qc.disp.previewScrapInText',
          lines: [
            t('qc.disp.previewScrapInL1'),
            t('qc.disp.previewScrapInL2'),
            t('qc.disp.previewScrapInL3')
          ]
        }
      : {
          tone: 'warn', icon: Delete,
          titleKey: 'qc.disp.previewScrapOut',
          textKey: 'qc.disp.previewScrapOutText',
          lines: [
            t('qc.disp.previewScrapOutL1'),
            t('qc.disp.previewScrapOutL2'),
            t('qc.disp.previewScrapOutL3')
          ]
        }
  }
  if (a === 'REWORK') {
    return props.inStock
      ? {
          tone: 'info', icon: Tools,
          titleKey: 'qc.disp.previewReworkIn',
          textKey: 'qc.disp.previewReworkInText',
          lines: [
            t('qc.disp.previewReworkInL1'),
            t('qc.disp.previewReworkInL2'),
            t('qc.disp.previewReworkInL3')
          ]
        }
      : {
          tone: 'info', icon: Tools,
          titleKey: 'qc.disp.previewReworkOut',
          textKey: 'qc.disp.previewReworkOutText',
          lines: [
            t('qc.disp.previewReworkOutL1'),
            t('qc.disp.previewReworkOutL2'),
            t('qc.disp.previewReworkOutL3')
          ]
        }
  }
  return props.inStock
    ? {
        tone: 'warn', icon: Refresh,
        titleKey: 'qc.disp.previewConcIn',
        textKey: 'qc.disp.previewConcInText',
        lines: [
          t('qc.disp.previewConcInL1'),
          t('qc.disp.previewConcInL2'),
          t('qc.disp.previewConcInL3')
        ]
      }
    : {
        tone: 'warn', icon: Refresh,
        titleKey: 'qc.disp.previewConcOut',
        textKey: 'qc.disp.previewConcOutText',
        lines: [
          t('qc.disp.previewConcOutL1'),
          t('qc.disp.previewConcOutL2'),
          t('qc.disp.previewConcOutL3')
        ]
      }
})
</script>

<style scoped>
.dp-preview {
  border: 1px solid var(--border);
  border-left-width: 3px;
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  padding: 10px 12px;
}
.dp-preview.danger { border-left-color: var(--alarm); }
.dp-preview.warn { border-left-color: var(--idle); }
.dp-preview.info { border-left-color: var(--accent); }
.dpv-head {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--fg);
}
.dpv-text {
  margin: 6px 0 0;
  font-size: var(--text-xs);
  color: var(--fg-2);
  line-height: 1.7;
}
.dpv-list {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: var(--text-2xs);
  color: var(--muted);
  line-height: 1.8;
}
</style>
