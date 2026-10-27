import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { http } from '@/api/http'

export interface MeInfo {
  authenticated?: boolean
  username?: string
  displayName?: string
  role?: string
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<MeInfo | null>(null)
  const ready = ref(false)
  const isAdmin = computed(() => user.value?.role === 'ADMIN')
  const canWrite = computed(() => user.value?.role === 'ADMIN' || user.value?.role === 'MANAGER')
  const displayName = computed(() => user.value?.displayName || user.value?.username || '--')
  const roleLabel = computed(() => {
    const r = user.value?.role
    return r === 'ADMIN' ? '管理员' : r === 'MANAGER' ? '车间主任' : r === 'LEADER' ? '班组长' : (r || '--')
  })

  async function ensure(): Promise<boolean> {
    if (ready.value) return !!user.value
    try {
      const me = await http.get<MeInfo>('/api/auth/me')
      user.value = me && me.authenticated ? me : null
    } catch {
      user.value = null
    }
    ready.value = true
    return !!user.value
  }

  async function login(username: string, password: string) {
    /* 登录失败是 HTTP 200 + {ok:false,message}（含连续失败锁定提示），必须透传 message */
    const r: any = await http.post('/api/auth/login', { username, password })
    if (r && r.ok === false) throw new Error(r.message || '登录失败')
    ready.value = false
    const ok = await ensure()
    if (!ok) throw new Error('登录后无法获取用户信息')
  }

  async function logout() {
    try { await http.post('/api/auth/logout') } catch { /* 登出接口异常不阻断跳转 */ }
    user.value = null
    ready.value = false
  }

  async function changePassword(oldPassword: string, newPassword: string) {
    return http.post<{ ok: boolean; message?: string }>('/api/auth/password', { oldPassword, newPassword })
  }

  return { user, ready, isAdmin, canWrite, displayName, roleLabel, ensure, login, logout, changePassword }
})
