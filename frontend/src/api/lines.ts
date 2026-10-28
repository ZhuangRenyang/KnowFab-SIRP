/* 产线管理契约（后端 ErpLineController）。
   产量口径：产线产量 = 成品计数设备的计数器增量，设备自身计数不变。 */

import { http } from './http'

export interface ProdLineRow {
  id: number
  code: string
  name: string
  workshop?: string | null
  sortOrder: number
  enabled: boolean
  remark?: string | null
  deviceCount: number
  onlineCount: number
  finishedCode?: string | null
  finishedName?: string | null
  /** true = 没指定，取工位序号最大的一台 */
  finishedAuto: boolean
  finishedHasCount: boolean
  today: number
  month: number
  year: number
}

export interface ProdLineSummary {
  lines: number
  devices: number
  online: number
  withFinished: number
  today: number
  month: number
}

export interface ProdLineList {
  ok: boolean
  rows: ProdLineRow[]
  summary: ProdLineSummary
  unassigned: number
}

export interface LineDeviceRow {
  code: string
  name?: string | null
  location?: string | null
  seq: number
  online: boolean
  hasCount: boolean
  finished: boolean
  today: number
  month: number
  prodSkuId?: number | null
}

export interface ProdLineDetail {
  ok: boolean
  message?: string
  line: ProdLineRow & { finishedAuto: boolean; finishedCode?: string | null; finishedName?: string | null }
  devices: LineDeviceRow[]
  daily: { date: string; qty: number }[]
  totals: { today: number; month: number; year: number }
}

const enc = (code: string) => encodeURIComponent(code)

export const lineApi = {
  list: () => http.get<ProdLineList>('/api/erp/lines'),
  detail: (code: string, days = 30) => http.get<ProdLineDetail>(`/api/erp/lines/${enc(code)}?days=${days}`),
  create: (b: Partial<ProdLineRow>) => http.post<{ ok: boolean; message?: string }>('/api/erp/lines', b),
  update: (code: string, b: Partial<ProdLineRow>) =>
    http.put<{ ok: boolean; message?: string }>(`/api/erp/lines/${enc(code)}`, b),
  remove: (code: string) => http.del<{ ok: boolean; message?: string }>(`/api/erp/lines/${enc(code)}`),
  /** 传完整有序编码列表：数组顺序就是工位顺序 */
  assign: (code: string, codes: string[]) =>
    http.post<{ ok: boolean; message?: string }>(`/api/erp/lines/${enc(code)}/devices`, { codes }),
  move: (code: string, deviceCode: string, dir: 'up' | 'down') =>
    http.post<{ ok: boolean; message?: string }>(`/api/erp/lines/${enc(code)}/move`, { deviceCode, dir }),
  setFinished: (code: string, deviceCode: string | null) =>
    http.post<{ ok: boolean; message?: string }>(`/api/erp/lines/${enc(code)}/finished`, { deviceCode })
}
