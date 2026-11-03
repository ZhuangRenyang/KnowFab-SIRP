<template>
  <el-dialog
    :model-value="modelValue"
    :title="batch ? t('qc.disp.batchTitle') : t('qc.disp.title')"
    width="720px"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <template v-if="target">
      <!-- 单条：显示不良记录本身 -->
      <div class="dp-head">
        <span class="dp-no num">{{ target.defectNo }}</span>
        <span class="dp-p">{{ target.skuName || '--' }}</span>
        <span class="dp-q num">{{ qty(target.qty, 0) }} {{ t('qc.disp.pcs') }}</span>
        <el-tag size="small" effect="plain" :type="sourceType(target.source)">
          {{ t('qc.source.' + target.source) }}
        </el-tag>
        <el-tag v-if="target.inStock" size="small" type="warning" effect="plain">
          {{ t('qc.disp.inStock') }}
        </el-tag>
      </div>
    </template>

    <!-- 后端提示：代号不是文案，前端按 i18n 渲染（英文界面不能蹦中文） -->
    <el-alert
      v-if="warnText"
      type="warning"
      :closable="false"
      show-icon
      class="dp-warn"
      :title="warnText"
    />

    <!-- 处置方式 -->
    <div class="dt-sec">
      <span class="dt-sec-title">{{ t('qc.disp.secAction') }}</span>
      <span class="dt-sec-sub">{{ t('qc.disp.actionHint') }}</span>
    </div>
    <el-radio-group v-model="action" class="dp-actions">
      <el-radio-button v-for="a in DISPOSITIONS" :key="a" :value="a">
        {{ t('qc.disposition.' + a) }}
      </el-radio-button>
    </el-radio-group>

    <!-- 数量 -->
    <div class="frow">
      <el-form-item :label="t('qc.disp.qty')" class="dp-item">
        <el-input-number
          v-model="qtyInput"
          :min="0"
          :max="maxQty"
          :precision="0"
          controls-position="right"
          class="w-full"
        />
        <div class="field-hint">{{ t('qc.disp.qtyHint', { max: maxQty }) }}</div>
      </el-form-item>

      <!-- 返工失败退回件数 -->
      <el-form-item v-if="action === 'REWORK'" :label="t('qc.disp.failQty')" class="dp-item">
        <el-input-number
          v-model="failQty"
          :min="0"
          :max="qtyInput"
          :precision="0"
          controls-position="right"
          class="w-full"
        />
        <div class="field-hint">{{ t('qc.disp.failQtyHint') }}</div>
      </el-form-item>

      <!-- 让步：商务决定，要能追溯谁批的 -->
      <el-form-item v-if="action === 'CONCESSION'" :label="t('qc.disp.approver')" class="dp-item">
        <el-input v-model="approver" :placeholder="t('qc.disp.approverPh')" />
        <div class="field-hint">{{ t('qc.disp.approverHint') }}</div>
      </el-form-item>
    </div>

    <div v-if="action === 'REWORK'" class="frow">
      <el-form-item :label="t('qc.disp.failReason')" class="dp-item">
        <el-input v-model="failReason" :placeholder="t('qc.disp.failReasonPh')" />
      </el-form-item>
    </div>

    <div v-if="action === 'CONCESSION'" class="frow">
      <el-form-item :label="t('qc.disp.partner')" class="dp-item">
        <el-select v-model="partnerId" clearable filterable class="w-full" :placeholder="t('qc.disp.partnerPh')">
          <el-option v-for="p in partners" :key="p.id" :value="p.id" :label="p.name" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('qc.disp.amount')" class="dp-item">
        <el-input-number v-model="amount" :min="0" :precision="2" controls-position="right" class="w-full" />
        <div class="field-hint">{{ t('qc.disp.amountHint') }}</div>
      </el-form-item>
    </div>

    <el-form-item :label="t('base.remark')">
      <el-input v-model="remark" type="textarea" :rows="2" :placeholder="t('qc.disp.remarkPh')" />
    </el-form-item>

    <!-- 后果预告：处置 × 库存状态 六分支。这一步会动库存，说清楚 -->
    <div class="dt-sec">
      <span class="dt-sec-title">{{ t('qc.disp.secPreview') }}</span>
      <span class="dt-sec-sub">{{ t('qc.disp.previewHint') }}</span>
    </div>
    <QcDispositionPreview :action="action" :in-stock="target?.inStock === true" />

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t('base.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ t('qc.disp.submit') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  qcDefectApi, sourceType, DISPOSITIONS, type QcDefectRow, type QcDisposition
} from '@/api/qc'
import { partnerApi, type ErpPartner } from '@/api/erp'
import { qty } from '@/utils/format'
import QcDispositionPreview from './QcDispositionPreview.vue'

const props = defineProps<{
  modelValue: boolean
  /** 单条处置传一条；批量传多条 */
  defects: QcDefectRow[]
  batch?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved'): void
}>()

const { t, te } = useI18n()

