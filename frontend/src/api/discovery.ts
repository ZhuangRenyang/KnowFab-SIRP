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

/** 本机可用串口 */
export interface SerialPortInfo {
  port: string
  path?: string
  description?: string
}

/** 串口点表对拍的参数 */
export interface SerialProbeArgs {
  serialPort: string
  baudRate: number
  dataBits: number
  stopBits: number
  parity: number
  unitId: number
  startAddr: number
  count: number
  functionCode: number
  dataType?: string
  timeoutMs?: number
}

/** 对拍结果：命中的寄存器 + 可直接用的点表 */
export interface SerialProbeResult {
  ok: boolean
  hit?: number
  miss?: number
  elapsedMs?: number
  found?: { address: number; value: unknown; key: string; name: string }[]
  points?: Record<string, unknown>[]
  message?: string
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
    http.post<UnitsResult>('/api/discovery/units', f),
  /** 本机串口列表（COM 口） */
  serialPorts: () => http.get<SerialPortInfo[]>('/api/discovery/serial-ports'),
  /** 串口点表对拍：对着已知从站逐个寄存器试读 */
  serialProbe: (a: SerialProbeArgs) => http.post<SerialProbeResult>('/api/discovery/serial-probe', a)
}
