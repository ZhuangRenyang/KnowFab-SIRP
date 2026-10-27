import { http } from './http'

/* 采购管理契约（DH-52）：与后端 ErpPurchaseController 对齐。
   订单本身不动库存，入库/退货才生成库存单据；进度由后端按单据重算，前端只负责显示。 */

export interface PurchaseOrderRow {
  orderNo: string
  supplierId?: number | null
  supplierName?: string | null
  status: string
  statusLabel: string
  orderDate: number
  expectDate?: number | null
  totalQty: number
  totalAmount: number
  receivedQty: number
  progress: number
  overdue: boolean
  operator?: string | null
  remark?: string | null
}

export interface PurchaseOrderLine {
  itemId: number
  lineNo: number
  skuId: number
  skuCode: string
  skuName: string
  spec?: string | null
  unit?: string | null
  qty: number
  receivedQty: number
  remainQty: number
  unitPrice?: number | null
  amount?: number | null
  remark?: string | null
}

export interface PurchaseBillRow {
  billNo: string
  type: string
  typeLabel: string
  qty: number
  amount: number
  createdAt: number
  operator?: string | null
  voided: boolean
}

export interface PurchaseOrderHead {
  orderNo: string
  supplierId?: number | null
  supplierName?: string | null
  status: string
  statusLabel: string
  orderDate: number
  expectDate?: number | null
  totalQty: number
  totalAmount: number
  receivedQty: number
  progress: number
  remark?: string | null
  operator?: string | null
  createdAt: number
  updatedAt: number
  editable: boolean
  receivable: boolean
}

export interface PurchaseStats {
  ok: boolean
  monthAmount: number
  openCount: number
  partialCount: number
  overdueCount: number
  draftCount: number
  statusOptions: { value: string; label: string }[]
}

export interface PurchaseLineInput {
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

export const purchaseApi = {
  stats: () => http.get<PurchaseStats>('/api/erp/purchase/stats'),
  list: (p: {
    status?: string; supplierId?: number | null; from?: number; to?: number
    kw?: string; page?: number; size?: number
  } = {}) => http.get<{ ok: boolean; total: number; page: number; size: number; rows: PurchaseOrderRow[] }>(
    '/api/erp/purchase/orders' + qsOf({ ...p, page: p.page || 1, size: p.size || 20 })),
  detail: (orderNo: string) => http.get<{
    ok: boolean
    order: PurchaseOrderHead
    lines: PurchaseOrderLine[]
    bills: PurchaseBillRow[]
  }>(`/api/erp/purchase/orders/${encodeURIComponent(orderNo)}`),
  create: (b: {
    supplierId: number; orderDate?: string; expectDate?: string; remark?: string
    lines: PurchaseLineInput[]
  }) => http.post<{ ok: boolean; message?: string; orderNo?: string }>('/api/erp/purchase/orders', b),
  update: (orderNo: string, b: {
    supplierId?: number; orderDate?: string; expectDate?: string; remark?: string
    lines: PurchaseLineInput[]
  }) => http.put<{ ok: boolean; message?: string }>(`/api/erp/purchase/orders/${encodeURIComponent(orderNo)}`, b),
  confirm: (orderNo: string) =>
    http.post<{ ok: boolean; message?: string }>(`/api/erp/purchase/orders/${encodeURIComponent(orderNo)}/confirm`, {}),
  cancel: (orderNo: string) =>
    http.post<{ ok: boolean; message?: string }>(`/api/erp/purchase/orders/${encodeURIComponent(orderNo)}/cancel`, {}),
  receive: (orderNo: string, b: { warehouseId: number; remark?: string; lines: PurchaseLineInput[] }) =>
    http.post<{ ok: boolean; message?: string; billNo?: string }>(
      `/api/erp/purchase/orders/${encodeURIComponent(orderNo)}/receive`, b),
  returnTo: (orderNo: string, b: { warehouseId: number; remark?: string; lines: PurchaseLineInput[] }) =>
    http.post<{ ok: boolean; message?: string; billNo?: string }>(
      `/api/erp/purchase/orders/${encodeURIComponent(orderNo)}/return`, b),
  open: (limit = 10) => http.get<PurchaseOrderRow[]>(`/api/erp/purchase/open?limit=${limit}`)
}

export const PURCHASE_STATUS_TAG: Record<string, string> = {
  DRAFT: 'info',
  CONFIRMED: 'primary',
  PARTIAL: 'warning',
  DONE: 'success',
  CANCELLED: 'info'
}
