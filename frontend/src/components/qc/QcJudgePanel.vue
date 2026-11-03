<template>
  <div>
    <!-- 已判定：展示结论与依据 -->
    <div v-if="status === 'JUDGED'" class="judge-done">
      <div class="jd-head">
        <el-tag size="small" effect="light" :type="verdictType(result || '')">
          {{ result ? t('qc.verdict.' + result) : '--' }}
        </el-tag>
        <span class="muted">
          {{ judgedBy || '--' }}
          <span v-if="judgedAt" class="num"> · {{ dt(judgedAt) }}</span>
        </span>
      </div>
      <p v-if="judgeText" class="jd-text">{{ judgeText }}</p>
    </div>

    <!-- 已提交待判定：判定岗在这里出结论 -->
    <div v-else-if="status === 'SUBMITTED'" class="judge-form">
      <div class="frow">
        <div>
          <label class="jf-lb">{{ t('qc.insp.defectQtyInput') }}</label>
          <el-input-number
            v-model="localQty"
            :min="0"
            :precision="0"
            controls-position="right"
            class="w-full"
          />
          <div class="field-hint">{{ t('qc.insp.defectQtyHint') }}</div>
        </div>
        <div>
          <label class="jf-lb">{{ t('qc.insp.crCount') }}</label>
          <el-input-number
            v-model="localCr"
            :min="0"
            :precision="0"
            controls-position="right"
            class="w-full"
          />
          <div class="field-hint">{{ t('qc.insp.crHint') }}</div>
        </div>
      </div>

      <!-- 判定预览：提交前就让人看到结论，但最终以服务端为准 -->
      <div v-if="preview" class="jf-prev" :class="preview.verdict === 'PASS' ? 'ok' : 'ng'">
        <span class="jp-main">{{ t('qc.why.' + preview.why) }}</span>
        <span class="jp-sub">{{ modePrefix }}{{ previewText }}</span>
      </div>
      <el-alert
        v-if="preview?.inconsistent"
        type="warning"
        :closable="false"
        show-icon
        class="jf-alert"
        :title="t('qc.insp.inconsistent')"
      />

      <!-- 让步接收：算法永远不产出，只能人工选，且必须写原因 -->
      <div class="jf-cond">
        <el-checkbox v-model="conditional">
          {{ t('qc.insp.conditional') }}
        </el-checkbox>
        <el-input
          v-if="conditional"
          v-model="condReason"
          class="jf-cond-in"
          size="small"
          :placeholder="t('qc.insp.condReasonPh')"
        />
      </div>
      <p class="jf-hint">{{ t('qc.insp.condHint') }}</p>
    </div>

    <!-- 草稿：只读，等待提交 -->
    <el-alert
      v-else-if="status === 'DRAFT'"
      type="info"
      :closable="false"
      show-icon
      :title="t('qc.insp.draftHint')"
    />

    <el-alert
      v-else-if="status === 'CANCELLED'"
      type="info"
      :closable="false"
      show-icon
      :title="t('qc.insp.cancelledHint')"
      :description="voidReason || ''"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { verdictType } from '@/api/qc'
import { previewVerdict, splitJudgeHint } from '@/utils/aql'
import { dt } from '@/utils/format'

const props = defineProps<{
  status: string
  result?: string | null
  /** 不合格品件数 —— 判定唯一依据 */
  defectQty: number
  /** 致命缺陷项数 */
  defectCr: number
  ac: number | null
  re: number | null
  fullInspection: boolean
  judgedBy?: string | null
  judgedAt?: number | null
  judgeText?: string | null
  voidReason?: string | null
}>()

const emit = defineEmits<{
  (e: 'update:defectQty', v: number): void
  (e: 'update:defectCr', v: number): void
  (e: 'update:conditional', v: boolean): void
  (e: 'update:condReason', v: string): void
}>()

const { t } = useI18n()

const localQty = ref(props.defectQty)
const localCr = ref(props.defectCr)
const conditional = ref(false)
const condReason = ref('')

watch(() => props.defectQty, (v) => { localQty.value = v })
watch(() => props.defectCr, (v) => { localCr.value = v })
watch(localQty, (v) => emit('update:defectQty', v))
watch(localCr, (v) => emit('update:defectCr', v))
watch(conditional, (v) => emit('update:conditional', v))
watch(condReason, (v) => emit('update:condReason', v))

/** 判定预览。与服务端同一套规则，但服务端才是真值。 */
const preview = computed(() => {
  if (props.ac == null || props.re == null) return null
  return previewVerdict({
    defectCr: localCr.value,
    defectQty: localQty.value,
    ac: props.ac,
    re: props.re,
    fullInspection: props.fullInspection
  })
})

/** 模式前缀。主行不加前缀，副行加 —— 后端原文后 3 条自带「抽检：」，重复会变成「抽检：抽检：」 */
const modePrefix = computed(() =>
  props.fullInspection ? t('qc.insp.modeFull') : t('qc.insp.modeSample')
)

/** 副行原文：摘掉后端可能已带的前缀，模式词由这里统一加 */
const previewText = computed(() => {
  const base = splitJudgeHint(props.judgeText, props.fullInspection)
  return base || t('qc.insp.previewFallback')
})
</script>

<style scoped>
.judge-done {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--surface-2);
  padding: 12px 14px;
}
.jd-head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: var(--text-xs);
}
.jd-text {
  margin: 8px 0 0;
  font-size: var(--text-sm);
  color: var(--fg-2);
  line-height: 1.7;
}

.judge-form {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--surface-2);
  padding: 12px 14px;
}
.jf-lb {
  display: block;
  font-size: var(--text-xs);
  color: var(--muted);
  margin-bottom: 4px;
}
.jf-prev {
  margin-top: 12px;
  border-left: 2px solid var(--border);
  padding: 6px 0 6px 10px;
}
.jf-prev.ok { border-left-color: var(--run); }
.jf-prev.ng { border-left-color: var(--alarm); }
.jp-main {
  display: block;
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--fg);
}
.jp-sub {
  display: block;
  margin-top: 2px;
  font-size: var(--text-xs);
  color: var(--fg-2);
  line-height: 1.6;
}
.jf-alert { margin-top: 10px; }
.jf-cond {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.jf-cond-in { width: 320px; }
.jf-hint {
  margin: 6px 0 0;
  font-size: var(--text-2xs);
  color: var(--muted);
  line-height: 1.6;
}
</style>
