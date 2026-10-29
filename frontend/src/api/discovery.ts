import { http } from './http'

/* 设备发现 API（只读探测：工具绝不碰写功能码）。 */

export interface ScanResult {
  ok: boolean
  scanned?: number
  elapsedMs?: number
  port?: number
  found?: { ip: string; port: number }[]
  error?: string
}

export interface ProbeArgs {
  ip: string
  port: number
  unitId: number
  fc: number
  from: number
  count: number
  framing: 'tcp' | 'rtu'
}

export interface ProbeResult {
  ok: boolean
  values?: number[]
  hex?: string[]
  at?: number
  error?: string
}

export interface UnitsResult {
  ok: boolean
  scanned?: number
  elapsedMs?: number
  alive?: number[]
  error?: string
}

export const discoveryApi = {
  /** 本机所在网段（自动预填扫描范围）：服务器各 IPv4 的 C 段前缀 */
  localSubnets: () =>
    http.get<{ ok: boolean; subnets: string[]; error?: string }>('/api/discovery/local-subnets'),
  scan: (f: { prefixes: string; from: number; to: number; port: number; timeoutMs: number }) =>
    http.post<ScanResult>('/api/discovery/scan', f),
  probe: (a: ProbeArgs) => http.post<ProbeResult>('/api/discovery/probe', a),
  units: (f: { ip: string; port: number; framing: string; from: number; to: number; timeoutMs: number }) =>
    http.post<UnitsResult>('/api/discovery/units', f)
}
