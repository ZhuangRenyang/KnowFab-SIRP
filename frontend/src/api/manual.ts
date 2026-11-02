import { http } from './http'

/* 数据补录（DH-13）：补录单独存表，报表里单列「其中补录」 */

export interface ManualEntryRow {
  id: number
  batchId?: string
  deviceCode: string
  deviceName?: string
  day: string
  kwh: number
  kwhCost: number
  output: number
  reason?: string
  operator?: string
  createdAt: number
  revoked: boolean
  revokedBy?: string
  revokedAt?: number
}

export interface ManualCreateBody {
  deviceCode: string
  fromDay: string
  toDay?: string
  kwh?: number
  output?: number
  reason: string
}

export const manualApi = {
  list: (from: string, to: string) =>
    http.get<{ ok: boolean; from: string; to: string; rows: ManualEntryRow[] }>(
      `/api/manual?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
  create: (body: ManualCreateBody) =>
    http.post<{ ok: boolean; message?: string; batchId?: string; days?: number }>('/api/manual', body),
  revoke: (id: number) =>
    http.post<{ ok: boolean; message?: string }>(`/api/manual/${id}/revoke`, {})
}
