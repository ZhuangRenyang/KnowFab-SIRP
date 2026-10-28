import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/* hash 路由（ADR：零后端改动，/app/ 落入 anyRequest().authenticated() 默认保护）
   视图全部懒加载：入口只留壳，ECharts 等重组件随路由 chunk 分割（体积预算 R3） */
export const router = createRouter({
  history: createWebHashHistory('/app/'),
  routes: [
    { path: '/login', component: () => import('@/views/LoginView.vue'), meta: { public: true, title: '登录' } },
    { path: '/', component: () => import('@/views/MonitorView.vue'), meta: { title: '设备监控' } },
    { path: '/alarm', component: () => import('@/views/AlarmView.vue'), meta: { title: '报警' } },
    { path: '/energy', component: () => import('@/views/EnergyView.vue'), meta: { title: '能耗分析' } },
    { path: '/report', component: () => import('@/views/ReportView.vue'), meta: { title: '报表' } },
    { path: '/digest', component: () => import('@/views/DigestView.vue'), meta: { title: 'AI 早报' } },
    { path: '/ai/chat', component: () => import('@/views/AiChatView.vue'), meta: { title: 'AI 助手' } },
    { path: '/system', component: () => import('@/views/SystemView.vue'), meta: { title: '系统', roles: ['ADMIN'] } },
    { path: '/users', component: () => import('@/views/UserManageView.vue'), meta: { title: '用户管理', roles: ['ADMIN'] } },
    { path: '/erp/lines', component: () => import('@/views/ErpLineView.vue'), meta: { title: '产线管理' } },
    { path: '/erp/base', component: () => import('@/views/ErpBaseView.vue'), meta: { title: '基础资料' } },
    { path: '/erp/purchase', component: () => import('@/views/ErpPurchaseView.vue'), meta: { title: '采购管理' } },
    { path: '/erp/sales', component: () => import('@/views/ErpSalesView.vue'), meta: { title: '销售管理' } },
    { path: '/erp/stock', component: () => import('@/views/ErpStockView.vue'), meta: { title: '库存管理' } },
    { path: '/erp/finance', component: () => import('@/views/ErpFinanceView.vue'), meta: { title: '财务管理' } },
    { path: '/erp/reports', component: () => import('@/views/ErpReportView.vue'), meta: { title: '经营报表' } },
    // 销售角色唯一可进的页面
    { path: '/erp/sales-query', component: () => import('@/views/SalesQueryView.vue'),
      meta: { title: '销售查询', roles: ['ADMIN', 'MANAGER', 'LEADER', 'SALES'] } },
    { path: '/discovery', component: () => import('@/views/DiscoveryView.vue'), meta: { title: '设备发现', roles: ['ADMIN'] } },
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
  // 销售角色只有「销售查询」一个页面：别的路径一律送回去，避免点进来看到 403 空页
  // 销售角色只有两页：销售查询 + AI 助手（AI 侧工具已按角色收口）
  if (auth.isSales && to.path !== '/erp/sales-query' && to.path !== '/ai/chat') return '/erp/sales-query'
  const roles = to.meta.roles as string[] | undefined
  if (roles && !roles.includes(auth.user?.role || '')) return auth.isSales ? '/erp/sales-query' : '/'
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title
    ? `${to.meta.title} · 知造 SIRP`
    : '知造 SIRP · AI 智慧工厂管理系统'
})
