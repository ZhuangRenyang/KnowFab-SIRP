import { http } from './http'

export interface UserRow {
  id: number
  username: string
  displayName?: string
  role: string
  roleText?: string
  enabled: boolean
  createdAt?: string
  lastLoginAt?: string
}

export const ROLES = [
  { value: 'ADMIN', label: '管理员' },
  { value: 'MANAGER', label: '车间主任' },
  { value: 'LEADER', label: '班组长' }
]

export const roleLabel = (r: string) => ROLES.find((x) => x.value === r)?.label || r

/* 契约与后端 UserController 逐字段对齐：
   - 角色变更/停用启用都是 POST /{id}（部分字段 patch）
   - 重置密码 POST /{id}/password（管理员操作，≥8 位由后端校验）
   - 后端保护：不能删除自己、不能停用最后一个启用的管理员 */
export const usersApi = {
  list: () => http.get<UserRow[]>('/api/users'),
  create: (body: { username: string; displayName: string; password: string; role: string }) =>
    http.post<{ ok: boolean; message?: string }>('/api/users', body),
  update: (id: number, patch: { displayName?: string; role?: string; enabled?: boolean }) =>
    http.post<{ ok: boolean; message?: string }>(`/api/users/${id}`, patch),
  resetPassword: (id: number, password: string) =>
    http.post<{ ok: boolean; message?: string }>(`/api/users/${id}/password`, { password }),
  remove: (id: number) => http.del<{ ok: boolean; message?: string }>(`/api/users/${id}`)
}
