/* 销售查询契约（后端 ErpSalesQueryController）。
   只读：现存量 / 订单占用 / 可承诺量 / 所在仓库；**不含成本**，可以直接导出给客户。 */

import { http } from './http'

export interface StockWarehouse {
  id: number
  name: string
  qty: number
}

export interface SalesStockRow {
  skuId: number
  code: string
  name: string
  spec?: string | null
  unit?: string | null
  category?: string | null
  salePrice?: number | null
  onHand: number
  allocated: number
  available: number
  /** 欠交：已被订单占用但库存不够发（可承诺截到 0 时仍要能看到缺口） */
  shortage: number
  /** OK 充足 / LOW 紧张（低于库存下限）/ OUT 已无可承诺 */
  state: 'OK' | 'LOW' | 'OUT'
  warehouses: StockWarehouse[]
  warehouseText: string
}

export interface SalesStockResult {
  ok: boolean
  rows: SalesStockRow[]
  totals: { skuCount: number; onHand: number; allocated: number; available: number }
  warehouses: { id: number; name: string }[]
  note: string
}

const query = (p: { kw?: string; category?: string; warehouseId?: number | null; onlyAvailable?: boolean }) => {
  const q = new URLSearchParams()
  if (p.kw && p.kw.trim()) q.set('kw', p.kw.trim())
  if (p.category) q.set('category', p.category)
  if (p.warehouseId) q.set('warehouseId', String(p.warehouseId))
  if (p.onlyAvailable) q.set('onlyAvailable', 'true')
  const s = q.toString()
  return s ? `?${s}` : ''
}

export const salesQueryApi = {
  stock: (p: { kw?: string; category?: string; warehouseId?: number | null; onlyAvailable?: boolean }) =>
    http.get<SalesStockResult>('/api/erp/sales-query/stock' + query(p))
}

/** 导出 xlsx：fetch blob（失败时后端回 JSON，window.open 会把 JSON 当页面打开） */
export async function exportSalesStock(p: {
  kw?: string; category?: string; warehouseId?: number | null; onlyAvailable?: boolean
}): Promise<void> {
  const res = await fetch('/api/erp/sales-query/export' + query(p))
  if (!res.ok) {
    let msg = `导出失败（${res.status}）`
    try {
      const j = await res.json()
      msg = j.message || msg
    } catch {
      /* 非 JSON */
    }
    throw new Error(msg)
  }
  const blob = await res.blob()
  const cd = res.headers.get('content-disposition') || ''
  let name = '销售库存查询.xlsx'
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
