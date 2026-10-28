import { http } from './http'

/* ERP 基础资料契约：与后端 ErpBaseController 对齐。
   错误统一 200 + {ok:false,message}，写权限 ADMIN/MANAGER（后端按 HTTP 方法收口）。 */

export interface ErpSku {
  id: number
  code: string
  name: string
  category?: string | null
  unit?: string | null
  spec?: string | null
  barcode?: string | null
  purchasePrice?: number | null
  salePrice?: number | null
  minStock?: number | null
  maxStock?: number | null
  remark?: string | null
  enabled: boolean
  updatedAt: number
}

export interface ErpWarehouse {
  id: number
  code: string
  name: string
  address?: string | null
  manager?: string | null
  phone?: string | null
  remark?: string | null
  default: boolean
  enabled: boolean
  updatedAt: number
}

export interface ErpPartner {
  id: number
  code: string
  name: string
  type: string
  contact?: string | null
  phone?: string | null
  address?: string | null
  remark?: string | null
  enabled: boolean
  updatedAt: number
}

const qs = (q?: string) => (q && q.trim() ? `?q=${encodeURIComponent(q.trim())}` : '')

export const skuApi = {
  list: (q?: string) => http.get<ErpSku[]>(`/api/erp/skus${qs(q)}`),
  create: (b: Partial<ErpSku>) => http.post<{ ok: boolean; message?: string }>('/api/erp/skus', b),
  update: (id: number, b: Partial<ErpSku>) => http.put<{ ok: boolean; message?: string }>(`/api/erp/skus/${id}`, b),
  remove: (id: number) => http.del<{ ok: boolean; message?: string }>(`/api/erp/skus/${id}`)
}

export const warehouseApi = {
  list: (q?: string) => http.get<ErpWarehouse[]>(`/api/erp/warehouses${qs(q)}`),
  create: (b: Partial<ErpWarehouse>) => http.post<{ ok: boolean; message?: string }>('/api/erp/warehouses', b),
  update: (id: number, b: Partial<ErpWarehouse>) => http.put<{ ok: boolean; message?: string }>(`/api/erp/warehouses/${id}`, b),
  remove: (id: number) => http.del<{ ok: boolean; message?: string }>(`/api/erp/warehouses/${id}`)
}

export const partnerApi = {
  list: (q?: string, type?: string) => {
    const p = new URLSearchParams()
    if (q && q.trim()) p.set('q', q.trim())
    if (type && type !== 'ALL') p.set('type', type)
    const s = p.toString()
    return http.get<ErpPartner[]>(`/api/erp/partners${s ? '?' + s : ''}`)
  },
  create: (b: Partial<ErpPartner>) => http.post<{ ok: boolean; message?: string }>('/api/erp/partners', b),
  update: (id: number, b: Partial<ErpPartner>) => http.put<{ ok: boolean; message?: string }>(`/api/erp/partners/${id}`, b),
  remove: (id: number) => http.del<{ ok: boolean; message?: string }>(`/api/erp/partners/${id}`)
}

export const PARTNER_TYPES = [
  { value: 'SUPPLIER', label: '供应商' },
  { value: 'CUSTOMER', label: '客户' },
  { value: 'BOTH', label: '两者皆是' }
]

export const partnerTypeLabel = (t: string) => PARTNER_TYPES.find((x) => x.value === t)?.label || t

/* ---------- DH-44 生产入库联动：机器级配置 + 产量统计 ---------- */

export interface ProdStatus {
  ok?: boolean
  auto: boolean
  skuId?: number | null
  warehouseId?: number | null
  skuName?: string | null
  warehouseName?: string | null
  resetHour: number
  hasCount: boolean
  today: number
  month: number
  year: number
  months: { month: number; qty: number }[]
}

export const prodApi = {
  status: (code: string) =>
    http.get<ProdStatus>(`/api/erp/prod/status?code=${encodeURIComponent(code)}`),
  /** 产量图表：day=当日24小时 / month=当月每天 / year=今年12个月 / all=近365天（前端缩放） */
  chart: (code: string, mode: 'day' | 'month' | 'year' | 'all') =>
    http.get<{ ok: boolean; mode: string; resetHour: number; points: { label: string; qty: number }[] }>(
      `/api/erp/prod/chart?code=${encodeURIComponent(code)}&mode=${mode}`),
  saveConfig: (b: { code: string; skuId?: number | null; warehouseId?: number | null; resetHour: number; auto: boolean }) =>
    http.post<{ ok: boolean; message?: string }>('/api/erp/prod/config', b)
}

/* ---------- DH-43b 库存与出入库单据 ---------- */

