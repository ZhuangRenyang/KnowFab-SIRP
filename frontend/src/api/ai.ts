import { http } from './http'

/* AI 早报域 API（契约与旧版一致）。 */

export interface DigestFacts {
  online: number
  deviceTotal: number
  output: number
  outputAvg7: number
  stopHours: number
  cost: number
  alarmOpen: number
  alarmCriticalOpen: number
  demandPeak: number
  contractDemand: number
}

export interface DigestEntry {
  date: string
  source: string
  model?: string
  tokens?: number
  createdText?: string
  content: string
  facts?: DigestFacts | null
}

export interface DigestData {
  enabled: boolean
  hour: number
  minute: number
  nextRun?: string
  warnings?: string[]
  digest?: DigestEntry | null
  history: DigestEntry[]
}

export interface GenerateResult {
  ok: boolean
  message?: string
  date?: string
  facts?: DigestFacts
  degraded?: boolean
  error?: string
}

export interface AiConfig {
  enabled: boolean
  baseUrl: string
  apiKey: string
  model: string
  maxTokens: number
  hour: number
  minute: number
  temperature: number
  pushToWechat: boolean
  systemPrompt: string
}

export const aiApi = {
  digest: () => http.get<DigestData>('/api/ai/digest'),
  generate: () => http.post<GenerateResult>('/api/ai/digest/generate', {}),
  config: () => http.get<{ config: AiConfig; warnings?: string[] }>('/api/ai/config'),
  saveConfig: (c: AiConfig) =>
    http.post<{ ok: boolean; message?: string; warnings?: string[] }>('/api/ai/config', c),
  test: () => http.post<{ ok: boolean; message?: string; tokens?: number }>('/api/ai/test')
}
