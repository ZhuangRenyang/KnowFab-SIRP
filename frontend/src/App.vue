<template>
  <el-config-provider :locale="zhCn">
    <router-view v-if="isLogin" />
    <el-container v-else class="shell">
    <el-aside :width="collapsed ? '56px' : '212px'" class="aside">
      <div class="brand">
        <div class="brand-mark">知</div>
        <div v-if="!collapsed" class="brand-text"><b>知造 SIRP</b><span>AI 智慧工厂</span></div>
      </div>

      <nav class="nav">
        <template v-for="g in groups" :key="g.title">
          <div v-if="!collapsed" class="group-label">{{ g.title }}</div>
          <template v-for="it in g.items" :key="g.title + it.label">
            <router-link v-if="!it.disabled" v-slot="{ navigate, isActive }" :to="it.path" custom>
              <div class="nav-item" :class="{ active: isActive }" @click="navigate()">
                <el-icon :size="18"><component :is="it.icon" /></el-icon>
                <span v-if="!collapsed">{{ it.label }}</span>
              </div>
            </router-link>
            <el-tooltip v-else :content="it.tip || it.label" placement="right" :disabled="!collapsed">
              <div class="nav-item disabled">
                <el-icon :size="18"><component :is="it.icon" /></el-icon>
                <span v-if="!collapsed">{{ it.label }}</span>
              </div>
            </el-tooltip>
          </template>
        </template>
      </nav>

      <div class="foot">
        <!-- 收缩态只留图标：56px 宽度塞不下文字链接，会把主题/收起两个图标挤出容器外 -->
        <a v-if="!collapsed" class="foot-link" href="/screen.html" target="_blank" title="打开车间大屏（不需要登录）">大屏</a>
        <el-icon class="foot-icon" :title="theme.light ? '切换深色' : '切换浅色'" @click="theme.toggle()">
          <Moon v-if="theme.light" /><Sunny v-else />
        </el-icon>
        <el-icon class="foot-icon" :title="collapsed ? '展开菜单' : '收起菜单'" @click="toggleCollapsed">
          <Expand v-if="collapsed" /><Fold v-else />
        </el-icon>
      </div>
    </el-aside>

    <el-container class="body-col">
      <el-header class="hd" height="52px">
        <div class="hd-title">知造 SIRP <span>AI 智慧工厂管理系统</span></div>
        <div v-if="!auth.isSales" class="hd-stats num">
          <span>设备 <b>{{ devices.stats.total }}</b></span>
          <span>在线 <b class="c-run">{{ devices.stats.online }}</b></span>
          <span>运行 <b class="c-run">{{ devices.stats.running }}</b></span>
          <span>报警 <b class="c-alarm">{{ devices.stats.alarm }}</b></span>
        </div>
        <div class="hd-right">
          <span v-if="!auth.isSales" class="ws-pill" :class="ws.connected ? 'on' : 'off'">
            <i class="dot" />{{ ws.connected ? '实时连接' : '已断开，重连中' }}
          </span>
          <el-badge v-if="!auth.isSales" :value="alarmsOpen > 99 ? '99+' : alarmsOpen"
            :hidden="alarmsOpen <= 0" :max="99">
            <el-icon class="hd-icon" title="报警记录" @click="router.push('/alarm')"><Bell /></el-icon>
          </el-badge>
          <!-- 2026-10-29：「发现」和「新增设备」已按用户要求移到设备监控页工具栏，
               发现以弹框打开（DiscoveryDialog），页头不再重复放按钮 -->
          <el-dropdown trigger="click" @command="onUserCmd">
            <span class="user-pill">
              {{ auth.displayName }} · {{ auth.roleLabel }}
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="account">账号与密码</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main"><router-view /></el-main>
    </el-container>
  </el-container>
  </el-config-provider>

  <AccountDialog v-model="acctVisible" />
</template>

