import { http } from './http'
import type {
  QcInspectionDetail,
  QcInspectionHeader,
  QcInspectionItem,
  QcInspectionDefect,
  QcInspectionRow,
  QcJudgeResult
} from './qcTypes'
import type {
  QcConcessionRelease,
  QcDashboard,
  QcDefectRow,
  QcDeviceBaseline,
  QcParetoRow,
  QcRecon
} from './qcRecords'
import type {
  QcBatchDisposeInput,
  QcBatchResult,
  QcBatchTriageInput,
  QcConcessionInput,
  QcDefectCreateInput,
  QcInspectionInput,
  QcRevokeInput,
  QcReworkInput,
  QcScrapInput
} from './qcInputs'

/* ============================================================
   质检 · 检验单 / 不良品 / 处置 / 看板
   ------------------------------------------------------------
   前缀 /api/erp/qc/。

   几条不能省的约定：

   · 判定权不外包：judge / dispose / revoke 都只给 ADMIN|MANAGER，
     前端按 canWrite 隐藏按钮，但真正的门在后端。

   · 撤销处置必须带原因（撤销要能追溯谁批的）。

   · 让步（CONCESSION）永远由人手动选，算法不产出。

   · 判定的依据只有 defectQty（不合格品件数），不是缺陷项数。
     前端任何地方都不要拿 defectCr+Ma+Mi 去算合格率。
   ============================================================ */

export interface QcOk {
  ok: boolean
  message?: string
  code?: string
}

export interface QcInspectionListResult {
  ok: boolean
  rows: QcInspectionRow[]
  total: number
  page: number
  size: number
  pages: number
}

export interface QcDefectListResult {
  ok: boolean
  rows: QcDefectRow[]
  total: number
  page: number
  size: number
  pages: number
}

export interface QcParetoResult {
  ok: boolean
  by: string
  rows: QcParetoRow[]
}

/** 检验单详情的原始三层结构（后端返回的样子） */
interface QcInspectionDetailRaw {
  ok: boolean
  header: QcInspectionHeader
  items: QcInspectionItem[]
  defects: QcInspectionDefect[]
}

/**
 * 把 header 摊平到顶层。
 * 后端返回 {ok, header, items, defects}，组件里写 detail.header.xxx 很难看，
 * 而且每次都要判断 header 有没有（加载中的时候会没有）。
 */
function flattenInspection(raw: QcInspectionDetailRaw): QcInspectionDetail {
  return { ...(raw?.header || ({} as QcInspectionHeader)), ...raw } as QcInspectionDetail
}

const enc = encodeURIComponent

export const qcInspectionApi = {
  list: (p: {
    type?: string
    status?: string
    from?: string
    to?: string
    kw?: string
    page?: number
    size?: number
  } = {}) => {
    const qs = new URLSearchParams()
    if (p.type) qs.set('type', p.type)
    if (p.status) qs.set('status', p.status)
    if (p.from) qs.set('from', p.from)
    if (p.to) qs.set('to', p.to)
    if (p.kw && p.kw.trim()) qs.set('kw', p.kw.trim())
    qs.set('page', String(p.page || 1))
    qs.set('size', String(p.size || 20))
    return http.get<QcInspectionListResult>(`/api/erp/qc/inspections?${qs.toString()}`)
  },
  detail: (no: string) =>
    http.get<QcInspectionDetailRaw>(`/api/erp/qc/inspections/${enc(no)}`)
      .then(flattenInspection),
  create: (body: QcInspectionInput) =>
    http.post<QcOk & { inspectionNo?: string }>('/api/erp/qc/inspections', body),
  /** 改草稿（整表替换明细）。只有 DRAFT 能改 */
  update: (no: string, body: Partial<QcInspectionInput>) =>
    http.put<QcOk>(`/api/erp/qc/inspections/${enc(no)}`, body),
  /** 提交：锁定抽样参数快照，之后永不回查方案表 */
  submit: (no: string) =>
    http.post<QcOk>(`/api/erp/qc/inspections/${enc(no)}/submit`, {}),
  /** 判定。返回 why（原因码）与 judgeText（说明原文） */
  judge: (no: string, body: { defectQty?: number; result?: string; remark?: string } = {}) =>
    http.post<QcJudgeResult>(`/api/erp/qc/inspections/${enc(no)}/judge`, body),
  /** 作废。已判定的不许作废（结论一旦产生就要留档） */
  cancel: (no: string, reason?: string) =>
    http.post<QcOk>(`/api/erp/qc/inspections/${enc(no)}/cancel`, { reason }),
  /** 导出。用 blob 下载，不用 fetch 直接开新窗口（要带认证头） */
  exportUrl: (no: string) => `/api/erp/qc/inspections/${enc(no)}/export`
}

