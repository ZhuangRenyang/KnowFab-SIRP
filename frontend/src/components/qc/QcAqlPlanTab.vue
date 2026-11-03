<template>
  <div>
    <div class="tab-toolbar">
      <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('base.refresh') }}</el-button>
      <div class="grow" />
      <el-button v-if="auth.canWrite" type="primary" :icon="Plus" @click="openPlan()">
        {{ t('qc.dict.newPlan') }}
      </el-button>
      <el-button v-if="auth.canWrite" :icon="Refresh" @click="reseed">{{ t('qc.dict.reseed') }}</el-button>
    </div>

    <!-- 推算格子与核对过的格子可信度不同，必须标出来。
         默默用 = 客户以为是国标，其实有二十几格是猜的 -->
    <el-alert
      v-if="(meta.unverifiedCount ?? 0) > 0"
      type="warning"
      :closable="false"
      show-icon
      class="mb12"
      :title="t('qc.dict.unverifiedTitle')"
      :description="t('qc.dict.unverifiedDesc', { n: meta.unverifiedCount ?? 0 })"
    />

    <el-alert
      v-if="errMsg"
      type="error"
      :closable="false"
      show-icon
      class="mb12"
      :title="t('qc.dict.loadFail')"
      :description="errMsg"
    >
      <template #default>
        <el-button size="small" :icon="Refresh" @click="load">{{ t('qc.dict.retry') }}</el-button>
      </template>
    </el-alert>

    <el-table :data="plans" stripe v-loading="loading" :empty-text="t('qc.dict.planEmpty')">
      <el-table-column :label="t('qc.dict.colLot')" width="130">
        <template #default="{ row }">
          <span class="num">{{ row.lotFrom }}~{{ row.lotTo }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.dict.colCombo')" width="116">
        <template #default="{ row }">
          <span class="num">{{ t('qc.level.' + row.level) }}</span>
          <span class="muted"> / {{ t('qc.strictLevel.' + row.strictLevel) }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.dict.colLetter')" width="72">
        <template #default="{ row }"><span class="num">{{ row.codeLetter }}</span></template>
      </el-table-column>
      <el-table-column :label="t('qc.dict.colSample')" width="80" align="right">
        <template #default="{ row }"><span class="num">{{ row.sampleSize }}</span></template>
      </el-table-column>
      <el-table-column :label="t('qc.dict.colAc')" width="96" align="right">
        <template #default="{ row }">
          <span class="num" :class="{ danger: row.zeroTolerance }">
            {{ row.zeroTolerance ? t('qc.aql.acZeroTolShort') : row.ac }}
          </span>
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.dict.colRe')" width="72" align="right">
        <template #default="{ row }"><span class="num">{{ row.re }}</span></template>
      </el-table-column>
      <el-table-column :label="t('qc.dict.colAql')" width="80" align="right">
        <template #default="{ row }"><span class="num">{{ row.aql }}%</span></template>
      </el-table-column>
      <el-table-column :label="t('qc.dict.colVerified')" width="94">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" :type="row.verified ? 'success' : 'warning'">
            {{ row.verified ? t('qc.dict.verified') : t('qc.dict.unverified') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('base.action')" width="150" align="right">
        <template #default="{ row }">
          <el-button
            v-if="!row.verified && auth.canWrite"
            size="small"
            @click="verify(row)"
          >{{ t('qc.dict.verify') }}</el-button>
          <el-button v-if="auth.canWrite" size="small" @click="openPlan(row)">{{ t('base.edit') }}</el-button>
          <el-button
            v-if="auth.canWrite"
            size="small"
            type="danger"
            plain
            @click="removePlan(row)"
          >{{ t('base.del') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- AQL 方案编辑 -->
    <el-dialog v-model="planDlg" :title="planForm.id ? t('qc.dict.editPlan') : t('qc.dict.newPlan')" width="560px">
      <el-form label-width="104px">
        <div class="frow">
          <el-form-item :label="t('qc.dict.fLotFrom')" required>
            <el-input-number v-model="planForm.lotFrom" :min="1" :precision="0" controls-position="right" class="w-full" />
          </el-form-item>
          <el-form-item :label="t('qc.dict.fLotTo')" required>
            <el-input-number v-model="planForm.lotTo" :min="1" :precision="0" controls-position="right" class="w-full" />
          </el-form-item>
        </div>
        <div class="frow">
          <el-form-item :label="t('qc.dict.fLevel')" required>
            <el-select v-model="planForm.level" class="w-full">
              <el-option v-for="l in QC_LEVELS" :key="l" :value="l" :label="t('qc.level.' + l)" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('qc.dict.fStrict')" required>
            <el-select v-model="planForm.strictLevel" class="w-full">
              <el-option v-for="s in STRICT_LEVELS" :key="s" :value="s" :label="t('qc.strictLevel.' + s)" />
            </el-select>
          </el-form-item>
        </div>
        <div class="frow">
          <el-form-item :label="t('qc.dict.fLetter')" required>
            <el-input v-model="planForm.codeLetter" maxlength="2" placeholder="J" />
          </el-form-item>
          <el-form-item :label="t('qc.dict.fSample')" required>
            <el-input-number v-model="planForm.sampleSize" :min="1" :precision="0" controls-position="right" class="w-full" />
          </el-form-item>
        </div>
        <div class="frow">
          <el-form-item :label="t('qc.dict.fAc')" required>
            <el-input-number v-model="planForm.ac" :min="-1" :precision="0" controls-position="right" class="w-full" />
            <div class="field-hint">{{ t('qc.dict.acHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('qc.dict.fAql')" required>
            <el-input-number v-model="planForm.aql" :min="0.01" :max="10" :precision="2" controls-position="right" class="w-full" />
          </el-form-item>
        </div>
        <el-form-item :label="t('base.remark')">
          <el-input v-model="planForm.remark" maxlength="255" :placeholder="t('qc.dict.planRemarkPh')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planDlg = false">{{ t('base.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="savePlan">{{ t('base.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { qcAqlApi, QC_LEVELS, STRICT_LEVELS, type QcAqlPlan } from '@/api/qc'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const auth = useAuthStore()

const plans = ref<QcAqlPlan[]>([])
const meta = ref<{ unverifiedCount?: number; crRuleNote?: string }>({})
const loading = ref(false)
const errMsg = ref('')
const saving = ref(false)
const planDlg = ref(false)

const planForm = reactive({
  id: 0, level: 'GENERAL_II' as string, strictLevel: 'NORMAL' as string,
  lotFrom: 2, lotTo: 8, codeLetter: 'A', sampleSize: 2, ac: 0,
  aql: 1.0, remark: ''
})

async function load() {
  loading.value = true
  errMsg.value = ''
  try {
    const r = await qcAqlApi.plans()
    plans.value = r.rows || []
    meta.value = { unverifiedCount: r.unverifiedCount, crRuleNote: r.crRuleNote }
  } catch (e) {
    errMsg.value = (e as Error)?.message || t('common.reqFail')
    plans.value = []
  } finally {
    loading.value = false
  }
}

function openPlan(row?: QcAqlPlan) {
  Object.assign(planForm, row
    ? { ...row, remark: row.remark || '' }
    : {
        id: 0, level: 'GENERAL_II', strictLevel: 'NORMAL',
        lotFrom: 2, lotTo: 8, codeLetter: 'A', sampleSize: 2, ac: 0, aql: 1.0, remark: ''
      })
  planDlg.value = true
}

async function savePlan() {
  if (planForm.lotTo < planForm.lotFrom) {
    ElMessage.warning(t('qc.dict.badLotRange'))
    return
  }
  if (planForm.ac < -1) {
    ElMessage.warning(t('qc.dict.badAc'))
    return
  }
  saving.value = true
  try {
    const body = {
      level: planForm.level as never,
      strictLevel: planForm.strictLevel as never,
      lotFrom: planForm.lotFrom,
      lotTo: planForm.lotTo,
      codeLetter: planForm.codeLetter.trim(),
      sampleSize: planForm.sampleSize,
      ac: planForm.ac,
      aql: planForm.aql,
      remark: planForm.remark.trim() || null
    }
    const r = planForm.id
      ? await qcAqlApi.updatePlan(planForm.id, body)
      : await qcAqlApi.createPlan(body)
    if (!r.ok) {
      ElMessage.error(r.message || t('common.reqFail'))
      return
    }
    ElMessage.success(r.message || t('common.saveOk'))
    planDlg.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  } finally {
    saving.value = false
  }
}

async function removePlan(row: QcAqlPlan) {
  try {
    await ElMessageBox.confirm(t('qc.dict.delPlanConfirm', { lot: row.lotFrom + '~' + row.lotTo }),
      t('qc.dict.delPlanTitle'), {
        type: 'warning', confirmButtonText: t('base.del'), cancelButtonText: t('base.cancel')
      })
  } catch { return }
  try {
    const r = await qcAqlApi.removePlan(row.id)
    if (!r.ok) {
      ElMessage.error(r.message || t('common.reqFail'))
      return
    }
    ElMessage.success(r.message || t('common.success'))
    await load()
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  }
}

async function verify(row: QcAqlPlan) {
  try {
    const r = await qcAqlApi.verifyPlan(row.id)
    if (!r.ok) {
      ElMessage.error(r.message || t('common.reqFail'))
      return
    }
    ElMessage.success(r.message || t('qc.dict.verifiedDone'))
    await load()
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  }
}

async function reseed() {
  try {
    await ElMessageBox.confirm(t('qc.dict.reseedConfirm'), t('qc.dict.reseedTitle'), {
      type: 'warning', confirmButtonText: t('qc.dict.reseed'), cancelButtonText: t('base.cancel')
    })
  } catch { return }
  try {
    const r = await qcAqlApi.reseed()
    if (!r.ok) {
      ElMessage.error(r.message || t('common.reqFail'))
      return
    }
    ElMessage.success(r.message || t('qc.dict.reseedDone'))
    await load()
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  }
}

onMounted(load)
</script>

<style scoped>
</style>
