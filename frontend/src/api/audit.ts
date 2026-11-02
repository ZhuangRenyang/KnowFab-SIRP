import { http } from './http'

/* 操作审计查询（DH-24）。
   只读：后端只有 GET /api/audit，没有新增/修改/删除接口。
   审计由业务代码在动作发生时写入（AuditService），前端不参与写入——
   前端能伪造的操作人没有意义。 */

export type AuditCategory =
  | 'DEVICE' | 'ENERGY' | 'ALARM' | 'REPORT' | 'SYSTEM' | 'MANUAL' | 'USER'

export type AuditAction =
  | 'CREATE' | 'UPDATE' | 'DELETE' | 'WRITE' | 'PURGE' | 'REVOKE' | 'RESET' | 'LOGIN'

export interface AuditRow {
  id: number
  /** epoch 毫秒（后端 System.currentTimeMillis()） */
  at: number
  category: AuditCategory
  action: AuditAction
  /** 对象标识：如 tariff、CNC-01 */
  target: string
  /** 对象名：如 峰谷电价方案 */
  targetName: string
  detail: string
  operator: string
  ip: string
  ok: boolean
  /** 失败原因；成功时是空串 */
  message: string
}

export interface AuditQuery {
  /** YYYY-MM-DD；不传则后端取最近 7 天 */
  from?: string
  /** YYYY-MM-DD；不传则后端取到今天 */
  to?: string
  category?: AuditCategory | ''
  /** 匹配对象名 / 对象标识 / 操作人 / 详情 */
  keyword?: string
  limit?: number
}

export interface AuditResponse {
  ok: boolean
  /** 后端实际采用的日期区间（做过倒序纠正） */
  from: string
  to: string
  limit: number
  rows: AuditRow[]
}

/** 分类下拉的取值顺序（与后端枚举一致）；显示文案走 i18n */
export const AUDIT_CATEGORIES: AuditCategory[] = [
  'DEVICE', 'ENERGY', 'ALARM', 'REPORT', 'SYSTEM', 'MANUAL', 'USER'
]

/** 后端枚举 → i18n 词条 key。显示文案不写死在代码里 */
export const catKey = (c: string) => `auditDict.cat.${c}`
export const actKey = (a: string) => `auditDict.act.${a}`

/** 页面默认取多少条（后端上限 1000，这里按契约用 200） */
export const AUDIT_LIMIT = 200

export async function fetchAudit(params: AuditQuery = {}): Promise<AuditResponse> {
  const q = new URLSearchParams()
  if (params.from) q.set('from', params.from)
  if (params.to) q.set('to', params.to)
  if (params.category) q.set('category', params.category)
  if (params.keyword) q.set('keyword', params.keyword)
  q.set('limit', String(params.limit ?? AUDIT_LIMIT))
  return http.get<AuditResponse>(`/api/audit?${q.toString()}`)
}
