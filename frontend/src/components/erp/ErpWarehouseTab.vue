<template>
  <div>
    <div class="tab-toolbar">
      <el-input v-model="q" :placeholder="t('baseWh.searchPh')" clearable :prefix-icon="Search" class="w250" @keyup.enter="load" @clear="load" />
      <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('base.refresh') }}</el-button>
      <el-button v-if="auth.canWrite" type="primary" @click="openNew">{{ t('baseWh.newBtn') }}</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column :label="t('base.code')" min-width="110">
        <template #default="{ row }"><span class="num">{{ row.code }}</span></template>
      </el-table-column>
      <el-table-column :label="t('base.name')" min-width="140">
        <template #default="{ row }">
          {{ row.name }}
          <el-tag v-if="row.default" type="primary" size="small" effect="light" class="ml6">{{ t('base.defaultTag') }}</el-tag>
          <el-tag v-if="!row.enabled" type="danger" size="small" effect="plain" class="ml6">{{ t('base.disabledTag') }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('base.address')" min-width="150">
        <template #default="{ row }">{{ row.address || '--' }}</template>
      </el-table-column>
      <el-table-column :label="t('baseWh.colManager')" min-width="90">
        <template #default="{ row }">{{ row.manager || '--' }}</template>
      </el-table-column>
      <el-table-column :label="t('base.phone')" min-width="120">
        <template #default="{ row }"><span class="num">{{ row.phone || '--' }}</span></template>
      </el-table-column>
      <el-table-column :label="t('base.action')" width="210" align="right">
        <template #default="{ row }">
          <template v-if="auth.canWrite">
            <el-button size="small" @click="openEdit(row)">{{ t('base.edit') }}</el-button>
            <el-button size="small" @click="toggle(row)">{{ row.enabled ? t('base.disable') : t('base.enable') }}</el-button>
            <el-button size="small" type="danger" plain @click="remove(row)">{{ t('base.del') }}</el-button>
          </template>
          <span v-else class="muted">{{ t('base.readonly') }}</span>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!rows.length && !loading" :description="t('baseWh.empty')" />

    <el-dialog v-model="dlg" :title="editingId ? t('baseWh.titleEdit') : t('baseWh.titleNew')" width="560px" :close-on-click-modal="false">
      <el-form label-width="90px">
        <div class="frow">
          <el-form-item :label="t('base.code')"><el-input v-model="f.code" :disabled="!!editingId" /></el-form-item>
          <el-form-item :label="t('base.name')"><el-input v-model="f.name" /></el-form-item>
        </div>
        <div class="frow">
          <el-form-item :label="t('base.address')"><el-input v-model="f.address" /></el-form-item>
          <el-form-item :label="t('baseWh.fManager')"><el-input v-model="f.manager" /></el-form-item>
        </div>
        <div class="frow">
          <el-form-item :label="t('base.phone')"><el-input v-model="f.phone" /></el-form-item>
          <el-form-item label-width="90px">
            <el-checkbox v-model="f.default">{{ t('baseWh.defaultHint') }}</el-checkbox>
          </el-form-item>
        </div>
        <el-form-item :label="t('base.remark')"><el-input v-model="f.remark" type="textarea" :rows="2" /></el-form-item>
        <el-form-item v-if="editingId" :label="t('base.status')">
          <el-switch v-model="f.enabled" :active-text="t('base.enable')" :inactive-text="t('base.disable')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">{{ t('base.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('base.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { warehouseApi, type ErpWarehouse } from '@/api/erp'

const { t } = useI18n()

const auth = useAuthStore()
const rows = ref<ErpWarehouse[]>([])
const q = ref('')
const loading = ref(false)
const saving = ref(false)
const dlg = ref(false)
const editingId = ref<number | null>(null)

const f = reactive({
  code: '', name: '', address: '', manager: '', phone: '', remark: '', default: false, enabled: true
})

async function load() {
  loading.value = true
  try { rows.value = await warehouseApi.list(q.value) }
  catch (e: any) { ElMessage.error(e?.message || t('base.loadFailed')) }
  finally { loading.value = false }
}
onMounted(load)

function openNew() {
  Object.assign(f, { code: '', name: '', address: '', manager: '', phone: '', remark: '', default: false, enabled: true })
  editingId.value = null
  dlg.value = true
}
function openEdit(row: ErpWarehouse) {
  Object.assign(f, { ...row, remark: row.remark || '' })
  editingId.value = row.id
  dlg.value = true
}

async function save() {
  if (saving.value) return
  if (!f.code.trim()) { ElMessage.warning(t('baseWh.needCode')); return }
  if (!f.name.trim()) { ElMessage.warning(t('baseWh.needName')); return }
  const body = {
    code: f.code.trim(), name: f.name.trim(), address: f.address.trim(),
    manager: f.manager.trim(), phone: f.phone.trim(),
    remark: f.remark, default: f.default, enabled: f.enabled
  }
  saving.value = true
  try {
    const r = editingId.value ? await warehouseApi.update(editingId.value, body) : await warehouseApi.create(body)
    if (r.ok) { ElMessage.success(r.message || t('base.saved')); dlg.value = false; load() }
    else ElMessage.error(r.message || t('base.saveFailed'))
  } catch (e: any) {
    ElMessage.error(e?.message || t('base.saveFailed'))
  } finally {
    saving.value = false
  }
}

async function toggle(row: ErpWarehouse) {
  const r = await warehouseApi.update(row.id, { enabled: !row.enabled })
  if (!r.ok) ElMessage.error(r.message || t('base.opFailed'))
  load()
}

async function remove(row: ErpWarehouse) {
  try {
    await ElMessageBox.confirm(t('baseWh.delConfirm', { name: row.name, code: row.code }), t('baseWh.delTitle'), { type: 'warning' })
  } catch { return }
  const r = await warehouseApi.remove(row.id)
  if (!r.ok) ElMessage.error(r.message || t('base.delFailed'))
  load()
}
</script>

<style scoped>
.tab-toolbar { display: flex; gap: 10px; margin-bottom: var(--space-3); flex-wrap: wrap; }
.w250 { width: 250px; }
.ml6 { margin-left: 6px; }
.muted { color: var(--muted); font-size: var(--text-xs); }
.frow { display: flex; gap: var(--space-4); flex-wrap: wrap; }
.frow .el-form-item { flex: 1; min-width: 200px; }
</style>
