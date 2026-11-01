<template>
  <div>
    <div class="tab-toolbar">
      <el-input v-model="q" :placeholder="t('baseSku.searchPh')" clearable :prefix-icon="Search" class="w250" @keyup.enter="load" @clear="load" />
      <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('base.refresh') }}</el-button>
      <el-button v-if="auth.canWrite" type="primary" @click="openNew">{{ t('baseSku.newBtn') }}</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column :label="t('base.code')" min-width="110">
        <template #default="{ row }"><span class="num">{{ row.code }}</span></template>
      </el-table-column>
      <el-table-column :label="t('base.name')" min-width="150">
        <template #default="{ row }">
          {{ row.name }}
          <el-tag v-if="!row.enabled" type="danger" size="small" effect="plain" class="ml6">{{ t('base.disabledTag') }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('base.category')" min-width="100">
        <template #default="{ row }">{{ row.category || '--' }}</template>
      </el-table-column>
      <el-table-column :label="t('base.unit')" width="70">
        <template #default="{ row }">{{ row.unit || '--' }}</template>
      </el-table-column>
      <el-table-column :label="t('base.spec')" min-width="110">
        <template #default="{ row }">{{ row.spec || '--' }}</template>
      </el-table-column>
      <el-table-column :label="t('baseSku.colPurchase')" width="100" align="right">
        <template #default="{ row }"><span class="num">{{ row.purchasePrice ?? '--' }}</span></template>
      </el-table-column>
      <el-table-column :label="t('baseSku.colSale')" width="100" align="right">
        <template #default="{ row }"><span class="num">{{ row.salePrice ?? '--' }}</span></template>
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
    <el-empty v-if="!rows.length && !loading" :description="t('baseSku.empty')" />

    <el-dialog v-model="dlg" :title="editingId ? t('baseSku.titleEdit') : t('baseSku.titleNew')" width="620px" :close-on-click-modal="false">
      <el-form label-width="90px">
        <div class="frow">
          <el-form-item :label="t('base.code')"><el-input v-model="f.code" :disabled="!!editingId" :placeholder="t('baseSku.codePh')" /></el-form-item>
          <el-form-item :label="t('base.name')"><el-input v-model="f.name" /></el-form-item>
        </div>
        <div class="frow">
          <el-form-item :label="t('base.category')"><el-input v-model="f.category" :placeholder="t('baseSku.categoryPh')" /></el-form-item>
          <el-form-item :label="t('base.unit')"><el-input v-model="f.unit" :placeholder="t('baseSku.unitPh')" /></el-form-item>
        </div>
        <div class="frow">
          <el-form-item :label="t('base.spec')"><el-input v-model="f.spec" /></el-form-item>
          <el-form-item :label="t('base.barcode')"><el-input v-model="f.barcode" /></el-form-item>
        </div>
        <div class="frow">
          <el-form-item :label="t('baseSku.fPurchase')"><el-input-number v-model="f.purchasePrice" :precision="4" :min="0" :controls="false" /></el-form-item>
          <el-form-item :label="t('baseSku.fSale')"><el-input-number v-model="f.salePrice" :precision="4" :min="0" :controls="false" /></el-form-item>
        </div>
        <div class="frow">
          <el-form-item :label="t('baseSku.fMin')"><el-input-number v-model="f.minStock" :precision="4" :min="0" :controls="false" /></el-form-item>
          <el-form-item :label="t('baseSku.fMax')"><el-input-number v-model="f.maxStock" :precision="4" :min="0" :controls="false" /></el-form-item>
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
import { skuApi, type ErpSku } from '@/api/erp'

const { t } = useI18n()

const auth = useAuthStore()
const rows = ref<ErpSku[]>([])
const q = ref('')
const loading = ref(false)
const saving = ref(false)
const dlg = ref(false)
const editingId = ref<number | null>(null)

const f = reactive({
  code: '', name: '', category: '', unit: '', spec: '', barcode: '',
  purchasePrice: undefined as number | undefined, salePrice: undefined as number | undefined,
  minStock: undefined as number | undefined, maxStock: undefined as number | undefined,
  remark: '', enabled: true
})

async function load() {
  loading.value = true
  try { rows.value = await skuApi.list(q.value) }
  catch (e: any) { ElMessage.error(e?.message || t('base.loadFailed')) }
  finally { loading.value = false }
}
onMounted(load)

function resetForm() {
  Object.assign(f, {
    code: '', name: '', category: '', unit: '', spec: '', barcode: '',
    purchasePrice: undefined, salePrice: undefined, minStock: undefined, maxStock: undefined,
    remark: '', enabled: true
  })
}

function openNew() { resetForm(); editingId.value = null; dlg.value = true }
function openEdit(row: ErpSku) {
  resetForm()
  editingId.value = row.id
  Object.assign(f, { ...row, remark: row.remark || '' })
  dlg.value = true
}

async function save() {
  if (saving.value) return
  if (!f.code.trim()) { ElMessage.warning(t('baseSku.needCode')); return }
  if (!f.name.trim()) { ElMessage.warning(t('baseSku.needName')); return }
  const body = {
    code: f.code.trim(), name: f.name.trim(), category: f.category.trim(), unit: f.unit.trim(),
    spec: f.spec.trim(), barcode: f.barcode.trim(),
    purchasePrice: f.purchasePrice ?? null, salePrice: f.salePrice ?? null,
    minStock: f.minStock ?? null, maxStock: f.maxStock ?? null,
    remark: f.remark, enabled: f.enabled
  }
  saving.value = true
  try {
    const r = editingId.value ? await skuApi.update(editingId.value, body) : await skuApi.create(body)
    if (r.ok) { ElMessage.success(r.message || t('base.saved')); dlg.value = false; load() }
    else ElMessage.error(r.message || t('base.saveFailed'))
  } catch (e: any) {
    ElMessage.error(e?.message || t('base.saveFailed'))
  } finally {
    saving.value = false
  }
}

async function toggle(row: ErpSku) {
  const r = await skuApi.update(row.id, { enabled: !row.enabled })
  if (!r.ok) ElMessage.error(r.message || t('base.opFailed'))
  load()
}

async function remove(row: ErpSku) {
  try {
    await ElMessageBox.confirm(t('baseSku.delConfirm', { name: row.name, code: row.code }), t('baseSku.delTitle'), { type: 'warning' })
  } catch { return }
  const r = await skuApi.remove(row.id)
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
.frow .el-form-item { flex: 1; min-width: 220px; }
</style>
