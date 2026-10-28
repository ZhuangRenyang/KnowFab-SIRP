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
  }>(`/api/erp/sales/orders/${encodeURIComponent(orderNo)}`),
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
  returnFrom: (orderNo: string, b: { warehouseId: number; remark?: string; lines: SalesLineInput[] }) =>
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
