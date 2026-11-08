import { http } from './http'

/* 销售管理契约（DH-45b）：与后端 ErpSalesController 对齐。
   订单不动库存，发货/退货才生成库存单据；进度与应收口径由后端算，前端只显示。 */

export interface SalesOrderRow {
  orderNo: string
  customerId?: number | null
  customerName?: string | null
  status: string
  statusLabel: string
  orderDate: number
  deliverDate?: number | null
  totalQty: number
  totalAmount: number
  shippedQty: number
  progress: number
  overdue: boolean
  operator?: string | null
  remark?: string | null
}

export interface SalesOrderLine {
  itemId: number
  lineNo: number
  skuId: number
  skuCode: string
  skuName: string
  spec?: string | null
  unit?: string | null
  qty: number
  shippedQty: number
  remainQty: number
  unitPrice?: number | null
  amount?: number | null
  remark?: string | null
}

export interface SalesBillRow {
  billNo: string
  type: string
  typeLabel: string
  qty: number
  amount: number
  createdAt: number
  operator?: string | null
  voided: boolean
}

/**
 * 这张销售单关联的生产工单。
 *
 * 以前销售单只有一个自由填写的 `srcOrderNo`，销售看不到「我这张单生产到哪了」，
 * 生产也不知道「这单是给谁做的」——客户催单时两边都答不上来。
 */
export interface SalesWorkOrder {
  orderNo: string
  status: string
  statusLabel: string
  productSkuId: number
  productCode?: string | null
  productName?: string | null
  planQty: number
  doneQty: number
  /** 完工百分比（已按 1 位小数取整） */
  progress: number
  lineCode?: string | null
  deviceCode?: string | null
  planStart?: number | null
  planEnd?: number | null
}

export interface SalesOrderHead {
  orderNo: string
  customerId?: number | null
  customerName?: string | null
  status: string
  statusLabel: string
  orderDate: number
  deliverDate?: number | null
  totalQty: number
  totalAmount: number
  shippedQty: number
  shippedAmount: number
  progress: number
  remark?: string | null
  operator?: string | null
  createdAt: number
  updatedAt: number
  editable: boolean
  shippable: boolean
}

export interface SalesStats {
  ok: boolean
  monthAmount: number
  openCount: number
  partialCount: number
  overdueCount: number
  draftCount: number
  statusOptions: { value: string; label: string }[]
}

export interface SalesLineInput {
  itemId?: number
  skuId?: number
  qty: number
  unitPrice?: number | null
  remark?: string
}

const qsOf = (p: Record<string, string | number | undefined | null | boolean>) => {
  const qs = new URLSearchParams()
  Object.entries(p).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '' || v === false) return
    qs.set(k, String(v))
  })
  const s = qs.toString()
  return s ? '?' + s : ''
}

export const salesApi = {
  stats: () => http.get<SalesStats>('/api/erp/sales/stats'),
  list: (p: {
    status?: string; customerId?: number | null; from?: number; to?: number
    kw?: string; page?: number; size?: number
  } = {}) => http.get<{ ok: boolean; total: number; page: number; size: number; rows: SalesOrderRow[] }>(
    '/api/erp/sales/orders' + qsOf({ ...p, page: p.page || 1, size: p.size || 20 })),
  detail: (orderNo: string) => http.get<{
    ok: boolean
    order: SalesOrderHead
    lines: SalesOrderLine[]
    bills: SalesBillRow[]
    /* 关联的生产工单（按 srcOrderNo 反查）：客户催单时唯一要回答的问题 */
    workOrders: SalesWorkOrder[]
  }>(`/api/erp/sales/orders/${encodeURIComponent(orderNo)}`),

  /**
   * 按订单行生成生产工单（销售 ↔ 生产联动）。
   *
   * 不传 planQty 时按「订货 − 已发货 − 已排产」的剩余量建单；
   * 超过剩余量会被后端拒绝，防止同一批货排两遍。
   * 落地是**草稿工单**，还要人工下达。
   */
  makeWorkOrder: (orderNo: string, b: { itemId: number; planQty?: number }) =>
    http.post<{ ok: boolean; message?: string; orderNo?: string; remainingQty?: number }>(
      `/api/erp/sales/orders/${encodeURIComponent(orderNo)}/make-work-order`, b),
  create: (b: {
    customerId: number; orderDate?: string; deliverDate?: string; remark?: string
    lines: SalesLineInput[]
  }) => http.post<{ ok: boolean; message?: string; orderNo?: string }>('/api/erp/sales/orders', b),
  update: (orderNo: string, b: {
    customerId?: number; orderDate?: string; deliverDate?: string; remark?: string
    lines: SalesLineInput[]
  }) => http.put<{ ok: boolean; message?: string }>(`/api/erp/sales/orders/${encodeURIComponent(orderNo)}`, b),
  confirm: (orderNo: string) =>
    http.post<{ ok: boolean; message?: string }>(`/api/erp/sales/orders/${encodeURIComponent(orderNo)}/confirm`, {}),
  cancel: (orderNo: string) =>
    http.post<{ ok: boolean; message?: string }>(`/api/erp/sales/orders/${encodeURIComponent(orderNo)}/cancel`, {}),
  ship: (orderNo: string, b: { warehouseId: number; remark?: string; lines: SalesLineInput[] }) =>
    http.post<{ ok: boolean; message?: string; billNo?: string }>(
      `/api/erp/sales/orders/${encodeURIComponent(orderNo)}/ship`, b),
  /**
   * 客户退货。
   *
   * `returnReason` 必选、无默认值（QUALITY-SPEC §3.5、AC-14）：
   * 默认 QUALITY → 队列全是噪音；默认 OTHER → 质量退货被系统性漏掉。
   * 后端收到 QUALITY 会生成待处置的不良记录并把货放进禁发仓；
   * OTHER 直接回良品仓、不生成记录。
   */
  returnFrom: (orderNo: string, b: {
    warehouseId: number
    remark?: string
    returnReason?: 'QUALITY' | 'OTHER'
    lines: SalesLineInput[]
  }) =>
    http.post<{ ok: boolean; message?: string; billNo?: string }>(
      `/api/erp/sales/orders/${encodeURIComponent(orderNo)}/return`, b),
  open: (limit = 10) => http.get<SalesOrderRow[]>(`/api/erp/sales/open?limit=${limit}`)
}

export const SALES_STATUS_TAG: Record<string, string> = {
  DRAFT: 'info',
  CONFIRMED: 'primary',
  PARTIAL: 'warning',
  DONE: 'success',
  CANCELLED: 'info'
}
