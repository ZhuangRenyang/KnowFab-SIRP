import { http } from './http'

/* 生产工单契约：与后端 ErpWorkOrderController 对齐。
   状态机：DRAFT → RELEASED → RUNNING → DONE / CANCELLED
   写作权限：建单/改单/下达/完工/取消 = ADMIN|MANAGER；开工/领料额外放给 LEADER（车间现场操作）。 */

export type WorkOrderStatus = 'DRAFT' | 'RELEASED' | 'RUNNING' | 'DONE' | 'CANCELLED'

export interface WorkOrderRow {
  id: number
  orderNo: string
  productSkuId: number
  productCode: string | null
  productName: string | null
  productUnit: string | null
  planQty: number
  doneQty: number
  defectQty: number
  status: WorkOrderStatus
  statusLabel: string
  lineCode: string | null
  deviceCode: string | null
  warehouseId: number | null
  /** 领料仓库（原料仓）；与成品仓是两个概念 */
  materialWhId: number | null
  srcOrderNo: string | null
  autoBackflush: boolean
  planStart: number | null
  planEnd: number | null
  actualStart: number | null
  actualEnd: number | null
  remark: string | null
  createdBy: string | null
  createdAt: number
  updatedAt: number
  /** 计划为 0 时为 null（"没有计划"和"一件没做"是两件事） */
  progress: number | null
  gapQty: number
  overdue: boolean
  dueInDays: number | null
}

export interface WorkOrderMaterial {
  id: number
  skuId: number
  skuCode: string | null
  skuName: string
  unit: string | null
  needQty: number
  issuedQty: number
  gapQty: number
  shortage: boolean
  over: boolean
  warehouseId: number | null
  stockQty?: number | null
}

export interface WorkOrderOutput {
  billNo: string
  deviceCode: string | null
  qty: number
  defectQty: number
  createdAt: number
}

export interface WorkOrderDetail extends WorkOrderRow {
  materials: WorkOrderMaterial[]
  materialNeedTotal: number
  materialIssuedTotal: number
  shortageCount: number
  outputs: WorkOrderOutput[]
}

export interface ShortageRow {
  orderNo: string
  productName: string | null
  status: WorkOrderStatus
  skuId: number
  skuCode: string | null
  skuName: string
  unit: string | null
  needQty: number
  issuedQty: number
  gapQty: number
  stockQty: number | null
}

export interface WorkOrderSummary {
  running: number
  released: number
  draft: number
  done: number
}

export interface WorkOrderInput {
  productSkuId: number
  planQty: number
  lineCode?: string | null
  deviceCode?: string | null
  warehouseId?: number | null
  materialWhId?: number | null
  srcOrderNo?: string | null
  autoBackflush?: boolean
  planStart?: number | null
  planEnd?: number | null
  remark?: string | null
}

