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
  daily: (code: string, days = 30) =>
    http.get<{ date: string; qty: number }[]>(`/api/erp/prod/daily?code=${encodeURIComponent(code)}&days=${days}`),
  saveConfig: (b: { code: string; skuId?: number | null; warehouseId?: number | null; resetHour: number; auto: boolean }) =>
    http.post<{ ok: boolean; message?: string }>('/api/erp/prod/config', b)
}
