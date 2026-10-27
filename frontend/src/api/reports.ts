import { http } from './http'

/* 报表域 API（契约与旧版一致）。 */

export interface ShiftDef { id: string; name: string; start: string; end: string }

export interface ReportTotals {
  output: number
  availability: number
  runSec: number
  idleSec: number
  stopSec: number
  countedSec: number
  deviceCount: number
}

export interface ShiftRow extends ShiftDef {
  output: number
  runSec: number
  idleSec: number
  stopSec: number
  availability: number
}

export interface DeviceRow {
  name: string
  code: string
  location: string
  output: number
  runSec: number
  stopSec: number
  availability: number
  shiftOutput: Record<string, number>
}

export interface StopReason { reason: string; count: number; totalMin: number }

export interface DailyReport {
  ok: boolean
  date: string
  planName: string
  totals: ReportTotals
  shifts: ShiftRow[]
  devices: DeviceRow[]
  stopReasons: StopReason[]
  hours: { hour: number; output: number }[]
  warnings?: string[]
}

export interface ShiftPlan { name: string; shifts: ShiftDef[] }

export const reportsApi = {
  daily: (date: string) =>
    http.get<DailyReport>(`/api/reports/daily?date=${encodeURIComponent(date)}`),
  shifts: () => http.get<{ plan: ShiftPlan }>('/api/reports/shifts'),
  saveShifts: (plan: ShiftPlan) =>
    http.post<{ ok: boolean; message?: string }>('/api/reports/shifts', plan)
}

/* 导出走原生 fetch：返回 xlsx 二进制（http.ts 只解 JSON/文本） */
export async function exportDaily(date: string): Promise<void> {
  const r = await fetch(`/api/reports/export?date=${encodeURIComponent(date)}`)
  if (!r.ok) {
    let msg = `导出失败（${r.status}）`
    try { const j = await r.json(); msg = j.message || msg } catch { /* 非 JSON */ }
    throw new Error(msg)
  }
  const blob = await r.blob()
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `生产报表-${date}.xlsx`
  document.body.appendChild(a)
  a.click()
  window.setTimeout(() => { URL.revokeObjectURL(a.href); a.remove() }, 1000)
}

/* ---------- 工具 ---------- */
export function todayStr(offset = 0): string {
  const d = new Date()
  d.setDate(d.getDate() + offset)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

export function num(v: number | null | undefined, digits = 1): string {
  return Number(v || 0).toFixed(digits)
}