export interface StockBalance {
  skuId: number
  code: string
  name: string
  spec?: string | null
  category?: string | null
  unit?: string | null
  warehouseId: number
  warehouseName: string
  qty: number
  unitCost: number
  amount: number
  minStock?: number | null
  maxStock?: number | null
}

export interface StockAlert {
  skuId: number
  code: string
  name: string
  spec?: string | null
  unit?: string | null
  total: number
  minStock?: number | null
  maxStock?: number | null
  status: 'LOW' | 'HIGH'
  gap: number
  byWarehouse: { warehouseId: number; warehouseName: string; qty: number }[]
}

export interface StockBillRow {
  billNo: string
  type: string
  typeLabel: string
  partnerName?: string | null
  srcWarehouseName?: string | null
  warehouseName?: string | null
  deviceCode?: string | null
  srcOrderNo?: string | null
  qty: number
  amount: number
  lineCount: number
  operator?: string | null
  remark?: string | null
  createdAt: number
  voided: boolean
  voidedBy?: string | null
}

export interface StockBillLine {
  lineNo: number
  skuId: number
  skuCode: string
  skuName: string
  spec?: string | null
  unit?: string | null
  warehouseName: string
  qty: number
  unitPrice?: number | null
  amount?: number | null
}

export interface StockFlowRow {
  createdAt: number
  billNo: string
  type: string
  typeLabel: string
  warehouseName?: string | null
  qty: number
  balance: number
  deviceCode?: string | null
}

export interface StockSummary {
  ok: boolean
  skuCount: number
  stockRowCount: number
  whCount: number
  totalQty: number
  totalValue: number
  alertCount: number
  todayIn: number
  todayOut: number
  monthIn: number
  monthOut: number
  todayBills: number
  prodToday: number
}

export interface BillLineInput {
  skuId: number
  qty: number
  unitPrice?: number | null
}

export const stockApi = {
  types: () => http.get<{ all: { value: string; label: string }[]; manual: string[] }>('/api/erp/stock/types'),
  summary: () => http.get<StockSummary>('/api/erp/stock/summary'),
  trend: (days = 30) => http.get<{ date: string; in: number; out: number }[]>(`/api/erp/stock/trend?days=${days}`),
  balances: (p: { warehouseId?: number | null; q?: string } = {}) => {
    const qs = new URLSearchParams()
    if (p.warehouseId) qs.set('warehouseId', String(p.warehouseId))
    if (p.q && p.q.trim()) qs.set('q', p.q.trim())
    const s = qs.toString()
    return http.get<StockBalance[]>(`/api/erp/stock/balances${s ? '?' + s : ''}`)
  },
  alerts: () => http.get<StockAlert[]>('/api/erp/stock/alerts'),
  flow: (skuId: number, warehouseId: number, limit = 60) =>
    http.get<StockFlowRow[]>(`/api/erp/stock/flow?skuId=${skuId}&warehouseId=${warehouseId}&limit=${limit}`),
  bills: (p: {
    type?: string; from?: number; to?: number; withProd?: boolean
    kw?: string; page?: number; size?: number
  } = {}) => {
    const qs = new URLSearchParams()
    if (p.type) qs.set('type', p.type)
    if (p.from) qs.set('from', String(p.from))
    if (p.to) qs.set('to', String(p.to))
    if (p.withProd) qs.set('withProd', 'true')
    if (p.kw && p.kw.trim()) qs.set('kw', p.kw.trim())
    qs.set('page', String(p.page || 1))
    qs.set('size', String(p.size || 20))
    return http.get<{ ok: boolean; total: number; page: number; size: number; rows: StockBillRow[] }>(
      `/api/erp/stock/bills?${qs.toString()}`)
  },
  billDetail: (billNo: string) =>
    http.get<{ ok: boolean; bill: StockBillRow & { voidedAt?: number | null }; lines: StockBillLine[] }>(
      `/api/erp/stock/bills/${encodeURIComponent(billNo)}`),
  createBill: (b: {
    type: string; partnerId?: number | null; warehouseId: number
    srcWarehouseId?: number | null; remark?: string; date?: string; lines: BillLineInput[]
  }) => http.post<{ ok: boolean; message?: string; billNo?: string; lineCount?: number }>('/api/erp/stock/bills', b),
  stocktake: (b: { warehouseId: number; remark?: string; lines: BillLineInput[] }) =>
    http.post<{ ok: boolean; message?: string; billNo?: string; noDiff?: boolean }>('/api/erp/stock/stocktake', b),
  voidBill: (billNo: string) =>
    http.post<{ ok: boolean; message?: string }>(`/api/erp/stock/bills/${encodeURIComponent(billNo)}/void`, {})
}
