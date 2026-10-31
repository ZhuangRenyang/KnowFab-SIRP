<template>
  <div class="login-wrap">
    <!-- 登录页自带语言切换：侧边栏的切换器要登录后才可见 -->
    <el-dropdown class="lang" trigger="click" @command="onLang">
      <span class="lang-btn" :title="t('common.language')">{{ langShort }}</span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item v-for="l in LOCALES" :key="l.code" :command="l.code"
            :disabled="l.code === currentLocale">{{ l.label }}</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>

    <div class="login-card">
      <div class="logo">知</div>
      <h1>{{ t('login.title') }}</h1>
      <p class="sub">{{ t('login.sub') }}</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-input
          v-model="username" :placeholder="t('login.username')" size="large"
          autocomplete="username" autofocus @keyup.enter="submit"
        />
        <el-input
          v-model="password" type="password" :placeholder="t('login.password')" size="large"
          autocomplete="current-password" show-password @keyup.enter="submit"
        />
        <el-button type="primary" size="large" :loading="busy" class="submit" @click="submit">
          {{ busy ? t('login.submitting') : t('login.submit') }}
        </el-button>
        <div v-if="err" class="err">{{ err }}</div>
      </el-form>
      <div class="hint">{{ t('login.hint') }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { LOCALES, currentLocale, setLocale } from '@/i18n'

const router = useRouter()
const auth = useAuthStore()
const { t } = useI18n()

const username = ref('')
const password = ref('')
const busy = ref(false)
const err = ref('')

const langShort = computed(() => currentLocale.value.split('-')[0].toUpperCase())
function onLang(code: string) {
  setLocale(code)
  /* 切换后清掉上一语言的错误提示，否则中英混排 */
  err.value = ''
}

/* 已登录会话直接回主界面，不发第二次登录请求（PRD §7.2） */
onMounted(async () => {
  if (await auth.ensure()) router.replace('/')
})

async function submit() {
  if (busy.value) return
  if (!username.value.trim() || !password.value) {
    err.value = t('login.needInput')
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
      ? t('login.networkError')
      : (e?.message || t('login.failed'))
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  position: relative;
  min-height: 100vh; display: flex; align-items: center; justify-content: center;
  background: var(--bg); padding: var(--space-4);
}
.lang { position: absolute; top: 18px; right: 22px; }
.lang-btn {
  font-size: var(--text-xs); color: var(--muted); cursor: pointer; user-select: none;
  padding: 4px 9px; border-radius: 6px; letter-spacing: .5px;
  transition: color var(--motion-fast) var(--ease-standard), background var(--motion-fast) var(--ease-standard);
}
.lang-btn:hover { color: var(--fg); background: var(--hover); }
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