export const qcDefectApi = {
  list: (p: {
    status?: string
    source?: string
    severity?: string
    from?: string
    to?: string
    kw?: string
    partial?: boolean
    page?: number
    size?: number
  } = {}) => {
    const qs = new URLSearchParams()
    if (p.status) qs.set('status', p.status)
    if (p.source) qs.set('source', p.source)
    if (p.severity) qs.set('severity', p.severity)
    if (p.from) qs.set('from', p.from)
    if (p.to) qs.set('to', p.to)
    if (p.kw && p.kw.trim()) qs.set('kw', p.kw.trim())
    if (p.partial) qs.set('partial', 'true')
    qs.set('page', String(p.page || 1))
    qs.set('size', String(p.size || 20))
    return http.get<QcDefectListResult>(`/api/erp/qc/defects?${qs.toString()}`)
  },
  /** 人工补录 */
  create: (body: QcDefectCreateInput) =>
    http.post<QcOk & { defectNo?: string }>('/api/erp/qc/defects', body),
  /** 报废 */
  scrap: (no: string, body: QcScrapInput) =>
    http.post<QcOk & { stockBillNo?: string }>(`/api/erp/qc/defects/${enc(no)}/scrap`, body),
  /** 返工 */
  rework: (no: string, body: QcReworkInput) =>
    http.post<QcOk & { reworkOrderNo?: string; fqcInspectionNo?: string }>(
      `/api/erp/qc/defects/${enc(no)}/rework`, body),
  /** 让步接收 */
  concession: (no: string, body: QcConcessionInput) =>
    http.post<QcOk & { concessionNo?: string }>(`/api/erp/qc/defects/${enc(no)}/concession`, body),
  /** 撤销处置：必须写原因，后端会冲销产生的库存单据 */
  revoke: (no: string, body: QcRevokeInput) =>
    http.post<QcOk>(`/api/erp/qc/defects/${enc(no)}/revoke`, body),
  /** 批量补缺陷代码 */
  batchTriage: (body: QcBatchTriageInput) =>
    http.post<QcBatchResult>('/api/erp/qc/defects/batch-triage', body),
  /** 批量处置 */
  batchDispose: (body: QcBatchDisposeInput) =>
    http.post<QcBatchResult>('/api/erp/qc/defects/batch-dispose', body),
  /** 设备通道基线：人工补录时拿来对照 */
  deviceBaseline: (workOrderNo: string) =>
    http.get<QcDeviceBaseline>(
      `/api/erp/qc/defects/device-baseline?workOrderNo=${enc(workOrderNo)}`
    ),
  /** 放行检查：出货前查有没有放行依据 */
  concessionRelease: (p: { whId: number; skuIds: number[]; soNo?: string }) => {
    const qs = new URLSearchParams()
    qs.set('whId', String(p.whId))
    qs.set('skuIds', p.skuIds.join(','))
    if (p.soNo) qs.set('soNo', p.soNo)
    return http.get<QcConcessionRelease>(`/api/erp/qc/defects/concession-release?${qs.toString()}`)
  },
  /** 手动同步设备通道 */
  syncDevice: () => http.post<QcOk>('/api/erp/qc/defects/sync-device', {})
}

export const qcReportApi = {
  /** 看板。口径说明（caliberNote）是后端给的原文，前端原样展示不自己算 */
  dashboard: (p: { from?: string; to?: string } = {}) => {
    const qs = new URLSearchParams()
    if (p.from) qs.set('from', p.from)
    if (p.to) qs.set('to', p.to)
    return http.get<QcDashboard>(`/api/erp/qc/dashboard?${qs.toString()}`)
  },
  /** 帕累托。by = code（按缺陷代码）| device（按设备） */
  pareto: (p: { by?: 'code' | 'device'; from?: string; to?: string } = {}) => {
    const qs = new URLSearchParams()
    qs.set('by', p.by || 'code')
    if (p.from) qs.set('from', p.from)
    if (p.to) qs.set('to', p.to)
    return http.get<QcParetoResult>(`/api/erp/qc/pareto?${qs.toString()}`)
  },
  /** 双通道对账：设备采集 vs 人工补录 */
  recon: (p: { from?: string; to?: string; workOrderNo?: string } = {}) => {
    const qs = new URLSearchParams()
    if (p.from) qs.set('from', p.from)
    if (p.to) qs.set('to', p.to)
    if (p.workOrderNo) qs.set('workOrderNo', p.workOrderNo)
    return http.get<QcRecon>(`/api/erp/qc/recon?${qs.toString()}`)
  },
  /** 按工单解决对账分歧：以哪条通道为准 */
  resolveRecon: (workOrderNo: string, body: { source: 'DEVICE' | 'MANUAL'; reason?: string }) =>
    http.post<QcOk>(`/api/erp/qc/recon/${enc(workOrderNo)}/resolve`, body)
}
