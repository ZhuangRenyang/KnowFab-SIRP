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
    <el-tag v-if="data?.challenged" size="small" type="warning" effect="light">
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
      v-if="data?.challenged"
      type="warning"
      :closable="false"
      show-icon
      class="rc-alert"
      :title="t('qc.recon.challengedTitle')"
      :description="t('qc.recon.challengedDesc')"
    />
    <p v-else-if="gapText" class="rc-note">{{ gapText }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { qty } from '@/utils/format'
import type { QcReconView } from '@/api/qc'

const props = withDefaults(defineProps<{
  data?: QcReconView | null
  variant?: 'compact' | 'full'
}>(), { data: null, variant: 'compact' })

// te：判断 i18n 键是否存在。用于「未知代号退化成中性文案」——
// 缺键时 vue-i18n 会返回键路径原文，那等于把内部码暴露给用户。
const { t, te } = useI18n()

/**
 * 以哪条通道为准 —— 这句话决定用户要不要去核对，必须说清楚。
 *
 * source 是后端给的代号（不是文案，英文界面不能蹦中文）：
 *   DEVICE_PRIORITY 设备采到了数，按设备优先（人工不叠加）
 *   MANUAL_ONLY     设备没数，人工是唯一来源
 *   NO_DEVICE       工单没设备 / 设备没配 reject 点位
 * 两边数不一致时（challenged）优先说"待核对"——那是要人去做决定的信号。
 */
const sourceLabel = computed(() => {
  if (props.data?.challenged) return t('qc.recon.challenged')
  const code = props.data?.source
  if (!code) return t('qc.recon.source.NO_DEVICE')
  // 未知代号退化成中性文案（不显示码本身，也不白屏）
  const key = 'qc.recon.source.' + code
  return te(key) ? t(key) : t('qc.recon.source.NO_DEVICE')
})

const sourceHint = computed(() => sourceLabel.value)

/** 差了多少：人工比设备多还是少 */
const gapText = computed(() => {
  const gap = props.data?.gap
  if (gap === undefined || gap === null || gap === 0) return ''
  return gap > 0
    ? t('qc.recon.gapMore', { n: Math.abs(gap) })
    : t('qc.recon.gapLess', { n: Math.abs(gap) })
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
