<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('qc.insp.createTitle')"
    width="820px"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <el-form label-width="96px">
      <div class="frow">
        <el-form-item :label="t('qc.insp.fType')" required>
          <el-radio-group v-model="form.inspectionType" @change="onTypeChange">
            <el-radio-button v-for="x in QC_TYPES" :key="x" :value="x">
              {{ t('qc.type.' + x) }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('qc.insp.fProduct')" required>
          <el-select v-model="form.skuId" filterable class="w-full" :placeholder="t('qc.insp.pickProduct')">
            <el-option v-for="s in skus" :key="s.id" :value="s.id" :label="`${s.code} ${s.name}`" />
          </el-select>
        </el-form-item>
      </div>
      <div class="frow">
        <el-form-item :label="t('qc.insp.fLotQty')" required>
          <el-input-number v-model="form.lotQty" :min="1" :precision="0" controls-position="right" class="w-full" />
        </el-form-item>
        <el-form-item :label="t('qc.insp.fLotNo')">
          <el-input v-model="form.lotNo" :placeholder="t('qc.insp.lotNoPh')" />
        </el-form-item>
      </div>
      <div class="frow">
        <el-form-item :label="t('qc.insp.fSrcBill')">
          <el-input v-model="form.srcBillNo" :placeholder="t('qc.insp.srcBillPh')" />
        </el-form-item>
        <el-form-item :label="t('qc.insp.fDevice')">
          <el-input v-model="form.deviceCode" :placeholder="t('qc.insp.devicePh')" />
        </el-form-item>
      </div>
    </el-form>

    <!-- 抽样参数：FAI / IPQC 走手填，其余走 AQL 查表 -->
    <div class="dt-sec">
      <span class="dt-sec-title">{{ t('qc.insp.secSampling') }}</span>
      <span class="dt-sec-sub">{{ t('qc.insp.sampleRuleHint', { type: t('qc.type.' + form.inspectionType) }) }}</span>
    </div>
    <QcAqlSampling
      v-if="form.inspectionType !== 'FAI' && form.inspectionType !== 'IPQC'"
      :lot-qty="form.lotQty"
      :level="form.sampleLevel"
      :strict-level="form.strictLevel"
      :aql="form.aql"
      :resolved="serverResult"
      :plans="plans"
      :manual-mode="manualMode"
      @change="onSamplingChange"
      @outlet="onOutlet"
    />
    <div v-else class="fixed-n">
      <label class="fn-lb">{{ t('qc.insp.fSampleSize') }}</label>
      <el-input-number v-model="form.sampleSize" :min="1" :precision="0" controls-position="right" />
      <div class="field-hint">{{ t('qc.insp.fSampleSizeHint') }}</div>
    </div>

    <!-- 检验项 -->
    <div class="dt-sec">
      <span class="dt-sec-title">{{ t('qc.insp.secItems') }}</span>
      <span class="dt-sec-sub">{{ t('qc.insp.itemsCount', { n: items.length }) }}</span>
    </div>
    <QcItemEditor v-model="items" :codes="codes" @created="onCodeCreated" />

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t('base.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ t('qc.insp.createSubmit') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  qcDefectCodeApi, qcInspectionApi, QC_TYPES,
  type QcDefectCode, type QcInspectionItemInput, type QcLevel, type QcStrictLevel
} from '@/api/qc'
import { skuApi, type ErpSku } from '@/api/erp'
import { useAqlResolve } from '@/composables/useAqlResolve'
import QcAqlSampling from './QcAqlSampling.vue'
import QcItemEditor from './QcItemEditor.vue'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'created', inspectionNo: string): void
}>()

const { t } = useI18n()

const saving = ref(false)
const skus = ref<ErpSku[]>([])
const codes = ref<QcDefectCode[]>([])
const items = ref<QcInspectionItemInput[]>([])
const { plans, serverResult, manualMode, loadPlans, resolve: resolveAql } = useAqlResolve()

