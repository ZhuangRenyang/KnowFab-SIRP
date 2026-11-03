import { http } from './http'
import type { QcAqlPlan, QcAqlResolve, QcDefectCode } from './qcTypes'
import type { QcAqlPlanInput, QcDefectCodeInput } from './qcInputs'

/* ============================================================
   质检 · 主数据与 AQL 方案
   ------------------------------------------------------------
   前缀 /api/erp/qc/。与后端 ErpQcDefectCodeController / ErpAqlController 对齐。

   写这条链的规矩：**主数据缺失一律报错，不做字面量兜底**。
   缺陷代码查不到就报错、删不掉就报错，不要在前端给个"未知缺陷"糊过去——
   那样主数据维护不到位这件事就永远不会被发现。
   ============================================================ */

export interface QcDefectCodeListResult {
  ok: boolean
  total: number
  rows: QcDefectCode[]
}

export interface QcAqlPlanListResult {
  ok: boolean
  total: number
  /** 还没对着纸本核对的格子数 */
  unverifiedCount: number
  /** CR 零容忍规则说明（后端给的原文） */
  crRuleNote: string
  rows: QcAqlPlan[]
}

export interface QcApiOk {
  ok: boolean
  message?: string
  code?: string
}

export const qcDefectCodeApi = {
  list: (p: { q?: string; severity?: string; enabled?: boolean } = {}) => {
    const qs = new URLSearchParams()
    if (p.q && p.q.trim()) qs.set('q', p.q.trim())
    if (p.severity) qs.set('severity', p.severity)
    if (p.enabled !== undefined) qs.set('enabled', String(p.enabled))
    return http.get<QcDefectCodeListResult>(`/api/erp/qc/defect-codes?${qs.toString()}`)
  },
  /** 下拉选项：只给启用的，字段少 */
  options: () =>
    http.get<{ id: number; code: string; name: string; severity: string; category?: string | null }[]>(
      '/api/erp/qc/defect-codes/options'
    ),
  create: (body: QcDefectCodeInput) =>
    http.post<QcApiOk>('/api/erp/qc/defect-codes', body),
  update: (id: number, body: QcDefectCodeInput) =>
    http.put<QcApiOk>(`/api/erp/qc/defect-codes/${id}`, body),
  /** 被引用过就拒绝删除（后端校验引用计数），前端原样透传原因 */
  remove: (id: number) =>
    http.del<QcApiOk>(`/api/erp/qc/defect-codes/${id}`)
}

export const qcAqlApi = {
  /**
   * 查表。查不到时后端返回 { ok:false, code:'NO_AQL_PLAN' } —— 不是 HTTP 错误。
   * 前端**不许自己兜底算一个 Ac**，要把 code 抛给界面做「两个出路」卡片。
   */
  resolve: (p: { level?: string; strictLevel?: string; lotQty: number; aql: number }) => {
    const qs = new URLSearchParams()
    if (p.level) qs.set('level', p.level)
    if (p.strictLevel) qs.set('strictLevel', p.strictLevel)
    qs.set('lotQty', String(p.lotQty))
    qs.set('aql', String(p.aql))
    return http.get<QcAqlResolve>(`/api/erp/qc/aql/resolve?${qs.toString()}`)
  },
  plans: (p: { level?: string; strictLevel?: string; onlyUnverified?: boolean } = {}) => {
    const qs = new URLSearchParams()
    if (p.level) qs.set('level', p.level)
    if (p.strictLevel) qs.set('strictLevel', p.strictLevel)
    if (p.onlyUnverified) qs.set('onlyUnverified', 'true')
    return http.get<QcAqlPlanListResult>(`/api/erp/qc/aql/plans?${qs.toString()}`)
  },
  createPlan: (body: QcAqlPlanInput) =>
    http.post<QcApiOk>('/api/erp/qc/aql/plans', body),
  updatePlan: (id: number, body: QcAqlPlanInput) =>
    http.put<QcApiOk>(`/api/erp/qc/aql/plans/${id}`, body),
  removePlan: (id: number) =>
    http.del<QcApiOk>(`/api/erp/qc/aql/plans/${id}`),
  /** 标记这一格已对着纸本核对过 */
  verifyPlan: (id: number) =>
    http.post<QcApiOk>(`/api/erp/qc/aql/plans/${id}/verify`, {}),
  /** 恢复国标默认。重播会删掉现有行，要提示用户 */
  reseed: () => http.post<QcApiOk & { rows?: number }>('/api/erp/qc/aql/reseed', {})
}
