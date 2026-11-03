<template>
  <div>
    <div class="tab-toolbar">
      <el-input
        v-model="kw"
        :placeholder="t('qc.dict.searchPh')"
        clearable
        :prefix-icon="Search"
        class="w200"
        @keyup.enter="load"
        @clear="load"
      />
      <el-select v-model="sevFilter" clearable :placeholder="t('qc.dict.filterSeverity')" class="w120" @change="load">
        <el-option v-for="s in SEVERITIES" :key="s" :value="s" :label="t('qc.severity.' + s)" />
      </el-select>
      <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('base.refresh') }}</el-button>
      <div class="grow" />
      <el-button v-if="auth.canWrite" type="primary" :icon="Plus" @click="openCode()">
        {{ t('qc.dict.newCode') }}
      </el-button>
    </div>

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

    <el-table :data="codes" stripe v-loading="loading" :empty-text="t('qc.dict.codeEmpty')">
      <el-table-column :label="t('qc.dict.colCode')" width="120">
        <template #default="{ row }"><span class="num">{{ row.code }}</span></template>
      </el-table-column>
      <el-table-column :label="t('qc.dict.colName')" min-width="140">
        <template #default="{ row }">{{ row.name }}</template>
      </el-table-column>
      <el-table-column :label="t('qc.dict.colSeverity')" width="90">
        <template #default="{ row }">
          <el-tag size="small" effect="light" :type="gradeType(row.severity)">
            {{ t('qc.severity.' + row.severity) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.dict.colCategory')" min-width="110">
        <template #default="{ row }">{{ row.category || '--' }}</template>
      </el-table-column>
      <el-table-column :label="t('qc.dict.colAql')" width="88" align="right">
        <template #default="{ row }">
          <span class="num">{{ row.defaultAql == null ? '--' : row.defaultAql + '%' }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.dict.colState')" width="86">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" :type="row.enabled ? 'success' : 'info'">
            {{ row.enabled ? t('common.enable') : t('common.disable') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('base.action')" width="128" align="right">
        <template #default="{ row }">
          <el-button size="small" @click="openCode(row)">{{ t('base.edit') }}</el-button>
          <el-button
            v-if="auth.canWrite"
            size="small"
            type="danger"
            plain
            @click="removeCode(row)"
          >{{ t('base.del') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 缺陷代码编辑 -->
    <el-dialog v-model="codeDlg" :title="codeForm.id ? t('qc.dict.editCode') : t('qc.dict.newCode')" width="520px">
      <el-form label-width="88px">
        <el-form-item :label="t('qc.dict.colCode')" required>
          <el-input v-model="codeForm.code" maxlength="32" :disabled="!!codeForm.id" :placeholder="t('qc.dict.codePh')" />
          <div class="field-hint">{{ t('qc.dict.codeHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('qc.dict.colName')" required>
          <el-input v-model="codeForm.name" maxlength="128" :placeholder="t('qc.dict.namePh')" />
        </el-form-item>
        <el-form-item :label="t('qc.dict.colSeverity')" required>
          <el-radio-group v-model="codeForm.severity">
            <el-radio-button v-for="s in SEVERITIES" :key="s" :value="s">
              {{ t('qc.severity.' + s) }}
            </el-radio-button>
          </el-radio-group>
          <div class="field-hint">{{ t('qc.dict.severityHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('qc.dict.colCategory')">
          <el-input v-model="codeForm.category" maxlength="64" :placeholder="t('qc.dict.categoryPh')" />
        </el-form-item>
        <el-form-item :label="t('qc.dict.colAql')">
          <el-input-number v-model="codeForm.defaultAql" :min="0" :max="10" :precision="2" controls-position="right" />
          <div class="field-hint">{{ t('qc.dict.aqlHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('qc.dict.colState')">
          <el-switch v-model="codeForm.enabled" />
        </el-form-item>
        <el-form-item :label="t('base.remark')">
          <el-input v-model="codeForm.remark" type="textarea" :rows="2" :placeholder="t('qc.dict.remarkPh')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="codeDlg = false">{{ t('base.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="saveCode">{{ t('base.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import {
  gradeType, qcDefectCodeApi, SEVERITIES, type QcDefectCode
} from '@/api/qc'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const auth = useAuthStore()

const codes = ref<QcDefectCode[]>([])
const loading = ref(false)
const errMsg = ref('')
const kw = ref('')
const sevFilter = ref('')
const saving = ref(false)
const codeDlg = ref(false)

const codeForm = reactive({
  id: 0, code: '', name: '', severity: 'MA' as string, category: '',
  defaultAql: null as number | null, remark: '', enabled: true
})

async function load() {
  loading.value = true
  errMsg.value = ''
  try {
    const r = await qcDefectCodeApi.list({
      q: kw.value, severity: sevFilter.value || undefined
    })
    codes.value = r.rows || []
  } catch (e) {
    errMsg.value = (e as Error)?.message || t('common.reqFail')
    codes.value = []
  } finally {
    loading.value = false
  }
}

function openCode(row?: QcDefectCode) {
  Object.assign(codeForm, row
    ? { ...row, category: row.category || '', remark: row.remark || '' }
    : { id: 0, code: '', name: '', severity: 'MA', category: '', defaultAql: null, remark: '', enabled: true })
  codeDlg.value = true
}

async function saveCode() {
  if (!codeForm.code.trim() || !codeForm.name.trim()) {
    ElMessage.warning(t('qc.dict.needCodeName'))
    return
  }
  saving.value = true
  try {
    const body = {
      code: codeForm.code.trim(),
      name: codeForm.name.trim(),
      severity: codeForm.severity as 'CR' | 'MA' | 'MI',
      category: codeForm.category.trim() || null,
      defaultAql: codeForm.defaultAql,
      remark: codeForm.remark.trim() || null,
      sortNo: 0,
      enabled: codeForm.enabled
    }
    const r = codeForm.id
      ? await qcDefectCodeApi.update(codeForm.id, body)
      : await qcDefectCodeApi.create(body)
    if (!r.ok) {
      ElMessage.error(r.message || t('common.reqFail'))
      return
    }
    ElMessage.success(r.message || t('common.saveOk'))
    codeDlg.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  } finally {
    saving.value = false
  }
}

async function removeCode(row: QcDefectCode) {
  try {
    await ElMessageBox.confirm(t('qc.dict.delCodeConfirm', { name: row.name }), t('qc.dict.delCodeTitle'), {
      type: 'warning', confirmButtonText: t('base.del'), cancelButtonText: t('base.cancel')
    })
  } catch { return }
  try {
    const r = await qcDefectCodeApi.remove(row.id)
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

onMounted(load)
</script>

<style scoped>
</style>
