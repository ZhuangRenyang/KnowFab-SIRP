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

export const energyApi = {
  overview: () => http.get<EnergyOverview>('/api/energy/overview?days=7'),
  rank: (range: string) => http.get<EnergyRankItem[]>(`/api/energy/devices?range=${range}`),
  demand: () => http.get<DemandData>('/api/energy/demand'),
  device: (code: string) =>
    http.get<DeviceEnergyDetail>(`/api/energy/device/${encodeURIComponent(code)}`),
  tariff: () => http.get<{ plan: TariffPlan }>('/api/energy/tariff'),
  saveTariff: (plan: { name: string; segments: TariffPlan['segments'] }) =>
    http.post<{ ok: boolean; message?: string; warnings?: string[] }>('/api/energy/tariff', plan),
  demandConfig: () => http.get<{ config: DemandConfig; warnings?: string[] }>('/api/energy/demand/config'),
  saveDemandConfig: (c: DemandConfig) =>
    http.post<{ ok: boolean; message?: string }>('/api/energy/demand/config', c)
}