<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Monitor, Bell, Lightning, DataLine, Sunrise,
  OfficeBuilding, Box, Sell, ChatDotRound, Setting,
  Expand, Fold, Sunny, Moon, ArrowDown, User, Goods, ShoppingCart, Search, Money, TrendCharts
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { useWsStore } from '@/stores/ws'
import { useDevicesStore } from '@/stores/devices'
import { alarmsApi } from '@/api/alarms'
import { usePolling } from '@/composables/usePolling'
import AccountDialog from '@/components/AccountDialog.vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const theme = useThemeStore()
const ws = useWsStore()
const devices = useDevicesStore()

const isLogin = computed(() => route.path === '/login')
const acctVisible = ref(false)

/* 侧边栏收缩：沿用 dh-sb-min 键；窗口 <960px 默认收起（PRD §0.1） */
const collapsed = ref(false)
try { collapsed.value = localStorage.getItem('dh-sb-min') === '1' } catch { /* 隐私模式 */ }
onMounted(() => { if (window.innerWidth < 960) collapsed.value = true })
function toggleCollapsed() {
  collapsed.value = !collapsed.value
  try { localStorage.setItem('dh-sb-min', collapsed.value ? '1' : '0') } catch { /* 隐私模式 */ }
}

/* 登录态驱动 WS 连接与首屏数据 */
watch(() => auth.user, (u) => { if (u) ws.connect(); else ws.close() }, { immediate: true })
watch(() => auth.ready, (r) => {
  if (r && auth.user) {
    devices.bindWs()
    devices.load()
    loadAlarmSummary()
  }
}, { immediate: true })

/* 铃铛：ws 事件节流触发 + 20s 兜底轮询（PRD §0.1） */
const alarmsOpen = ref(0)
async function loadAlarmSummary() {
  // 销售角色没有报警权限，别每 20 秒去撞一次 403
  if (auth.isSales) return
  try { alarmsOpen.value = (await alarmsApi.summary()).open ?? 0 } catch { /* 静默，下一轮兜底 */ }
}
usePolling(loadAlarmSummary, 20000, { immediate: false })
onScopeDispose(ws.on('alarms:dirty', () => loadAlarmSummary()))

const baseGroups = computed(() => [
  { title: '生产监控', items: [
    { path: '/', label: '设备监控', icon: Monitor },
    { path: '/alarm', label: '报警', icon: Bell }
  ] },
  { title: '分析', items: [
    { path: '/energy', label: '能耗分析', icon: Lightning },
    { path: '/report', label: '报表', icon: DataLine },
    { path: '/digest', label: 'AI 早报', icon: Sunrise },
    { path: '/ai/chat', label: 'AI 助手', icon: ChatDotRound }
  ] },
  { title: 'ERP', items: [
    { path: '/erp/lines', label: '产线管理', icon: OfficeBuilding, disabled: false, tip: '' },
    { path: '/erp/base', label: '基础资料', icon: Goods, disabled: false, tip: '' },
    { path: '/erp/purchase', label: '采购管理', icon: ShoppingCart, disabled: false, tip: '' },
    { path: '/erp/sales', label: '销售管理', icon: Sell, disabled: false, tip: '' },
    { path: '/erp/stock', label: '库存管理', icon: Box, disabled: false, tip: '' },
    { path: '/erp/finance', label: '财务管理', icon: Money, disabled: false, tip: '' },
    { path: '/erp/reports', label: '经营报表', icon: TrendCharts, disabled: false, tip: '' },
    { path: '/erp/sales-query', label: '销售查询', icon: Search, disabled: false, tip: '' }
  ] },
  ...(auth.isAdmin
    ? [{ title: '系统管理', items: [
        { path: '/system', label: '系统', icon: Setting, disabled: false, tip: '' },
        { path: '/users', label: '用户管理', icon: User, disabled: false, tip: '' }
      ] }]
    : [])
])

const groups = computed(() => {
  // 销售角色只给一个只读页：菜单是权限的第一道说明，别让他点进去再吃 403
  if (auth.isSales) {
    return [{ title: '销售', items: [
      { path: '/erp/sales-query', label: '销售查询', icon: Search, disabled: false, tip: '' },
      { path: '/ai/chat', label: 'AI 助手', icon: ChatDotRound, disabled: false, tip: '' }
    ] }]
  }
  return baseGroups.value
})

