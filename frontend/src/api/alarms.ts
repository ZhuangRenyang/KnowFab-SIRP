import { http } from './http'

export interface AlarmSummary {
  open: number
  todayNew: number
  todayRecovered: number
  ruleEnabled: number
  notifyEnabled: boolean
  notifyChannels: number
}

export const alarmsApi = {
  summary: () => http.get<AlarmSummary>('/api/alarms/summary')
}
