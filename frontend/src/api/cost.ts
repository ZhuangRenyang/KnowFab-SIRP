import { http } from './http'

/* 成本核算契约：与后端 ErpCostController 对齐。
   口径：材料=实际领料金额，人工=设备运行工时×工价，制造费用=电费+工时×费率。
   算不出来一律返回 null（不是 0）——null 表示"不知道"，0 表示"确实是零成本"。 */

export interface OrderCost {
  ok: boolean
  orderNo: string
  productSkuId: number
  productName: string | null
  status: string
  doneQty: number
  defectQty: number

  materialCost: number
  /** 工价没配时为 null：不是"零人工"，是"没算" */
  laborCost: number | null
  overheadCost: number
  electricityCost: number
  overheadRateCost: number
  totalCost: number
  /** 完成量为 0 时为 null */
  unitCost: number | null

  /** 按 BOM 单件材料成本 × 完成量算出来的，用来看材料超耗 */
  standardMaterialCost: number | null
  /** 实际 - 标准，正数=超耗 */
  materialVariance: number | null

  hours: number
  hoursSource: 'device' | 'wallclock' | 'none'
  hoursSourceLabel: string
  kwh: number
  laborRate: number
  overheadRate: number

  inProgress: boolean
  /** 成本是否不完整（人工没算进去） */
  partial: boolean
  note: string | null
}

export interface ProductCost {
  skuId: number
  code: string | null
  name: string
  unit: string | null
  orders: number
  qty: number
  materialCost: number
  materialUnit: number | null
  laborCost: number
  laborUnit: number | null
  overheadCost: number
  overheadUnit: number | null
  totalCost: number
  unitCost: number | null
  salePrice: number | null
  /** 售价 - 单位成本；任一为 null 时为 null */
  grossProfit: number | null
  grossPct: number | null
  partial: boolean
}

export interface ProductCostResult {
  ok: boolean
  from: string
  to: string
  rows: ProductCost[]
  total: number
  /** 在制/待开工工单的金额，不进单位成本 */
  openCost: number
  openCount: number
  note: string
}

export interface CostOverview {
  ok: boolean
  from: string
  to: string
  orders: number
  outputQty: number
  materialCost: number
  laborCost: number
  overheadCost: number
  totalCost: number
  unitCost: number | null
  materialPct: number | null
  laborPct: number | null
  overheadPct: number | null
  partial: boolean
  note: string
}

export const costApi = {
  overview: (from?: string, to?: string) => {
    const q = new URLSearchParams()
    if (from) q.set('from', from)
    if (to) q.set('to', to)
    const s = q.toString()
    return http.get<CostOverview>(`/api/erp/cost/overview${s ? '?' + s : ''}`)
  },
  products: (from?: string, to?: string) => {
    const q = new URLSearchParams()
    if (from) q.set('from', from)
    if (to) q.set('to', to)
    const s = q.toString()
    return http.get<ProductCostResult>(`/api/erp/cost/products${s ? '?' + s : ''}`)
  },
  order: (orderNo: string) =>
    http.get<OrderCost>(`/api/erp/cost/orders/${encodeURIComponent(orderNo)}`)
}
