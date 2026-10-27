<template>
  <el-dialog v-model="visible" title="账号与密码" width="420px" :append-to-body="true">
    <div class="who">
      <div><span class="lbl">当前用户</span><b>{{ auth.displayName }}</b></div>
      <div><span class="lbl">角色</span>{{ auth.roleLabel }}</div>
      <div><span class="lbl">用户名</span><span class="num">{{ auth.user?.username || '--' }}</span></div>
    </div>
    <el-divider />
    <el-form label-width="90px" @submit.prevent>
      <el-form-item label="原密码">
        <el-input v-model="oldP" type="password" show-password autocomplete="current-password" />
      </el-form-item>
      <el-form-item label="新密码">
        <el-input v-model="newP" type="password" show-password autocomplete="new-password" placeholder="至少 8 位" />
      </el-form-item>
      <el-form-item label="再输一次">
        <el-input v-model="newP2" type="password" show-password autocomplete="new-password" />
      </el-form-item>
    </el-form>
    <div v-if="msg" class="msg" :class="{ bad: !okFlag }">{{ msg }}</div>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button type="primary" :loading="busy" @click="submit">修改密码</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const visible = defineModel<boolean>({ default: false })
const auth = useAuthStore()

const oldP = ref('')
const newP = ref('')
const newP2 = ref('')
const msg = ref('')
const okFlag = ref(false)
const busy = ref(false)

watch(visible, (v) => { if (v) { oldP.value = ''; newP.value = ''; newP2.value = ''; msg.value = '' } })

const canSubmit = computed(() => oldP.value && newP.value.length >= 8 && newP.value === newP2.value)

async function submit() {
  if (busy.value) return
  if (!oldP.value) { msg.value = '请输入原密码'; okFlag.value = false; return }
  if (newP.value.length < 8) { msg.value = '新密码至少 8 位'; okFlag.value = false; return }
  if (newP.value !== newP2.value) { msg.value = '两次输入的新密码不一致'; okFlag.value = false; return }
  busy.value = true
  try {
    const r = await auth.changePassword(oldP.value, newP.value)
    okFlag.value = !!r.ok
    msg.value = r.message || (r.ok ? '修改成功' : '修改失败')
    if (r.ok) ElMessage.success('密码修改成功')
    if (r.ok) { oldP.value = ''; newP.value = ''; newP2.value = '' }
  } catch (e: any) {
    okFlag.value = false
    msg.value = e?.message || '修改失败'
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.who div { display: flex; gap: 10px; margin: 4px 0; font-size: var(--text-sm); }
.who .lbl { width: 60px; color: var(--muted); }
.msg { margin-top: 4px; font-size: var(--text-sm); color: var(--run); }
.msg.bad { color: var(--alarm); }
</style>