const form = reactive({
  inspectionType: 'IQC' as 'IQC' | 'FAI' | 'IPQC' | 'FQC',
  skuId: undefined as number | undefined,
  lotQty: 100,
  lotNo: '',
  srcBillNo: '',
  deviceCode: '',
  sampleLevel: 'GENERAL_II' as QcLevel,
  strictLevel: 'NORMAL' as QcStrictLevel,
  aql: 1.0 as number | null,
  sampleSize: 1 as number | null
})

watch(() => props.modelValue, async (v) => {
  if (!v) return
  items.value = []
  form.lotQty = 100
  form.aql = 1.0
  form.sampleSize = 1
  try {
    const [s, c] = await Promise.allSettled([skuApi.list(), qcDefectCodeApi.list()])
    if (s.status === 'fulfilled') skus.value = s.value || []
    if (c.status === 'fulfilled') codes.value = c.value.rows || []
  } catch {
    /* 列表拿不到不挡建单，提交时后端会再校验一次 */
  }
  await loadPlans()
  await resolveAql({
    lotQty: form.lotQty,
    level: form.sampleLevel,
    strictLevel: form.strictLevel,
    aql: form.aql ?? 1.0
  })
})

function onTypeChange() {
  if (form.inspectionType === 'FAI') {
    // 首件检验：标准规定 n=1
    form.sampleSize = 1
  } else if (form.inspectionType === 'IPQC') {
    // 过程检验：n 手填
    form.sampleSize = form.sampleSize || 1
  }
}

function onSamplingChange(v: { lotQty: number; level: QcLevel; strictLevel: QcStrictLevel; aql: number }) {
  form.lotQty = v.lotQty
  form.sampleLevel = v.level
  form.strictLevel = v.strictLevel
  form.aql = v.aql
  resolveAql({
    lotQty: form.lotQty,
    level: form.sampleLevel,
    strictLevel: form.strictLevel,
    aql: form.aql ?? 1.0
  })
}

function onOutlet(kind: 'changeLot' | 'addPlan') {
  if (kind === 'addPlan') {
    ElMessage.info(t('qc.aql.outletPlanTip'))
    return
  }
  ElMessage.info(t('qc.aql.outletLotTip'))
}

function onCodeCreated(c: QcDefectCode) {
  // 新建的代码立刻进列表，用户再选一次就能回填到行上
  if (!c.id) {
    codes.value = [...codes.value, { ...c, id: -Date.now() }]
    ElMessage.success(t('qc.item.codeCreatedTip'))
  }
}

async function submit() {
  if (!form.skuId) {
    ElMessage.warning(t('qc.insp.needProduct'))
    return
  }
  if (!form.lotQty || form.lotQty < 1) {
    ElMessage.warning(t('qc.insp.needLotQty'))
    return
  }
  saving.value = true
  try {
    const body: Record<string, unknown> = {
      inspectionType: form.inspectionType,
      skuId: form.skuId,
      lotQty: form.lotQty,
      lotNo: form.lotNo.trim() || null,
      srcBillNo: form.srcBillNo.trim() || null,
      deviceCode: form.deviceCode.trim() || null,
      items: items.value
    }
    if (form.inspectionType === 'FAI' || form.inspectionType === 'IPQC') {
      body.sampleSource = 'FIXED'
      body.sampleSize = form.sampleSize
    } else {
      body.sampleSource = manualMode.value ? 'MANUAL' : 'AQL'
      body.sampleLevel = form.sampleLevel
      body.strictLevel = form.strictLevel
      body.aql = form.aql
    }
    const r = await qcInspectionApi.create(body as never)
    if (!r.ok) {
      ElMessage.error(r.message || t('common.reqFail'))
      return
    }
    ElMessage.success(r.message || t('qc.insp.created'))
    emit('update:modelValue', false)
    emit('created', r.inspectionNo || '')
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.fixed-n {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--surface-2);
  padding: 12px 14px;
}
.fn-lb {
  display: block;
  font-size: var(--text-xs);
  color: var(--muted);
  margin-bottom: 4px;
}
</style>
