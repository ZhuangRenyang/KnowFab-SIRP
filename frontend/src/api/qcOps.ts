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
  QcReconResult,
  QcWarnCode
} from './qcRecords'
import type {
  QcBatchDisposeInput,
  QcBatchResult,
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

/**
 * 时间窗参数 → 毫秒时间戳。
 *
 * 后端的 {@code from}/{@code to} 是 {@code @RequestParam Long}——
 * **传日期串会 400**，控制器 catch 之后返回 {@code {ok:false}}，
 * 整个看板页面报错，而且看不出是参数格式的问题。
 *
 * 这里统一转：纯数字当已是时间戳；{@code 'YYYY-MM-DD'} 按当天
 * 00:00:00.000（from）/ 23:59:59.999（to）转。
 */
function tsOf(v: string | number | undefined, endOfDay = false): string | null {
  if (v === undefined || v === null || v === '') return null
  if (typeof v === 'number') return String(v)
  if (/^\d+$/.test(v)) return v
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(v)
  if (!m) return v
  const dt = new Date(
    Number(m[1]), Number(m[2]) - 1, Number(m[3]),
    endOfDay ? 23 : 0, endOfDay ? 59 : 0, endOfDay ? 59 : 0, endOfDay ? 999 : 0
  )
  return String(dt.getTime())
}

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

/**
 * 处置结果。
 *
 * **直接用后端给的 pendingQty / disposedQty / displayStatus**，
 * 别在前端拿 qty − disposedQty 自己减 —— 两列都是 DECIMAL(18,4)，
 * 减出来可能和后端差 0.0001，处置流水是只追加的，差一点点就再也对不上。
 *
 * warnCode 是代号不是文案：REWORK_NEED_ISSUE / CONCESSION_NEED_ACK，
 * 前端按 i18n 渲染（后端塞中文的话英文界面会蹦中文）。
 */
export interface QcDisposeResult extends QcOk {
  defectNo?: string
  action?: string
  dispositionSeq?: number
  stockBillNo?: string | null
  reworkOrderNo?: string | null
  disposedQty?: number
  pendingQty?: number
  status?: string
  displayStatus?: string
  /** 告警代号 */
  warnCode?: QcWarnCode | null
  /** 告警参数，如 { warehouseName: '成品仓' } */
  warnParams?: Record<string, string | number> | null
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
    const tsFrom = tsOf(p.from)
    const tsTo = tsOf(p.to, true)
    if (tsFrom) qs.set('from', tsFrom)
    if (tsTo) qs.set('to', tsTo)
    if (p.kw && p.kw.trim()) qs.set('kw', p.kw.trim())
    qs.set('page', String(p.page || 1))
    qs.set('size', String(p.size || 20))
    return http.get<QcInspectionListResult>(`/api/erp/qc/inspections?${qs.toString()}`)
  },
  detail: (no: string) =>
    http.get<QcInspectionDetailRaw>(`/api/erp/qc/inspections/${enc(no)}`)
      .then(flattenInspection),
  /**
   * 建单（草稿）。
   * 抽样参数在这一步抄快照：提交后永不回查方案表。
   */
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
    const tsFrom = tsOf(p.from)
    const tsTo = tsOf(p.to, true)
    if (tsFrom) qs.set('from', tsFrom)
    if (tsTo) qs.set('to', tsTo)
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
    http.post<QcDisposeResult>(`/api/erp/qc/defects/${enc(no)}/scrap`, body),
  /** 返工 */
  rework: (no: string, body: QcReworkInput) =>
    http.post<QcDisposeResult>(`/api/erp/qc/defects/${enc(no)}/rework`, body),
  /** 让步接收 */
  concession: (no: string, body: QcConcessionInput) =>
    http.post<QcDisposeResult>(`/api/erp/qc/defects/${enc(no)}/concession`, body),
  /** 撤销处置：必须写原因，后端会冲销产生的库存单据 */
  revoke: (no: string, body: QcRevokeInput) =>
    http.post<QcOk>(`/api/erp/qc/defects/${enc(no)}/revoke`, body),
  /** 批量处置 */
  batchDispose: (body: QcBatchDisposeInput) =>
    http.post<QcBatchResult>('/api/erp/qc/defects/batch-dispose', body),
  /**
   * 分诊：给一条不良记录补缺陷分类。
   *
   * 批量是前端逐条循环调的（后端只有单条端点）——
   * 分诊不会失败一半，失败的只会是"这条已处置"，逐条报错更好定位是哪一条。
   */
  triage: (no: string, body: { defectCodeId: number; severity?: string | null }) =>
    http.post<QcOk & { defectNo?: string; defectCode?: string; defectName?: string; severity?: string | null }>(
      `/api/erp/qc/defects/${enc(no)}/triage`, body
    ),
  /** 缺陷详情（单条）：{ defect, dispositions, voidedCount, pendingQty, displayStatus } */
  detail: (no: string) =>
    http.get<{ ok: boolean; defect: QcDefectRow; dispositions?: unknown[]; voidedCount?: number }>(
      `/api/erp/qc/defects/${enc(no)}`
    ),
  /** 设备通道基线：人工补录时拿来对照 */
  deviceBaseline: (workOrderNo: string) =>
    http.get<QcDeviceBaseline>(
      `/api/erp/qc/defects/device-baseline?workOrderNo=${enc(workOrderNo)}`
    ),
  /**
   * 放行检查：出货前查有没有放行依据。
   *
   * 路径是 /api/erp/qc/concession-release（不在 /defects 下）——
   * 它是"质检域的放行校验"，顶层路径语义更准。
   */
  concessionRelease: (p: { whId: number; skuIds: number[] }) => {
    const qs = new URLSearchParams()
    qs.set('whId', String(p.whId))
    qs.set('skuIds', p.skuIds.join(','))
    return http.get<QcConcessionRelease>(`/api/erp/qc/concession-release?${qs.toString()}`)
  },
  /** 手动同步设备通道 */
  syncDevice: () => http.post<QcOk>('/api/erp/qc/defects/sync-device', {})
}