/**
 * 处置后果预告（代号不是文案，前端按 i18n 渲染）。
 *
 * 预告在<b>弹窗里</b>给，不等提交——提交完弹窗就关了，用户来不及看。
 * 这一条前端自己能算：返工 + 已入账 → 货还在仓里，得先领出来。
 * 「让步还缺客户同意凭证」那条前端判断不了（凭证在别的系统里），
 * 由后端在处置结果里给 warnCode，提交后用消息提示补上。
 *
 * 未知代号退化成中性文案——缺键时 vue-i18n 会返回键路径原文，
 * 那等于把内部码暴露给用户（"qc.disp.warn.REWORK_NEED_ISSUE"）。
 */
const warnText = computed(() => {
  if (!target.value) return ''
  let code: string | null = null
  if (action.value === 'REWORK' && target.value.inStock) {
    code = 'REWORK_NEED_ISSUE'
  }
  if (!code) return ''
  const key = 'qc.disp.warn.' + code
  return te(key) ? t(key) : ''
})

const saving = ref(false)
const action = ref<QcDisposition>('SCRAP')
const qtyInput = ref(0)
const failQty = ref(0)
const failReason = ref('')
const approver = ref('')
const partnerId = ref<number | null>(null)
const amount = ref<number | null>(null)
const remark = ref('')
const partners = ref<ErpPartner[]>([])

const target = computed(() => props.defects[0] || null)

/** 本次能处置的最大数量：待处置件数之和 */
const maxQty = computed(() =>
  props.defects.reduce((a, d) => a + Math.max(0, Number(d.pendingQty ?? d.qty ?? 0)), 0)
)

watch(() => props.modelValue, async (v) => {
  if (!v) return
  action.value = 'SCRAP'
  qtyInput.value = maxQty.value
  failQty.value = 0
  failReason.value = ''
  approver.value = ''
  partnerId.value = null
  amount.value = null
  remark.value = ''
  try {
    partners.value = (await partnerApi.list()) || []
  } catch {
    partners.value = []
  }
})

async function submit() {
  if (!target.value) return
  if (qtyInput.value <= 0) {
    ElMessage.warning(t('qc.disp.needQty'))
    return
  }
  if (action.value === 'REWORK' && failQty.value > 0 && !failReason.value.trim()) {
    ElMessage.warning(t('qc.disp.needFailReason'))
    return
  }
  if (action.value === 'CONCESSION' && !approver.value.trim()) {
    ElMessage.warning(t('qc.disp.needApprover'))
    return
  }
  saving.value = true
  try {
    if (props.batch) {
      // 批量：每条各处置同样的件数
      const r = await qcDefectApi.batchDispose({
        defectNos: props.defects.map((d) => d.defectNo),
        action: action.value,
        qty: qtyInput.value,
        remark: remark.value.trim() || null
      })
      if (!r.ok) {
        ElMessage.error(r.message || t('common.reqFail'))
        return
      }
      // 部分没处理成功不是全失败：说清有几条没成、分别是什么原因。
      // 原因码是代号（DISPOSED / CONFLICT / ERROR），前端给文案——
      // 三种原因要用户做的事不同，不能合并成一句"部分失败"。
      const skipped = r.skipped || []
      if (skipped.length) {
        const reasons = [...new Set(
          skipped.map((s) => {
            const key = 'qc.skipReason.' + s.reason
            return te(key) ? t(key) : s.reason
          })
        )]
        ElMessage.warning(`${t('qc.triage.partialDone', { n: skipped.length })} ${reasons.join('、')}`)
      } else {
        ElMessage.success(r.message || t('qc.disp.done'))
      }
      emit('update:modelValue', false)
      emit('saved')
      return
    }
    const no = target.value.defectNo
    const r = action.value === 'SCRAP'
      ? await qcDefectApi.scrap(no, { qty: qtyInput.value, remark: remark.value.trim() || null })
      : action.value === 'REWORK'
        ? await qcDefectApi.rework(no, {
            qty: qtyInput.value,
            failQty: failQty.value,
            failReason: failReason.value.trim() || null,
            remark: remark.value.trim() || null
          })
        : await qcDefectApi.concession(no, {
            qty: qtyInput.value,
            concessionPartnerId: partnerId.value,
            concessionApprover: approver.value.trim(),
            concessionAmount: amount.value,
            remark: remark.value.trim() || null
          })
    if (!r.ok) {
      ElMessage.error(r.message || t('common.reqFail'))
      return
    }
    ElMessage.success(r.message || t('qc.disp.done'))
    // 后端补的处置提示（前端算不出来的那些）：让步缺客户同意凭证等。
    // 处置已经生效了，这条是"接下来还要做什么"——用消息提示，别塞进已关闭的弹窗。
    if (r.warnCode) {
      const key = 'qc.disp.warn.' + r.warnCode
      if (te(key)) ElMessage.warning(t(key))
    }
    emit('update:modelValue', false)
    emit('saved')
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.dp-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.dp-no {
  font-family: var(--font-mono);
  font-size: 15px;
  font-weight: 600;
  color: var(--fg);
}
.dp-p { color: var(--fg-2); font-size: var(--text-sm); }
.dp-q { color: var(--muted); font-size: var(--text-xs); }
.dp-actions { margin-bottom: 12px; }
.dp-item { margin-bottom: 12px; }

</style>
