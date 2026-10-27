<template>
  <div>
    <div class="tab-toolbar">
      <el-input v-model="q" placeholder="搜编码 / 名称 / 联系人 / 电话" clearable :prefix-icon="Search" class="w250" @keyup.enter="load" @clear="load" />
      <el-select v-model="type" class="w130" @change="load">
        <el-option value="ALL" label="全部类型" />
        <el-option v-for="t in PARTNER_TYPES" :key="t.value" :value="t.value" :label="t.label" />
      </el-select>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button v-if="auth.canWrite" type="primary" @click="openNew">新增单位</el-button>
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
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag :type="row.type === 'SUPPLIER' ? 'warning' : row.type === 'CUSTOMER' ? 'primary' : 'info'" size="small" effect="light">
            {{ partnerTypeLabel(row.type) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="联系人" min-width="90">
        <template #default="{ row }">{{ row.contact || '--' }}</template>
      </el-table-column>
      <el-table-column label="电话" min-width="120">
        <template #default="{ row }"><span class="num">{{ row.phone || '--' }}</span></template>
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
    <el-empty v-if="!rows.length && !loading" description="还没有往来单位（供应商 / 客户）" />

    <el-dialog v-model="dlg" :title="editingId ? '编辑单位' : '新增单位'" width="560px" :close-on-click-modal="false">
      <el-form label-width="90px">
        <div class="frow">
          <el-form-item label="编码"><el-input v-model="f.code" :disabled="!!editingId" /></el-form-item>
          <el-form-item label="名称"><el-input v-model="f.name" /></el-form-item>
        </div>
        <div class="frow">
          <el-form-item label="类型">
            <el-select v-model="f.type">
              <el-option v-for="t in PARTNER_TYPES" :key="t.value" :value="t.value" :label="t.label" />
            </el-select>
          </el-form-item>
          <el-form-item label="联系人"><el-input v-model="f.contact" /></el-form-item>
        </div>
        <div class="frow">
          <el-form-item label="电话"><el-input v-model="f.phone" /></el-form-item>
          <el-form-item label="地址"><el-input v-model="f.address" /></el-form-item>
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
import { partnerApi, PARTNER_TYPES, partnerTypeLabel, type ErpPartner } from '@/api/erp'

const auth = useAuthStore()
const rows = ref<ErpPartner[]>([])
const q = ref('')
const type = ref('ALL')
const loading = ref(false)
const saving = ref(false)
const dlg = ref(false)
const editingId = ref<number | null>(null)

const f = reactive({
  code: '', name: '', type: 'SUPPLIER', contact: '', phone: '', address: '', remark: '', enabled: true
})

async function load() {
  loading.value = true
  try { rows.value = await partnerApi.list(q.value, type.value) }
  catch (e: any) { ElMessage.error(e?.message || '加载失败') }
  finally { loading.value = false }
}
onMounted(load)

function openNew() {
  Object.assign(f, { code: '', name: '', type: 'SUPPLIER', contact: '', phone: '', address: '', remark: '', enabled: true })
  editingId.value = null
  dlg.value = true
}
function openEdit(row: ErpPartner) {
  Object.assign(f, { ...row, remark: row.remark || '' })
  editingId.value = row.id
  dlg.value = true
}

async function save() {
  if (saving.value) return
  if (!f.code.trim()) { ElMessage.warning('单位编码必填'); return }
  if (!f.name.trim()) { ElMessage.warning('单位名称必填'); return }
  const body = {
    code: f.code.trim(), name: f.name.trim(), type: f.type,
    contact: f.contact.trim(), phone: f.phone.trim(), address: f.address.trim(),
    remark: f.remark, enabled: f.enabled
  }
  saving.value = true
  try {
    const r = editingId.value ? await partnerApi.update(editingId.value, body) : await partnerApi.create(body)
    if (r.ok) { ElMessage.success(r.message || '已保存'); dlg.value = false; load() }
    else ElMessage.error(r.message || '保存失败')
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggle(row: ErpPartner) {
  const r = await partnerApi.update(row.id, { enabled: !row.enabled })
  if (!r.ok) ElMessage.error(r.message || '操作失败')
  load()
}

async function remove(row: ErpPartner) {
  try {
    await ElMessageBox.confirm(`删除往来单位「${row.name}（${row.code}）」？`, '删除单位', { type: 'warning' })
  } catch { return }
  const r = await partnerApi.remove(row.id)
  if (!r.ok) ElMessage.error(r.message || '删除失败')
  load()
}
</script>

<style scoped>
.tab-toolbar { display: flex; gap: 10px; margin-bottom: var(--space-3); flex-wrap: wrap; }
.w250 { width: 250px; }
.w130 { width: 130px; }
.ml6 { margin-left: 6px; }
.muted { color: var(--muted); font-size: var(--text-xs); }
.frow { display: flex; gap: var(--space-4); flex-wrap: wrap; }
.frow .el-form-item { flex: 1; min-width: 200px; }
</style>