export const qcReportApi = {
  /** 看板。口径说明（caliberNote）是后端给的原文，前端原样展示不自己算 */
  dashboard: (p: { from?: string; to?: string } = {}) => {
    const qs = new URLSearchParams()
    const tsFrom = tsOf(p.from)
    const tsTo = tsOf(p.to, true)
    if (tsFrom) qs.set('from', tsFrom)
    if (tsTo) qs.set('to', tsTo)
    return http.get<QcDashboard>(`/api/erp/qc/dashboard?${qs.toString()}`)
  },
  /** 帕累托。by = code（按缺陷代码）| device（按设备） */
  pareto: (p: { by?: 'code' | 'device'; from?: string; to?: string } = {}) => {
    const qs = new URLSearchParams()
    qs.set('by', p.by || 'code')
    const tsFrom = tsOf(p.from)
    const tsTo = tsOf(p.to, true)
    if (tsFrom) qs.set('from', tsFrom)
    if (tsTo) qs.set('to', tsTo)
    return http.get<QcParetoResult>(`/api/erp/qc/pareto?${qs.toString()}`)
  },
  /** 双通道对账：设备采集 vs 人工补录。返回 { items: [...] } */
  recon: (p: { from?: string; to?: string } = {}) => {
    const qs = new URLSearchParams()
    const tsFrom = tsOf(p.from)
    const tsTo = tsOf(p.to, true)
    if (tsFrom) qs.set('from', tsFrom)
    if (tsTo) qs.set('to', tsTo)
    return http.get<QcReconResult>(`/api/erp/qc/recon?${qs.toString()}`)
  },
  /**
   * 按工单解决对账分歧：以哪条通道为准。
   * 校准**只改质量域**，prod_hourly 一个字不动 —— 那是 OEE 良率的唯一数据源。
   */
  resolveRecon: (workOrderNo: string, body: { choice: 'TRUST_QC' | 'TRUST_DEVICE'; reason: string }) =>
    http.post<QcOk>(`/api/erp/qc/recon/${enc(workOrderNo)}/resolve`, body)
}
