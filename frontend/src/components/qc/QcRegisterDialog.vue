<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('qc.reg.title')"
    width="680px"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <!-- 双通道实时对照：人工补录前先看设备采了多少 -->
    <div class="rg-recon">
      <QcReconBar :data="baseline" variant="compact" />
      <p class="rg-hint">{{ t('qc.reg.reconHint') }}</p>
    </div>

    <el-form label-width="96px">
      <div class="frow">
        <el-form-item :label="t('qc.reg.fWorkOrder')">
          <el-input v-model="form.workOrderNo" :placeholder="t('qc.reg.workOrderPh')" @blur="loadBaseline" />
        </el-form-item>
        <el-form-item :label="t('qc.reg.fDevice')">
          <el-input v-model="form.deviceCode" :placeholder="t('qc.reg.devicePh')" />
        </el-form-item>
      </div>

      <div class="frow">
        <el-form-item :label="t('qc.reg.fProduct')" required>
          <el-select v-model="form.skuId" filterable class="w-full" :placeholder="t('qc.reg.pickProduct')">
            <el-option v-for="s in skus" :key="s.id" :value="s.id" :label="`${s.code} ${s.name}`" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('qc.reg.fQty')" required>
          <el-input-number v-model="form.qty" :min="1" :precision="0" controls-position="right" class="w-full" />
        </el-form-item>
      </div>

      <div class="frow">
        <el-form-item :label="t('qc.reg.fDefect')" required>
          <el-select v-model="form.defectCodeId" filterable clearable class="w-full" :placeholder="t('qc.reg.pickDefect')">
            <el-option v-for="c in codes" :key="c.id" :value="c.id" :label="`${c.code} ${c.name}`">
              <span class="opt-code">{{ c.code }}</span>
              <el-tag size="small" effect="plain" :type="gradeType(c.severity)">
                {{ t('qc.severity.' + c.severity) }}
              </el-tag>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item :label="t('qc.reg.fLotNo')">
          <el-input v-model="form.lotNo" :placeholder="t('qc.reg.lotNoPh')" />
        </el-form-item>
      </div>

      <div class="frow">
        <el-form-item :label="t('qc.reg.fInStock')">
          <el-switch v-model="form.inStock" />
          <div class="field-hint">{{ t('qc.reg.inStockHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('qc.reg.fSourceBill')">
          <el-input v-model="form.srcBillNo" :placeholder="t('qc.reg.srcBillPh')" />
        </el-form-item>
      </div>

      <el-form-item :label="t('base.remark')">
        <el-input v-model="form.remark" type="textarea" :rows="2" :placeholder="t('qc.reg.remarkPh')" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t('base.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ t('qc.reg.submit') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  gradeType, qcDefectApi, qcDefectCodeApi,
  type QcDefectCode, type QcDeviceBaseline
} from '@/api/qc'
import { skuApi, type ErpSku } from '@/api/erp'
import QcReconBar from './QcReconBar.vue'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved'): void
}>()

const { t } = useI18n()

const saving = ref(false)
const skus = ref<ErpSku[]>([])
const codes = ref<QcDefectCode[]>([])
const baseline = ref<QcDeviceBaseline | null>(null)

const form = reactive({
  workOrderNo: '',
  deviceCode: '',
  skuId: undefined as number | undefined,
  qty: 1,
  defectCodeId: undefined as number | undefined,
  lotNo: '',
  inStock: false,
  srcBillNo: '',
  remark: ''
})

watch(() => props.modelValue, async (v) => {
  if (!v) return
  form.workOrderNo = ''
  form.deviceCode = ''
  form.skuId = undefined
  form.qty = 1
  form.defectCodeId = undefined
  form.lotNo = ''
  form.inStock = false
  form.srcBillNo = ''
  form.remark = ''
  baseline.value = null
  try {
    const [s, c] = await Promise.allSettled([skuApi.list(), qcDefectCodeApi.list({ enabled: true })])
    if (s.status === 'fulfilled') skus.value = s.value || []
    if (c.status === 'fulfilled') codes.value = c.value.rows || []
  } catch {
    /* 拿不到不挡补录，提交时后端会再校验 */
  }
})

/**
 * 取设备通道基线。
 * 设备配了 reject 点位且当日有采集 → 以设备为准，人工补录只作对照；
 * 设备无数据 → 以人工为准。取不到就当没有基线，不猜。
 */
async function loadBaseline() {
  const wo = form.workOrderNo.trim()
  if (!wo) {
    baseline.value = null
    return
  }
  try {
    const r = await qcDefectApi.deviceBaseline(wo)
    baseline.value = r && r.ok ? r : null
  } catch {
    baseline.value = null
  }
}

async function submit() {
  if (!form.skuId) {
    ElMessage.warning(t('qc.reg.needProduct'))
    return
  }
  if (!form.qty || form.qty < 1) {
    ElMessage.warning(t('qc.reg.needQty'))
    return
  }
  if (!form.defectCodeId) {
    ElMessage.warning(t('qc.reg.needDefect'))
    return
  }
  saving.value = true
  try {
    const sku = skus.value.find((s) => s.id === form.skuId)
    const code = codes.value.find((c) => c.id === form.defectCodeId)
    const r = await qcDefectApi.create({
      skuId: form.skuId,
      skuCode: sku?.code ?? null,
      skuName: sku?.name ?? null,
      qty: form.qty,
      defectCodeId: form.defectCodeId,
      defectCode: code?.code ?? null,
      defectName: code?.name ?? null,
      severity: code?.severity ?? null,
      lotNo: form.lotNo.trim() || null,
      deviceCode: form.deviceCode.trim() || null,
      workOrderNo: form.workOrderNo.trim() || null,
      inStock: form.inStock,
      srcBillNo: form.srcBillNo.trim() || null,
      remark: form.remark.trim() || null
    })
    if (!r.ok) {
      ElMessage.error(r.message || t('common.reqFail'))
      return
    }
    ElMessage.success(r.message || t('qc.reg.done'))
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
.rg-recon {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--surface-2);
  padding: 10px 12px;
  margin-bottom: 14px;
}
.rg-hint {
  margin: 6px 0 0;
  font-size: var(--text-2xs);
  color: var(--muted);
  line-height: 1.6;
}
.opt-code {
  display: inline-block;
  min-width: 64px;
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  color: var(--muted);
}
</style>