function onUserCmd(cmd: string) {
  if (cmd === 'account') acctVisible.value = true
  else if (cmd === 'logout') auth.logout().then(() => router.replace('/login'))
}
</script>

<style scoped>
.shell { min-height: 100vh; }
.aside {
  background: var(--rail-bg); border-right: 1px solid var(--border);
  display: flex; flex-direction: column; transition: width var(--motion-base) var(--ease-standard);
  overflow: hidden;
}
.brand { display: flex; align-items: center; gap: 10px; padding: 16px 14px 12px; }
.brand-mark {
  flex: none; width: 32px; height: 32px; border-radius: 10px;
  background: var(--accent); color: var(--accent-on);
  box-shadow: var(--e-accent);
  display: flex; align-items: center; justify-content: center; font-weight: 600;
}
.brand-text { display: flex; flex-direction: column; line-height: 1.25; }
.brand-text b { font-size: var(--text-md); }
.brand-text span { font-size: var(--text-2xs); color: var(--muted); }
.nav { flex: 1; overflow-y: auto; padding: 4px 10px; }
.group-label {
  font-size: var(--text-2xs); color: var(--meta); letter-spacing: 2px;
  padding: 14px 8px 4px;
}
.nav-item {
  display: flex; align-items: center; gap: 10px; padding: 9px 12px; margin: 2px 0;
  border-radius: 10px; color: var(--fg-2); cursor: pointer; user-select: none;
  transition: background var(--motion-fast) var(--ease-standard),
              box-shadow var(--motion-fast) var(--ease-standard),
              color var(--motion-fast) var(--ease-standard);
}
.nav-item:hover { background: var(--hover); color: var(--fg); }
.nav-item.active {
  background: var(--pill-bg);
  box-shadow: var(--elev-raised), var(--pill-inset);
  color: var(--fg);
}
.nav-item.active .el-icon { color: var(--accent); }
.nav-item.disabled { opacity: .45; cursor: not-allowed; }
.foot {
  display: flex; align-items: center; gap: 6px; padding: 10px 12px;
  border-top: 1px solid var(--border);
}
.foot-link { color: var(--muted); font-size: var(--text-xs); text-decoration: none; }
.foot-link:hover { color: var(--fg); }
.foot-icon { margin-left: auto; color: var(--muted); cursor: pointer; }
.foot-icon + .foot-icon { margin-left: 0; }
.foot-icon:hover { color: var(--fg); }

.body-col { min-width: 0; }
.hd {
  display: flex; align-items: center; gap: 16px;
  border-bottom: 1px solid var(--border); background: var(--rail-bg);
}
.hd-title { font-size: var(--text-md); font-weight: 500; white-space: nowrap; }
.hd-title span { color: var(--muted); font-size: var(--text-xs); font-weight: 400; margin-left: 4px; }
.hd-stats { display: flex; gap: 14px; color: var(--muted); font-size: var(--text-sm); white-space: nowrap; }
.hd-stats b { color: var(--fg); margin: 0 2px; }
.c-run { color: var(--run) !important; }
.c-alarm { color: var(--alarm) !important; }
.hd-right { margin-left: auto; display: flex; align-items: center; gap: 14px; }
.ws-pill {
  display: inline-flex; align-items: center; gap: 6px; font-size: var(--text-xs);
  color: var(--muted); white-space: nowrap;
}
.ws-pill .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--off); }
.ws-pill.on .dot { background: var(--run); box-shadow: var(--dot-glow, 0 0 6px) var(--run); }
.hd-icon { cursor: pointer; color: var(--fg-2); font-size: 18px; }
.hd-icon:hover { color: var(--fg); }
.user-pill {
  display: inline-flex; align-items: center; gap: 4px; cursor: pointer;
  color: var(--fg-2); font-size: var(--text-sm); white-space: nowrap;
  padding: 6px 12px; border-radius: var(--radius-pill);
  background: var(--pill-bg); box-shadow: var(--pill-inset);
}
.user-pill:hover { color: var(--fg); }
.main { padding: var(--space-5); background: var(--bg); }
</style>
