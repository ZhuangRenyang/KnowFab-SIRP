import { http } from './http'

/* 报表域 API（契约与旧版一致）。 */

export interface ShiftDef { id: string; name: string; start: string; end: string }

/**
 * OEE（DH-15）。四个比率都是 null 表示「算不出来」，notes 说明缺什么、怎么补。
 * 「算不出来」和「0」是两回事，界面上必须分开显示。
 */
export interface OeeInfo {
  availability: number | null
  performance: number | null
  quality: number | null
  oee: number | null
  plannedSec: number
  countedSec?: number
  runSec: number
  output: number
  defect: number
  /** 有没有不良数数据（不是「不良数是不是 0」） */
  hasDefectData: boolean
  theoreticalOutput: number | null
  idealCycleSec: number | null
  notes: string[]
  deviceCount?: number
  noCycleDevices?: number
  noDefectDevices?: number
}

export interface ReportTotals {
  /** 其中人工补录的产量（含在 output 里） */
  manualOutput?: number
  output: number
  availability: number
  runSec: number
  idleSec: number
  stopSec: number
  countedSec: number
  deviceCount: number
  oee?: OeeInfo
}

export interface ShiftRow extends ShiftDef {
  output: number
  runSec: number
  idleSec: number
  stopSec: number
  availability: number
}

export interface DeviceRow {
  manualOutput?: number
  name: string
  code: string
  location: string
  output: number
  runSec: number
  stopSec: number
  availability: number
  shiftOutput: Record<string, number>
  /** DH-15：这一台设备的 OEE */
  oee?: OeeInfo
}

export interface StopReason { reason: string; count: number; totalMin: number }

export interface DailyReport {
  manual?: { id: number; deviceCode: string; deviceName?: string; day: string; kwh: number; kwhCost: number; output: number; defect?: number; reason?: string; operator?: string }[]
  ok: boolean
  date: string
  planName: string
  totals: ReportTotals
  /** DH-15：合计 OEE */
  oee?: OeeInfo
  shifts: ShiftRow[]
  devices: DeviceRow[]
  stopReasons: StopReason[]
  hours: { hour: number; output: number }[]
  warnings?: string[]
}

export interface ShiftPlan { name: string; shifts: ShiftDef[] }

/** 逐日 OEE 趋势（DH-15）。points 里缺的那天是 null，图上断线不是 0 */
export interface TrendDevice {
  code: string
  name: string
  series: (OeeInfo | null)[]
}

export interface RangeReport {
  ok: boolean
  dates: string[]
  rows: (ReportTotals & { date: string; defect?: number; oee?: OeeInfo })[]
  devices: TrendDevice[]
}

export const reportsApi = {
  daily: (date: string) =>
    http.get<DailyReport>(`/api/reports/daily?date=${encodeURIComponent(date)}`),
  /** 最近 N 天的逐日汇总（趋势图数据源） */
  range: (days: number) =>
    http.get<RangeReport>(`/api/reports/range?days=${days}`),
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

/* 月报导出（DH-16）：生产 + 电费一个文件 */
export async function exportMonth(month: string): Promise<void> {
  const r = await fetch(`/api/reports/export-month?month=${encodeURIComponent(month)}`)
  if (!r.ok) {
    let msg = `导出失败（${r.status}）`
    try { const j = await r.json(); msg = j.message || msg } catch { /* 非 JSON */ }
    throw new Error(msg)
  }
  const blob = await r.blob()
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `生产月报-${month}.xlsx`
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
