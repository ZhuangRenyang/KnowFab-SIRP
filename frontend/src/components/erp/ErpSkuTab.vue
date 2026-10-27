<template>
  <div>
    <div class="tab-toolbar">
      <el-input v-model="q" placeholder="搜编码 / 名称 / 类别 / 规格" clearable :prefix-icon="Search" class="w250" @keyup.enter="load" @clear="load" />
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button v-if="auth.canWrite" type="primary" @click="openNew">新增商品</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column label="编码" min-width="110">
        <template #default="{ row }"><span class="num">{{ row.code }}</span></template>
      </el-table-column>
      <el-table-column label="名称" min-width="150">
        <template #default="{ row }">
          {{ row.name }}
          <el-tag v-if="!row.enabled" type="danger" size="small" effect="plain" class="ml6">已停用</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="类别" min-width="100">
        <template #default="{ row }">{{ row.category || '--' }}</template>
      </el-table-column>
      <el-table-column label="单位" width="70">
        <template #default="{ row }">{{ row.unit || '--' }}</template>
      </el-table-column>
      <el-table-column label="规格" min-width="110">
        <template #default="{ row }">{{ row.spec || '--' }}</template>
      </el-table-column>
      <el-table-column label="采购价" width="100" align="right">
        <template #default="{ row }"><span class="num">{{ row.purchasePrice ?? '--' }}</span></template>
      </el-table-column>
      <el-table-column label="销售价" width="100" align="right">
        <template #default="{ row }"><span class="num">{{ row.salePrice ?? '--' }}</span></template>
      </el-table-column>
      <el-table-column label="操作" width="210" align="right">
        <template #default="{ row }">
          <template v-if="auth.canWrite">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
            <el-button size="small" type="danger" plain @click="remove(row)">删除</el-button>
          </template>
          <span v-else class="muted">只读</span>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!rows.length && !loading" description="还没有商品，点「新增商品」建立第一条主数据" />

    <el-dialog v-model="dlg" :title="editingId ? '编辑商品' : '新增商品'" width="620px" :close-on-click-modal="false">
      <el-form label-width="90px">
        <div class="frow">
          <el-form-item label="编码"><el-input v-model="f.code" :disabled="!!editingId" placeholder="唯一，如 P-001" /></el-form-item>
          <el-form-item label="名称"><el-input v-model="f.name" /></el-form-item>
        </div>
        <div class="frow">
          <el-form-item label="类别"><el-input v-model="f.category" placeholder="如 成品 / 原材料" /></el-form-item>
          <el-form-item label="单位"><el-input v-model="f.unit" placeholder="件 / kg" /></el-form-item>
        </div>
        <div class="frow">
          <el-form-item label="规格"><el-input v-model="f.spec" /></el-form-item>
          <el-form-item label="条码"><el-input v-model="f.barcode" /></el-form-item>
        </div>
        <div class="frow">
          <el-form-item label="采购价"><el-input-number v-model="f.purchasePrice" :precision="4" :min="0" :controls="false" /></el-form-item>
          <el-form-item label="销售价"><el-input-number v-model="f.salePrice" :precision="4" :min="0" :controls="false" /></el-form-item>
        </div>
        <div class="frow">
          <el-form-item label="库存下限"><el-input-number v-model="f.minStock" :precision="4" :min="0" :controls="false" /></el-form-item>
          <el-form-item label="库存上限"><el-input-number v-model="f.maxStock" :precision="4" :min="0" :controls="false" /></el-form-item>
        </div>
        <el-form-item label="备注"><el-input v-model="f.remark" type="textarea" :rows="2" /></el-form-item>
        <el-form-item v-if="editingId" label="状态">
          <el-switch v-model="f.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { skuApi, type ErpSku } from '@/api/erp'

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
  catch (e: any) { ElMessage.error(e?.message || '加载失败') }
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
  if (!f.code.trim()) { ElMessage.warning('商品编码必填'); return }
  if (!f.name.trim()) { ElMessage.warning('商品名称必填'); return }
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
    if (r.ok) { ElMessage.success(r.message || '已保存'); dlg.value = false; load() }
    else ElMessage.error(r.message || '保存失败')
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggle(row: ErpSku) {
  const r = await skuApi.update(row.id, { enabled: !row.enabled })
  if (!r.ok) ElMessage.error(r.message || '操作失败')
  load()
}

async function remove(row: ErpSku) {
  try {
    await ElMessageBox.confirm(`删除商品「${row.name}（${row.code}）」？`, '删除商品', { type: 'warning' })
  } catch { return }
  const r = await skuApi.remove(row.id)
  if (!r.ok) ElMessage.error(r.message || '删除失败')
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
