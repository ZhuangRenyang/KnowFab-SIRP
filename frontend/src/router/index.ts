import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import ComingSoon from '@/views/ComingSoon.vue'

/* hash 路由（ADR：零后端改动，/app/ 落入 anyRequest().authenticated() 默认保护）
   视图全部懒加载：入口只留壳，ECharts 等重组件随路由 chunk 分割（体积预算 R3） */
export const router = createRouter({
  history: createWebHashHistory('/app/'),
  routes: [
    { path: '/login', component: () => import('@/views/LoginView.vue'), meta: { public: true, title: '登录' } },
    { path: '/', component: () => import('@/views/MonitorView.vue'), meta: { title: '设备监控' } },
    { path: '/alarm', component: () => import('@/views/AlarmView.vue'), meta: { title: '报警' } },
    { path: '/energy', component: ComingSoon, meta: { title: '能耗分析' } },
    { path: '/report', component: () => import('@/views/ReportView.vue'), meta: { title: '报表' } },
    { path: '/digest', component: () => import('@/views/DigestView.vue'), meta: { title: 'AI 早报' } },
    { path: '/system', component: ComingSoon, meta: { title: '系统', roles: ['ADMIN'] } },
    { path: '/users', component: () => import('@/views/UserManageView.vue'), meta: { title: '用户管理', roles: ['ADMIN'] } },
    { path: '/erp/base', component: () => import('@/views/ErpBaseView.vue'), meta: { title: '基础资料' } },
    { path: '/discovery', component: ComingSoon, meta: { title: '设备发现', roles: ['ADMIN'] } },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    if (to.path === '/login' && (await auth.ensure())) return '/'
    return true
  }
  const ok = await auth.ensure()
  if (!ok) return '/login'
  const roles = to.meta.roles as string[] | undefined
  if (roles && !roles.includes(auth.user?.role || '')) return '/'
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title
    ? `${to.meta.title} · 知造 SIRP`
    : '知造 SIRP · AI 智慧工厂管理系统'
})
