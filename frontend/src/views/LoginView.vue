<template>
  <div class="login-wrap">
    <div class="login-card">
      <div class="logo">知</div>
      <h1>知造 SIRP</h1>
      <p class="sub">登录后查看设备、能耗与报警</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-input
          v-model="username" placeholder="用户名" size="large"
          autocomplete="username" autofocus @keyup.enter="submit"
        />
        <el-input
          v-model="password" type="password" placeholder="密码" size="large"
          autocomplete="current-password" show-password @keyup.enter="submit"
        />
        <el-button type="primary" size="large" :loading="busy" class="submit" @click="submit">
          {{ busy ? '登录中…' : '登 录' }}
        </el-button>
        <div v-if="err" class="err">{{ err }}</div>
      </el-form>
      <div class="hint">忘记密码找管理员重置 · 初始密码在服务器 data/initial-admin.txt</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const busy = ref(false)
const err = ref('')

/* 已登录会话直接回主界面，不发第二次登录请求（PRD §7.2） */
onMounted(async () => {
  if (await auth.ensure()) router.replace('/')
})

async function submit() {
  if (busy.value) return
  if (!username.value.trim() || !password.value) {
    err.value = '请输入用户名和密码'
    return
  }
  busy.value = true
  err.value = ''
  try {
    await auth.login(username.value.trim(), password.value)
    router.replace('/')
  } catch (e: any) {
    /* 网络异常与后端业务错误分开提示（PRD §7.2：429 锁定文案原样透传） */
    err.value = e instanceof TypeError
      ? '连不上服务器，检查服务是否在跑'
      : (e?.message || '登录失败')
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  min-height: 100vh; display: flex; align-items: center; justify-content: center;
  background: var(--bg); padding: var(--space-4);
}
.login-card {
  width: 360px; background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius-xl); box-shadow: var(--elev-modal);
  padding: var(--space-8) var(--space-6); text-align: center;
}
.logo {
  width: 44px; height: 44px; margin: 0 auto var(--space-3);
  border-radius: var(--radius-md); background: var(--accent); color: var(--accent-on);
  display: flex; align-items: center; justify-content: center;
  font-size: var(--text-xl); font-weight: 600;
}
h1 { margin: 0; font-size: var(--text-lg); font-weight: 600; }
.sub { margin: 4px 0 var(--space-6); color: var(--muted); font-size: var(--text-sm); }
.submit { width: 100%; margin-top: var(--space-2); }
.err { margin-top: var(--space-3); color: var(--alarm); font-size: var(--text-sm); }
.hint { margin-top: var(--space-5); color: var(--meta); font-size: var(--text-xs); }
</style>
