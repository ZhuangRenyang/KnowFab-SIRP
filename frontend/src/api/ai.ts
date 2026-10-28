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

/* ==================== AI 助手（DH-47） ==================== */

export interface ChatTool {
  name: string
  desc: string
}

export interface ChatToolsResult {
  ok: boolean
  role: string
  enabled: boolean
  configured: boolean
  model: string
  tools: ChatTool[]
}

export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}

export interface ChatTrace {
  name: string
  args: string
  ms: number
}

export interface ChatResult {
  ok: boolean
  content?: string
  tools?: ChatTrace[]
  tokens?: number
  rounds?: number
  code?: string
  message?: string
}

export const chatApi = {
  tools: () => http.get<ChatToolsResult>('/api/ai/chat/tools'),
  send: (messages: ChatMessage[]) => http.post<ChatResult>('/api/ai/chat', { messages })
}
