import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElMessage } from 'element-plus'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/tokens.css'
/* 显式 import 的命令式组件需要手动补样式（unplugin 只处理模板里的组件） */
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import App from './App.vue'
import { i18n } from './i18n'
import { router } from './router'
import { useAuthStore } from './stores/auth'
import { useThemeStore } from './stores/theme'
import { useWsStore } from './stores/ws'
import { setUnauthorizedHandler } from './api/http'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(i18n)

/* 主题首帧应用（防闪烁脚本已在 index.html 抢先执行，这里同步 store 状态） */
const theme = useThemeStore(pinia)
theme.init()

const auth = useAuthStore(pinia)
const ws = useWsStore(pinia)

/* 唯一的 401 出口：清登录态、断开 WS、回登录页（PRD 风险 6） */
setUnauthorizedHandler(() => {
  auth.user = null
  auth.ready = false
  ws.close()
  ElMessage.warning(i18n.global.t('login.expired'))
  if (router.currentRoute.value.path !== '/login') router.push('/login')
})

app.use(router)
app.mount('#app')
