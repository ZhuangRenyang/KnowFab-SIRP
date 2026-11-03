<template>
  <!--
    双通道对账条。
    两种形态：
    · compact —— 挤在标题行右侧，只给两个数 + 生效值
    · full    —— 独立一块，把「为什么以这条为准」讲清楚
  -->
  <div v-if="variant === 'compact'" class="rc-compact">
    <span class="rc-item">
      <i class="dot dev" />
      <span class="rc-k">{{ t('qc.recon.device') }}</span>
      <b class="num">{{ qty(data?.deviceQty ?? 0) }}</b>
    </span>
    <span class="rc-item">
      <i class="dot man" />
      <span class="rc-k">{{ t('qc.recon.manual') }}</span>
      <b class="num">{{ qty(data?.manualQty ?? 0) }}</b>
    </span>
    <el-tooltip :content="sourceHint" placement="top">
      <span class="rc-item eff">
        <span class="rc-k">{{ t('qc.recon.effective') }}</span>
        <b class="num">{{ qty(data?.effectiveQty ?? 0) }}</b>
      </span>
    </el-tooltip>
    <el-tag v-if="data?.source === 'CHALLENGED'" size="small" type="warning" effect="light">
      {{ t('qc.recon.challenged') }}
    </el-tag>
  </div>

  <div v-else class="rc-full">
    <div class="rc-cols">
      <div class="rc-col">
        <div class="rc-k">{{ t('qc.recon.device') }}</div>
        <div class="rc-v num">{{ qty(data?.deviceQty ?? 0) }}</div>
        <div class="rc-s" :class="data?.deviceCollected ? '' : 'muted'">
          {{ data?.deviceConfigured
            ? (data.deviceCollected ? t('qc.recon.deviceCollected') : t('qc.recon.deviceNoData'))
            : t('qc.recon.deviceNoPoint') }}
        </div>
      </div>
      <div class="rc-col">
        <div class="rc-k">{{ t('qc.recon.manual') }}</div>
        <div class="rc-v num">{{ qty(data?.manualQty ?? 0) }}</div>
        <div class="rc-s muted">{{ t('qc.recon.manualHint') }}</div>
      </div>
      <div class="rc-col eff">
        <div class="rc-k">{{ t('qc.recon.effective') }}</div>
        <div class="rc-v num">{{ qty(data?.effectiveQty ?? 0) }}</div>
        <div class="rc-s">{{ sourceLabel }}</div>
      </div>
    </div>

    <!-- 两边都 > 0：不自动相加，标记成待核对。绝不能悄悄把两个数加起来 -->
    <el-alert
      v-if="data?.source === 'CHALLENGED'"
      type="warning"
      :closable="false"
      show-icon
      class="rc-alert"
      :title="t('qc.recon.challengedTitle')"
      :description="t('qc.recon.challengedDesc')"
    />
    <p v-else-if="data?.note" class="rc-note">{{ data.note }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { qty } from '@/utils/format'
import type { QcRecon } from '@/api/qc'

const props = withDefaults(defineProps<{
  data?: QcRecon | null
  variant?: 'compact' | 'full'
}>(), { data: null, variant: 'compact' })

const { t } = useI18n()

/** 以哪条通道为准 —— 这句话决定用户要不要去核对，必须说清楚 */
const sourceLabel = computed(() => {
  switch (props.data?.source) {
    case 'DEVICE':
      return t('qc.recon.srcDevice')
    case 'MANUAL':
      return t('qc.recon.srcManual')
    case 'CHALLENGED':
      return t('qc.recon.srcChallenged')
    default:
      return t('qc.recon.srcNone')
  }
})

const sourceHint = computed(() => {
  const s = props.data?.source
  if (s === 'DEVICE') return t('qc.recon.srcDevice')
  if (s === 'MANUAL') return t('qc.recon.srcManual')
  if (s === 'CHALLENGED') return t('qc.recon.srcChallenged')
  return t('qc.recon.srcNone')
})
</script>

<style scoped>
/* 布局与配色走全局骨架（.hint / .kpi-row 等）与 tokens.css 的语义变量，
   这里只写本组件特有的排版，不重复定义全局类。 */

.rc-compact {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  font-size: var(--text-xs);
  color: var(--fg-2);
}
.rc-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.rc-k {
  color: var(--muted);
}
.rc-item b {
  color: var(--fg);
  font-weight: 600;
}
.rc-item.eff b {
  color: var(--accent);
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 2px;
  display: inline-block;
}
.dot.dev { background: var(--accent); }
.dot.man { background: var(--idle); }

.rc-full {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--surface-2);
  padding: 12px 14px;
}
.rc-cols {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}
.rc-col {
  border-left: 2px solid var(--border);
  padding-left: 10px;
}
.rc-col.eff {
  border-left-color: var(--accent);
}
.rc-k {
  font-size: var(--text-xs);
  color: var(--muted);
}
.rc-v {
  font-size: 20px;
  font-weight: 600;
  color: var(--fg);
  line-height: 1.25;
}
.rc-s {
  margin-top: 2px;
  font-size: var(--text-2xs);
  color: var(--fg-2);
}
.rc-alert {
  margin-top: 10px;
}
.rc-note {
  margin: 8px 0 0;
  font-size: var(--text-xs);
  color: var(--muted);
  line-height: 1.6;
}
</style>
