import { http } from './http'

/* 能耗域 API（契约与旧版一致）。 */

export interface TariffSegment {
  name: string
  type: 'TIP' | 'PEAK' | 'FLAT' | 'VALLEY' | 'UNPRICED'
  price: number
  kwh: number
  cost: number
  ratio: number
}

export interface EnergyOverview {
  todayKwh: number
  todayCost: number
  monthCost: number
  monthKwh: number
  power: number
  tariffName: string
  hasEnergyPoint: boolean
  unpricedKwh: number
  segments: TariffSegment[]
  trend: { date: string; cost: number }[]
}

export interface EnergyRankItem {
  code: string
  name: string
  location: string
  kwh: number
  cost: number
  online: boolean
  backfillKwh: number
}

export interface DemandConfig {
  contractDemandKw: number
  demandPrice: number
  transformerKva: number
  capacityPrice: number
  billingMode: 'AUTO' | 'DEMAND' | 'CAPACITY'
  warnRatio: number
  enabled: boolean
}

export interface DemandData {
  ok: boolean
  config: DemandConfig
  status: { level?: 'OVER' | 'WARN' | string; message?: string; headroomKw?: number }
  billing: { cost: number; modeText: string; formula: string; demandCost: number; capacityCost: number; mode: string }
  monthMax: { kw: number; atText: string }
  today: {
    fallback?: boolean
    date?: string
    peakKw: number
    peakAt: string
    series: { t: string; kw: number }[]
  }
  current: { kw: number; elapsedSec: number }
}

/**
 * 基本电费试算结果。
 *
 * 试算**不落库、不写审计、不影响任何人的配置**——
 * 只是把「这组参数」喂给后端同一套计费公式跑一遍。
 * 所以口径只有一处（后端 `DemandService.billing/status`），前端不自己算。
 */
export interface DemandPreview {
  ok: boolean
  /** 峰值取真实数据，不可改 */
  peakKw: number
  billing: DemandData['billing']
  status: DemandData['status']
  /** 已保存的配置（用来回显"现在是多少"） */
  saved: DemandConfig
  /** 这次试算用的参数 */
  probe: DemandConfig
  /** 试算参数与已保存配置是否有差异（前端据此显示「未保存」与保存按钮） */
  changed: boolean
  warnings?: string[]
  message?: string
}

export interface TariffPlan {
  name: string
  segments: { name: string; type: string; start: string; end: string; price: number }[]
}

export interface DeviceEnergyDetail {
  name: string
  date: string
  kwh: number
  cost: number
  hourly: { label: string; kwh: number; segType: string }[]
}

/* 峰谷配色语义（图表语义 Token，红=贵 绿=便宜的直觉不变） */
export const SEG_COLOR: Record<string, string> = {
  TIP: '#e0443b',
  PEAK: '#f97316',
  FLAT: '#236de9',
  VALLEY: '#58a021',
  UNPRICED: '#99a2a5'
}
export const SEG_TYPES: [string, string][] = [
  ['TIP', '尖峰'], ['PEAK', '高峰'], ['FLAT', '平段'], ['VALLEY', '低谷']
]

/* 用能单元（DH-11）：全厂 → 车间 → 产线 三级 */
export interface EnergyNodeLine {
  lineId: number
  lineName: string
  kwh: number
  cost: number
  backfillKwh: number
  deviceCount: number
  share: number
}
export interface EnergyNodeWorkshop {
  workshop: string
  kwh: number
  cost: number
  backfillKwh: number
  deviceCount: number
  lineCount: number
  share: number
  lines: EnergyNodeLine[]
}
export interface EnergyByNode {
  range: string
  total: { kwh: number; cost: number; backfillKwh: number }
  workshops: EnergyNodeWorkshop[]
  unassigned: { kwh: number; cost: number; backfillKwh: number; deviceCount: number }
  check: { workshopSum: number; matchesTotal: boolean; gap: number }
}

/* 能效对标（DH-12）：单位产品能耗 vs 国标限额值 */
export type BenchmarkScope = 'PLANT' | 'WORKSHOP' | 'LINE' | 'DEVICE'
export type BenchmarkStatus = 'ADVANCED' | 'ACCESS' | 'LIMIT' | 'EXCEED' | 'NO_OUTPUT' | 'SCOPE_EMPTY'

export interface EnergyBenchmark {
  id: string
  name: string
  standardNo: string
  scopeType: BenchmarkScope
  scopeValue: string
  unit: string
  advancedValue?: number | null
  accessValue?: number | null
  limitValue?: number | null
  enabled: boolean
  note: string
}

export interface BenchmarkRow extends EnergyBenchmark {
  scopeLabel: string
  kwh: number
  output: number
  unitKwh: number | null
  vsAdvanced: number | null
  vsAccess: number | null
  vsLimit: number | null
  status: BenchmarkStatus
}

export interface BenchmarkReport {
  range: string
  fromText: string
  toText: string
  plant: { kwh: number; output: number; unitKwh: number | null; unit: string }
  rows: BenchmarkRow[]
  summary: {
    total: number
    advanced: number
    access: number
    limit: number
    exceed: number
    noOutput: number
    scopeEmpty: number
    verdict: 'OK' | 'EXCEED' | 'INCOMPLETE'
  }
  warnings: string[]
  hasBenchmark: boolean
}

export const energyApi = {
  overview: () => http.get<EnergyOverview>('/api/energy/overview?days=7'),
  rank: (range: string) => http.get<EnergyRankItem[]>(`/api/energy/devices?range=${range}`),
  demand: () => http.get<DemandData>('/api/energy/demand'),
  byNode: (range: string) => http.get<EnergyByNode>(`/api/energy/by-node?range=${range}`),
  benchmarkReport: (range: string) =>
    http.get<BenchmarkReport>(`/api/energy/benchmark-report?range=${range}`),
  benchmarks: () =>
    http.get<{ ok: boolean; rows: EnergyBenchmark[]; warnings: string[] }>('/api/energy/benchmarks'),
  saveBenchmark: (b: Partial<EnergyBenchmark>) =>
    http.post<{ ok: boolean; message?: string; row?: EnergyBenchmark; warnings: string[] }>(
      '/api/energy/benchmarks', b),
  deleteBenchmark: (id: string) =>
    http.del<{ ok: boolean; message?: string }>(`/api/energy/benchmarks/${encodeURIComponent(id)}`),
  device: (code: string) =>
    http.get<DeviceEnergyDetail>(`/api/energy/device/${encodeURIComponent(code)}`),
  tariff: () => http.get<{ plan: TariffPlan }>('/api/energy/tariff'),
  saveTariff: (plan: { name: string; segments: TariffPlan['segments'] }) =>
    http.post<{ ok: boolean; message?: string; warnings?: string[] }>('/api/energy/tariff', plan),
  demandConfig: () => http.get<{ config: DemandConfig; warnings?: string[] }>('/api/energy/demand/config'),
  /**
   * 基本电费试算：拿一组「还不想保存」的参数算一遍。
   *
   * 不传的字段以已保存配置为底；`month` 不传是本月、`device` 不传是全厂。
   */
  demandPreview: (p: Partial<DemandConfig> & { month?: string; device?: string }) =>
    http.post<DemandPreview>('/api/energy/demand/preview', p),
  saveDemandConfig: (c: DemandConfig) =>
    http.post<{ ok: boolean; message?: string }>('/api/energy/demand/config', c)
}
