/* 经营报表契约（后端 ErpReportController）。
   日期参数统一 yyyy-MM-dd，两端都含；不传则后端默认本月 1 号到今天。 */

import { http } from './http'

export interface PartnerRank {
  partnerId?: number | null
  name: string
  amount: number
}

export interface TopSku {
  skuId: number
  code: string
  name: string
  spec?: string | null
  unit?: string | null
  inQty: number
  outQty: number
  amount: number
}

export interface TrendRow {
  date: string
  purchase: number
  sales: number
}

export interface Overview {
  ok: boolean
  fromDate: string
  toDate: string
  purchaseAmount: number
  salesAmount: number
  poOrderAmount: number
  soOrderAmount: number
  poCount: number
  soCount: number
  prodInQty: number
  stockValue: number
  stockRows: number
  skuCount: number
  whCount: number
  arOutstanding: number
  arOverdue: number
  apOutstanding: number
  apOverdue: number
  trend: TrendRow[]
  topCustomers: PartnerRank[]
  topSuppliers: PartnerRank[]
  topSkus: TopSku[]
}

export interface StockRow {
  skuId: number
  code: string
  name: string
  spec?: string | null
  unit?: string | null
  category?: string | null
  warehouseId: number
  warehouseName: string
  opening: number
  inQty: number
  outQty: number
  closing: number
  unitCost: number
  openingAmount: number
  inAmount: number
  outAmount: number
  amount: number
}

export interface StockReport {
  ok: boolean
  fromDate: string
  toDate: string
  warehouseId?: number | null
  warehouseName: string
  rows: StockRow[]
  totals: {
    skuCount: number
    openingAmount: number
    inAmount: number
    outAmount: number
    amount: number
    closingQty: number
  }
  note: string
}

export interface FlowTypeRow {
  type: string
  typeLabel: string
  bills: number
  inQty: number
  outQty: number
  inAmount: number
  outAmount: number
  netAmount: number
}

export interface FlowWarehouseRow {
  warehouseId: number
  warehouseName: string
  bills: number
  inQty: number
  outQty: number
  inAmount: number
  outAmount: number
}

export interface FlowDayRow {
  date: string
  inQty: number
  outQty: number
  inAmount: number
  outAmount: number
}

export interface FlowReport {
  ok: boolean
  fromDate: string
  toDate: string
  byType: FlowTypeRow[]
  byWarehouse: FlowWarehouseRow[]
  daily: FlowDayRow[]
  topSkus: TopSku[]
  totals: {
    bills: number
    inQty: number
    outQty: number
    inAmount: number
    outAmount: number
    netAmount: number
  }
}

export interface TradeOrderRow {
  orderNo: string
  orderDate: number
  partnerName: string
  status: string
  statusLabel: string
  orderAmount: number
  executedAmount: number
  openAmount: number
  rate: number
}

export interface TradePartnerRow {
  partnerId: number
  partnerName: string
  orders: number
  orderAmount: number
  executedAmount: number
  openAmount: number
  rate: number
}

export interface TradeReport {
  ok: boolean
  side: 'purchase' | 'sales'
  sideLabel: string
  partnerLabel: string
  fromDate: string
  toDate: string
  orders: TradeOrderRow[]
  partners: TradePartnerRow[]
  totals: {
    orders: number
    partners: number
    orderAmount: number
    executedAmount: number
    openAmount: number
    rate: number
  }
}

const range = (from: string, to: string) => `from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`

export const reportApi = {
  overview: (from: string, to: string) => http.get<Overview>(`/api/erp/reports/overview?${range(from, to)}`),
  stock: (from: string, to: string, whId?: number | null) =>
    http.get<StockReport>(`/api/erp/reports/stock?${range(from, to)}${whId ? `&warehouseId=${whId}` : ''}`),
  flows: (from: string, to: string, whId?: number | null) =>
    http.get<FlowReport>(`/api/erp/reports/flows?${range(from, to)}${whId ? `&warehouseId=${whId}` : ''}`),
  trade: (side: 'purchase' | 'sales', from: string, to: string) =>
    http.get<TradeReport>(`/api/erp/reports/trade?side=${side}&${range(from, to)}`)
}

/**
 * 下载 xlsx：用 fetch 拿 blob 而不是 window.open，
 * 因为失败时后端返回的是 JSON（"导出失败：原因"），
 * window.open 会把这个 JSON 当页面打开，客户看到一屏乱码。
 */
export async function downloadReport(
  type: 'overview' | 'stock' | 'flows' | 'trade',
  from: string,
  to: string,
  opts: { warehouseId?: number | null; side?: 'purchase' | 'sales' } = {}
): Promise<void> {
  const p = new URLSearchParams({ type, from, to })
  if (opts.warehouseId) p.set('warehouseId', String(opts.warehouseId))
  if (opts.side) p.set('side', opts.side)
  const res = await fetch(`/api/erp/reports/export?${p.toString()}`)
  if (!res.ok) {
    let msg = `导出失败（${res.status}）`
    try {
      const j = await res.json()
      msg = j.message || msg
    } catch {
      /* 非 JSON，保留状态码提示 */
    }
    throw new Error(msg)
  }
  const blob = await res.blob()
  const cd = res.headers.get('content-disposition') || ''
  let name = `经营报表-${from}_${to}.xlsx`
  const star = /filename\*=UTF-8''([^;]+)/i.exec(cd)
  if (star) {
    try {
      name = decodeURIComponent(star[1])
    } catch {
      /* 保留默认名 */
    }
  }
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = name
  document.body.appendChild(a)
  a.click()
  window.setTimeout(() => {
    URL.revokeObjectURL(a.href)
    a.remove()
  }, 1000)
}