export const workOrderApi = {
  list: (p: { status?: string; kw?: string; page?: number; size?: number }) => {
    const qs = new URLSearchParams()
    if (p.status) qs.set('status', p.status)
    if (p.kw && p.kw.trim()) qs.set('kw', p.kw.trim())
    qs.set('page', String(p.page || 1))
    qs.set('size', String(p.size || 20))
    return http.get<{
      ok: boolean
      rows: WorkOrderRow[]
      total: number
      page: number
      size: number
      pages: number
      summary: WorkOrderSummary
    }>(`/api/erp/work-orders?${qs.toString()}`)
  },
  detail: (orderNo: string) =>
    http.get<WorkOrderDetail>(`/api/erp/work-orders/${encodeURIComponent(orderNo)}`),
  shortage: () =>
    http.get<{ ok: boolean; rows: ShortageRow[]; total: number }>('/api/erp/work-orders/shortage'),
  create: (b: WorkOrderInput) =>
    http.post<{ ok: boolean; message?: string; orderNo?: string }>('/api/erp/work-orders', b),
  update: (orderNo: string, b: Partial<WorkOrderInput>) =>
    http.put<{ ok: boolean; message?: string }>(`/api/erp/work-orders/${encodeURIComponent(orderNo)}`, b),
  release: (orderNo: string) =>
    http.post<{ ok: boolean; message?: string; materialCount?: number }>(
      `/api/erp/work-orders/${encodeURIComponent(orderNo)}/release`, {}),
  start: (orderNo: string) =>
    http.post<{ ok: boolean; message?: string; deviceCode?: string }>(
      `/api/erp/work-orders/${encodeURIComponent(orderNo)}/start`, {}),
  finish: (orderNo: string) =>
    http.post<{ ok: boolean; message?: string; doneQty?: number; defectQty?: number }>(
      `/api/erp/work-orders/${encodeURIComponent(orderNo)}/finish`, {}),
  cancel: (orderNo: string, reason?: string) =>
    http.post<{ ok: boolean; message?: string }>(
      `/api/erp/work-orders/${encodeURIComponent(orderNo)}/cancel`, { reason }),
  issue: (orderNo: string, warehouseId: number, lines: { skuId: number; qty: number }[]) =>
    http.post<{ ok: boolean; message?: string; billNo?: string }>(
      `/api/erp/work-orders/${encodeURIComponent(orderNo)}/issue`, { warehouseId, lines }),
  autoIssue: (orderNo: string, warehouseId?: number | null) =>
    http.post<{ ok: boolean; message?: string; billNo?: string }>(
      `/api/erp/work-orders/${encodeURIComponent(orderNo)}/auto-issue`, { warehouseId })
}

export const WORK_ORDER_STATUSES: { value: WorkOrderStatus; label: string; type: 'info' | 'primary' | 'warning' | 'success' | 'danger' }[] = [
  { value: 'DRAFT', label: '草稿', type: 'info' },
  { value: 'RELEASED', label: '已下达', type: 'primary' },
  { value: 'RUNNING', label: '生产中', type: 'warning' },
  { value: 'DONE', label: '已完工', type: 'success' },
  { value: 'CANCELLED', label: '已取消', type: 'danger' }
]

export const workOrderStatusType = (s: string) =>
  WORK_ORDER_STATUSES.find((x) => x.value === s)?.type || 'info'

/* ---------- 生产工单排程（DH-72）：与后端 ErpScheduleController 对齐 ---------- */

/** 甘特数据：按产线分组 + 未排程清单 + 全部冲突 */
export interface ScheduleOrderRow {
  orderNo: string
  productCode?: string
  productName?: string
  planQty?: number
  doneQty?: number
  status?: string
  lineCode?: string
  deviceCode?: string
  planStart?: number | null
  planEnd?: number | null
  conflict?: boolean
}
export interface ScheduleConflict {
  lineCode: string
  aOrderNo: string
  bOrderNo: string
  overlapStart: number
  overlapEnd: number
}
export interface ScheduleGantt {
  lines: { lineCode: string; orders: ScheduleOrderRow[] }[]
  unscheduled: ScheduleOrderRow[]
  conflicts: ScheduleConflict[]
}

export const scheduleApi = {
  gantt: (from?: number | null, to?: number | null) =>
    http.get<ScheduleGantt>(
      `/api/erp/schedule${from || to ? `?from=${from ?? ''}&to=${to ?? ''}` : ''}`),
  conflicts: () => http.get<{ ok: boolean; count: number; conflicts: ScheduleConflict[] }>(
    '/api/erp/schedule/conflicts'),
  /** 排程。force=true 时即使与同产线的工单时间重叠也排（只提示） */
  schedule: (orderNo: string, planStart: number, planEnd: number, force = false, lineCode?: string) =>
    http.post<{ ok: boolean; message?: string; conflicts?: ScheduleConflict[]; warnings?: string[] }>(
      `/api/erp/schedule/${encodeURIComponent(orderNo)}`,
      { planStart, planEnd, force, lineCode: lineCode ?? null }),
  unschedule: (orderNo: string) =>
    http.del<{ ok: boolean; message?: string }>(
      `/api/erp/schedule/${encodeURIComponent(orderNo)}`)
}
