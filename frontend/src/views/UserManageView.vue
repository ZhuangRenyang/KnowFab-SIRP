<template>
  <div class="page">
    <el-card shadow="never" class="mb">
      <template #header><span>新增用户</span></template>
      <div class="create-row">
        <el-input v-model="nu.username" placeholder="用户名（字母数字，3~32 位）" class="w230" />
        <el-input v-model="nu.displayName" placeholder="姓名" class="w150" />
        <el-input
          v-model="nu.password" type="password" show-password
          placeholder="初始密码（至少 8 位）" class="w190"
        />
        <el-select v-model="nu.role" class="w130">
          <el-option v-for="r in ROLES" :key="r.value" :value="r.value" :label="r.label" />
        </el-select>
        <el-button type="primary" :loading="creating" @click="create">新增用户</el-button>
      </div>
      <div class="tip">角色说明：班组长=看板与报警（只读）；车间主任=能改设备配置；管理员=全部权限。初始密码请当面交给本人并要求首登修改。</div>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>用户列表</span>
          <el-button size="small" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </template>
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column label="用户名" min-width="150">
          <template #default="{ row }">
            <span class="num">{{ row.username }}</span>
            <el-tag v-if="!row.enabled" type="danger" size="small" effect="plain" class="ml6">已停用</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="姓名" min-width="110">
          <template #default="{ row }">{{ row.displayName || '--' }}</template>
        </el-table-column>
        <el-table-column label="角色" width="130">
          <template #default="{ row }">
            <el-select
              :model-value="row.role" size="small"
              @change="(v: string) => update(row, { role: v })"
            >
              <el-option v-for="r in ROLES" :key="r.value" :value="r.value" :label="r.label" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="上次登录" min-width="150" align="right">
          <template #default="{ row }">
            <span class="muted">{{ row.lastLoginAt ? fmtTime(row.lastLoginAt) : '从未登录' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="238" align="right">
          <template #default="{ row }">
            <el-button size="small" @click="resetPwd(row)">重置密码</el-button>
            <el-button size="small" @click="update(row, { enabled: !row.enabled })">
              {{ row.enabled ? '停用' : '启用' }}
            </el-button>
            <el-button size="small" type="danger" plain @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { usersApi, ROLES, type UserRow } from '@/api/users'

const auth = useAuthStore()
const rows = ref<UserRow[]>([])
const loading = ref(false)
const creating = ref(false)
const nu = reactive({ username: '', displayName: '', password: '', role: 'LEADER' })

async function load() {
  loading.value = true
  try {
    rows.value = await usersApi.list()
  } catch (e: any) {
    ElMessage.error(e?.message || '用户列表加载失败')
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function create() {
  if (creating.value) return
  if (!nu.username.trim()) { ElMessage.warning('请输入用户名'); return }
  if (nu.password.length < 8) { ElMessage.warning('初始密码至少 8 位'); return }
  creating.value = true
  try {
    const r = await usersApi.create({
      username: nu.username.trim(),
      displayName: nu.displayName.trim(),
      password: nu.password,
      role: nu.role
    })
    if (r.ok) {
      ElMessage.success(r.message || '已创建')
      nu.username = ''; nu.displayName = ''; nu.password = ''; nu.role = 'LEADER'
      load()
    } else {
      ElMessage.error(r.message || '创建失败')
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '创建失败')
  } finally {
    creating.value = false
  }
}

async function update(row: UserRow, patch: { role?: string; enabled?: boolean }) {
  const r = await usersApi.update(row.id, patch)
  if (!r.ok) ElMessage.error(r.message || '保存失败')
  load()
}

async function resetPwd(row: UserRow) {
  let pwd: string
  try {
    const r = await ElMessageBox.prompt(`给 ${row.username} 设置新密码（至少 8 位）：`, '重置密码', {
      inputType: 'password',
      inputValidator: (v: string) => (v && v.length >= 8) || '至少 8 位',
      confirmButtonText: '重置'
    })
    pwd = r.value
  } catch { return }
  const r = await usersApi.resetPassword(row.id, pwd)
  if (r.ok) ElMessage.success(r.message || '已重置')
  else ElMessage.error(r.message || '重置失败')
}

async function remove(row: UserRow) {
  try {
    await ElMessageBox.confirm(`删除用户 ${row.username}？删了就登不进来了。`, '删除用户', { type: 'warning' })
  } catch { return }
  const r = await usersApi.remove(row.id)
  if (!r.ok) ElMessage.error(r.message || '删除失败')
  load()
}

function fmtTime(t: string): string {
  const d = new Date(t)
  if (isNaN(d.getTime())) return String(t)
  const p2 = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())} ${p2(d.getHours())}:${p2(d.getMinutes())}`
}
</script>

<style scoped>
.mb { margin-bottom: var(--space-4); }
.create-row { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }
.w230 { width: 230px; } .w150 { width: 150px; }
.w190 { width: 190px; } .w130 { width: 130px; }
.tip { margin-top: var(--space-3); font-size: var(--text-xs); color: var(--meta); }
.card-head { display: flex; align-items: center; justify-content: space-between; }
.ml6 { margin-left: 6px; }
.muted { color: var(--muted); font-size: var(--text-xs); }
</style>
