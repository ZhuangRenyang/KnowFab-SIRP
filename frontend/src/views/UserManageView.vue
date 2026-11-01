<template>
  <div class="page">
    <el-card shadow="never" class="mb">
      <template #header><span>{{ t('user.addTitle') }}</span></template>
      <div class="create-row">
        <el-input v-model="nu.username" :placeholder="t('user.usernamePh')" class="w230" />
        <el-input v-model="nu.displayName" :placeholder="t('user.displayNamePh')" class="w150" />
        <el-input
          v-model="nu.password" type="password" show-password
          :placeholder="t('user.passwordPh')" class="w190"
        />
        <el-select v-model="nu.role" class="w130">
          <el-option v-for="r in ROLES" :key="r.value" :value="r.value" :label="r.label" />
        </el-select>
        <el-button type="primary" :loading="creating" @click="create">{{ t('user.create') }}</el-button>
      </div>
      <div class="tip">{{ t('user.roleTip') }}</div>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>{{ t('user.listTitle') }}</span>
          <el-button size="small" :icon="Refresh" :loading="loading" @click="load">{{ t('common.refresh') }}</el-button>
        </div>
      </template>
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column :label="t('user.colUsername')" min-width="150">
          <template #default="{ row }">
            <span class="num">{{ row.username }}</span>
            <el-tag v-if="!row.enabled" type="danger" size="small" effect="plain" class="ml6">{{ t('user.disabled') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('user.colName')" min-width="110">
          <template #default="{ row }">{{ row.displayName || '--' }}</template>
        </el-table-column>
        <el-table-column :label="t('user.colRole')" width="130">
          <template #default="{ row }">
            <el-select
              :model-value="row.role" size="small"
              @change="(v: string) => update(row, { role: v })"
            >
              <el-option v-for="r in ROLES" :key="r.value" :value="r.value" :label="r.label" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column :label="t('user.colLastLogin')" min-width="150" align="right">
          <template #default="{ row }">
            <span class="muted">{{ row.lastLoginAt ? fmtTime(row.lastLoginAt) : t('user.never') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('user.colActions')" width="238" align="right">
          <template #default="{ row }">
            <el-button size="small" @click="resetPwd(row)">{{ t('user.resetPwd') }}</el-button>
            <el-button size="small" @click="update(row, { enabled: !row.enabled })">
              {{ row.enabled ? t('user.disable') : t('user.enable') }}
            </el-button>
            <el-button size="small" type="danger" plain @click="remove(row)">{{ t('user.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
const { t } = useI18n()
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
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
    ElMessage.error(e?.message || t('user.loadFailed'))
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function create() {
  if (creating.value) return
  if (!nu.username.trim()) { ElMessage.warning(t('user.needUsername')); return }
  if (nu.password.length < 8) { ElMessage.warning(t('user.pwdTooShort')); return }
  creating.value = true
  try {
    const r = await usersApi.create({
      username: nu.username.trim(),
      displayName: nu.displayName.trim(),
      password: nu.password,
      role: nu.role
    })
    if (r.ok) {
      ElMessage.success(r.message || t('user.created'))
      nu.username = ''; nu.displayName = ''; nu.password = ''; nu.role = 'LEADER'
      load()
    } else {
      ElMessage.error(r.message || t('user.createFailed'))
    }
  } catch (e: any) {
    ElMessage.error(e?.message || t('user.createFailed'))
  } finally {
    creating.value = false
  }
}

async function update(row: UserRow, patch: { role?: string; enabled?: boolean }) {
  const r = await usersApi.update(row.id, patch)
  if (!r.ok) ElMessage.error(r.message || t('user.saveFailed'))
  load()
}

async function resetPwd(row: UserRow) {
  let pwd: string
  try {
    const r = await ElMessageBox.prompt(t('user.resetPrompt', { user: row.username }), t('user.resetTitle'), {
      inputType: 'password',
      inputValidator: (v: string) => (v && v.length >= 8) || t('user.atLeast8'),
      confirmButtonText: t('user.resetBtn')
    })
    pwd = r.value
  } catch { return }
  const r = await usersApi.resetPassword(row.id, pwd)
  if (r.ok) ElMessage.success(r.message || t('user.resetOk'))
  else ElMessage.error(r.message || t('user.resetFailed'))
}

async function remove(row: UserRow) {
  try {
    await ElMessageBox.confirm(t('user.delConfirm', { user: row.username }), t('user.delTitle'), { type: 'warning' })
  } catch { return }
  const r = await usersApi.remove(row.id)
  if (!r.ok) ElMessage.error(r.message || t('user.delFailed'))
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
