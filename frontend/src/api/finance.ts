import { http } from './http'

/* 财务契约（DH-54）：与后端 ErpFinanceController 对齐。
   应收 = 销售订单已发货金额；应付 = 采购订单已入库金额；
   未收/未付 = 应收/应付 − 已核销收付款；预收/预付 = 未关联订单的收付款。 */

export interface AgingBucket {
  bucket: string
  amount: number
}

export interface FinanceSummary {
  ok: boolean
  arTotal: number
  arPaid: number
  arOutstanding: number
  arOverdue: number
  arPre: number
  apTotal: number
  apPaid: number
  apOutstanding: number
  apOverdue: number
  apPre: number
  monthIn: number
  monthOut: number
  arAging: AgingBucket[]
  apAging: AgingBucket[]
  creditDays: number
  methods: string[]
}

export interface FinanceRow {
  orderNo: string
  partnerId?: number | null
  partnerName?: string | null
  orderDate: number
  totalAmount: number
  billedAmount: number
  paidAmount: number
  outstanding: number
  days: number
  bucket: string
  overdue: boolean
}

export interface PaymentRow {
  payNo: string
  direction: 'IN' | 'OUT'
  directionLabel: string
  partnerId?: number | null
  partnerName?: string | null
  orderType?: string | null
  orderNo?: string | null
  amount: number
  method?: string | null
  payDate: number
  remark?: string | null
  operator?: string | null
  voided: boolean
  voidedBy?: string | null
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

export const financeApi = {
  summary: () => http.get<FinanceSummary>('/api/erp/finance/summary'),
  receivables: (p: { partnerId?: number | null; onlyOpen?: boolean; kw?: string; page?: number; size?: number } = {}) =>
    http.get<{ ok: boolean; total: number; page: number; size: number; rows: FinanceRow[]; sumOutstanding: number; sumBilled: number; aging: AgingBucket[] }>(
      '/api/erp/finance/receivables' + qsOf({ ...p, page: p.page || 1, size: p.size || 20 })),
  payables: (p: { partnerId?: number | null; onlyOpen?: boolean; kw?: string; page?: number; size?: number } = {}) =>
    http.get<{ ok: boolean; total: number; page: number; size: number; rows: FinanceRow[]; sumOutstanding: number; sumBilled: number; aging: AgingBucket[] }>(
      '/api/erp/finance/payables' + qsOf({ ...p, page: p.page || 1, size: p.size || 20 })),
  payments: (p: {
    direction?: string; partnerId?: number | null; from?: number; to?: number
    kw?: string; withVoided?: boolean; page?: number; size?: number
  } = {}) =>
    http.get<{ ok: boolean; total: number; page: number; size: number; rows: PaymentRow[] }>(
      '/api/erp/finance/payments' + qsOf({ ...p, page: p.page || 1, size: p.size || 20 })),
  partners: (type: 'CUSTOMER' | 'SUPPLIER') =>
    http.get<{ partnerId: number; partnerName: string; billed: number; paid: number; outstanding: number; prepaid: number }[]>(
      `/api/erp/finance/partners?type=${type}`),
  createPayment: (b: {
    direction: 'IN' | 'OUT'; partnerId: number; orderNo?: string | null
    amount: number; method?: string; payDate?: string; remark?: string
  }) => http.post<{ ok: boolean; message?: string; payNo?: string }>('/api/erp/finance/payments', b),
  voidPayment: (payNo: string) =>
    http.post<{ ok: boolean; message?: string }>(`/api/erp/finance/payments/${encodeURIComponent(payNo)}/void`, {})
}

export const DIR_TAG: Record<string, string> = { IN: 'success', OUT: 'warning' }
