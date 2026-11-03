<template>
  <el-drawer
    :model-value="modelValue"
    size="960px"
    :title="t('qc.insp.detailTitle')"
    destroy-on-close
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <template v-if="detail">
      <div class="dt-head">
        <div class="dt-no num">{{ detail.inspectionNo }}</div>
        <el-tag size="small" effect="light" :type="qcStatusType(detail.status)">
          {{ t('qc.status.' + detail.status) }}
        </el-tag>
        <el-tag v-if="detail.result" size="small" effect="light" :type="verdictType(detail.result)">
          {{ t('qc.verdict.' + detail.result) }}
        </el-tag>
        <el-tag size="small" effect="plain">{{ t('qc.type.' + detail.inspectionType) }}</el-tag>
        <div class="grow" />
        <el-button-group>
          <el-button size="small" :icon="ArrowLeft" :disabled="!hasPrev" @click="emit('nav', -1)">
            {{ t('qc.insp.prev') }}
          </el-button>
          <el-button size="small" :disabled="!hasNext" @click="emit('nav', 1)">
            {{ t('qc.insp.next') }}<el-icon class="el-icon--right"><ArrowRight /></el-icon>
          </el-button>
        </el-button-group>
      </div>

      <el-descriptions :column="3" border size="small" class="dt-desc">
        <el-descriptions-item :label="t('qc.insp.product')">
          {{ detail.skuName }} <span class="muted num">{{ detail.skuCode }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('qc.insp.lotQty')">
          <span class="num">{{ qty(detail.lotQty, 0) }}</span> {{ detail.skuUnit }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('qc.insp.lotNo')">
          <span class="num">{{ detail.lotNo || '--' }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('qc.insp.srcBill')">
          <span class="num">{{ detail.srcBillNo || '--' }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('qc.insp.device')">
          <span class="num">{{ detail.deviceCode || '--' }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('qc.insp.inspector')">
          {{ detail.inspector || '--' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('qc.insp.defectQty')" :span="3">
          <!-- 判定唯一依据是不合格品件数，不是缺陷项数。两者口径不同，不能相加 -->
          <span class="num strong">{{ qty(detail.defectQty, 0) }}</span>
          <span class="muted">{{ t('qc.insp.defectBreakdown', {
            cr: detail.defectCr, ma: detail.defectMa, mi: detail.defectMi
          }) }}</span>
        </el-descriptions-item>
        <el-descriptions-item v-if="detail.remark" :label="t('base.remark')" :span="3">
          {{ detail.remark }}
        </el-descriptions-item>
      </el-descriptions>

      <!-- 抽样参数快照：提交后永久冻结，永不回查方案表 -->
      <div class="dt-sec">
        <span class="dt-sec-title">{{ t('qc.insp.secSampling') }}</span>
        <span class="dt-sec-sub">{{ t('qc.insp.snapshotHint') }}</span>
      </div>
      <div class="snap-wrap">
        <QcSamplingSnapshot
          :lot-qty="detail.lotQty"
          :code-letter="detail.codeLetter"
          :sample-size="detail.sampleSize"
          :ac="detail.ac"
          :re="detail.re"
          :zero-tolerance="detail.zeroTolerance"
          :sample-source="detail.sampleSource"
        />
      </div>

      <!-- 检验项明细 -->
      <div class="dt-sec">
        <span class="dt-sec-title">{{ t('qc.insp.secItems') }}</span>
        <span class="dt-sec-sub">{{ t('qc.insp.itemsCount', { n: detail.items.length }) }}</span>
      </div>
      <el-table :data="detail.items" size="small" stripe :empty-text="t('qc.item.empty')">
        <el-table-column :label="t('qc.item.colName')" min-width="150">
          <template #default="{ row }">
            <div class="cell-main">{{ row.itemName }}</div>
            <div class="cell-sub">{{ row.standard || '--' }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('qc.item.colMethod')" width="110">
          <template #default="{ row }">{{ row.method || '--' }}</template>
        </el-table-column>
        <el-table-column :label="t('qc.item.colDefect')" min-width="150">
          <template #default="{ row }">
            <span v-if="row.defectCode" class="num">{{ row.defectCode }}</span>
            <span v-if="row.defectName" class="muted"> {{ row.defectName }}</span>
            <span v-if="!row.defectCode" class="muted">--</span>
            <el-tag
              v-if="row.severity"
              size="small"
              effect="plain"
              class="ml8"
              :type="gradeType(row.severity)"
            >{{ t('qc.severity.' + row.severity) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('qc.item.colMeasured')" min-width="130">
          <template #default="{ row }">{{ row.measured || '--' }}</template>
        </el-table-column>
        <el-table-column :label="t('qc.item.colResult')" width="80">
          <template #default="{ row }">
            <el-tag size="small" effect="light" :type="row.result === 'NG' ? 'danger' : 'success'">
              {{ row.result === 'NG' ? t('qc.item.ng') : t('qc.item.ok') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('qc.item.colQty')" width="80" align="right">
          <template #default="{ row }"><span class="num">{{ qty(row.defectQty, 0) }}</span></template>
        </el-table-column>
      </el-table>

      <!-- 判定区 -->
      <div class="dt-sec">
        <span class="dt-sec-title">{{ t('qc.insp.secJudge') }}</span>
      </div>
      <QcJudgePanel
        :status="detail.status"
        :result="detail.result"
        :defect-qty="defectQty"
        :defect-cr="defectCr"
        :ac="detail.ac"
        :re="detail.re"
        :full-inspection="detail.fullInspection"
        :judged-by="detail.judgedBy"
        :judged-at="detail.judgedAt"
        :judge-text="detail.remark"
        :void-reason="detail.voidReason"
        @update:defect-qty="(v) => (defectQty = v)"
        @update:defect-cr="(v) => (defectCr = v)"
        @update:conditional="(v) => (conditional = v)"
        @update:cond-reason="(v) => (condReason = v)"
      />

      <!-- 底部固定条 -->
      <div class="foot-bar">
        <span class="foot-note">{{ t('qc.insp.footHint') }}</span>
        <div class="grow" />
        <el-button @click="emit('update:modelValue', false)">{{ t('base.close') }}</el-button>
        <el-button
          v-if="detail.status === 'DRAFT' && canWrite"
          type="primary"
          :loading="busy"
          @click="doSubmit"
        >{{ t('qc.insp.submit') }}</el-button>
        <el-button
          v-if="detail.status === 'SUBMITTED' && canWrite"
          type="primary"
          :loading="busy"
          @click="doJudge"
        >{{ t('qc.insp.judge') }}</el-button>
        <el-button
          v-if="detail.status === 'SUBMITTED' && canWrite"
          :loading="busy"
          @click="doCancel"
        >{{ t('qc.insp.cancel') }}</el-button>
      </div>
    </template>

    <el-empty v-else-if="!loading" :description="t('qc.insp.detailEmpty')" />
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import {
  gradeType, qcInspectionApi, qcStatusType, verdictType,
  type QcInspectionDetail
} from '@/api/qc'
import { useAuthStore } from '@/stores/auth'
import { qty } from '@/utils/format'
import QcJudgePanel from './QcJudgePanel.vue'
import QcSamplingSnapshot from './QcSamplingSnapshot.vue'

const props = defineProps<{
  modelValue: boolean
  detail: QcInspectionDetail | null
  loading?: boolean
  hasPrev?: boolean
  hasNext?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'nav', dir: -1 | 1): void
  (e: 'changed'): void
}>()

const { t } = useI18n()
const auth = useAuthStore()

const busy = ref(false)
const defectQty = ref(0)
const defectCr = ref(0)
const conditional = ref(false)
const condReason = ref('')

const canWrite = computed(() => auth.canWrite)

watch(() => props.detail, (d) => {
  if (!d) return
  defectQty.value = Number(d.defectQty) || 0
  defectCr.value = Number(d.defectCr) || 0
  conditional.value = false
  condReason.value = ''
}, { immediate: true })

async function doSubmit() {
  if (!props.detail) return
  busy.value = true
  try {
    const r = await qcInspectionApi.submit(props.detail.inspectionNo)
    if (!r.ok) {
      ElMessage.error(r.message || t('common.reqFail'))
      return
    }
    ElMessage.success(r.message || t('qc.insp.submitted'))
    emit('changed')
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  } finally {
    busy.value = false
  }
}

async function doJudge() {
  const d = props.detail
  if (!d) return
  if (conditional.value && !condReason.value.trim()) {
    ElMessage.warning(t('qc.insp.needCondReason'))
    return
  }
  busy.value = true
  try {
    const body: { defectQty: number; result?: string; remark?: string } = {
      defectQty: defectQty.value
    }
    if (conditional.value) {
      body.result = 'CONDITIONAL'
      body.remark = condReason.value.trim()
    }
    const r = await qcInspectionApi.judge(d.inspectionNo, body)
    if (!r.ok) {
      ElMessage.error(r.message || t('common.reqFail'))
      return
    }
    ElMessage.success(r.message || t('qc.insp.judged'))
    emit('changed')
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  } finally {
    busy.value = false
  }
}

async function doCancel() {
  const d = props.detail
  if (!d) return
  try {
    const r = await ElMessageBox.prompt(t('qc.insp.cancelPh'), t('qc.insp.cancel'), {
      inputPlaceholder: t('qc.insp.cancelReasonPh'),
      confirmButtonText: t('base.save'),
      cancelButtonText: t('base.cancel')
    })
    busy.value = true
    const res = await qcInspectionApi.cancel(d.inspectionNo, r.value || '')
    if (!res.ok) {
      ElMessage.error(res.message || t('common.reqFail'))
      return
    }
    ElMessage.success(res.message || t('qc.insp.cancelled'))
    emit('changed')
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
/* 底部固定条：抽屉内容可滚动，操作按钮始终可见 */
.foot-bar {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 18px -20px -20px;
  padding: 12px 20px;
  border-top: 1px solid var(--border);
  background: var(--surface);
}
.foot-note {
  font-size: var(--text-xs);
  color: var(--muted);
}
.grow { flex: 1; }
.snap-wrap { margin-bottom: 4px; }
</style>
