import { http } from './http'

/* 设备契约：与后端 DeviceUpsert / DeviceView / keybar 端点逐字段对齐（PRD §1） */

export const TPL: Record<string, any[]> = {
  machine: [
    { key: 'status', name: '运行状态', address: 0, functionCode: 3, dataType: 'u16', scale: 1, unit: '', role: 'status' },
    { key: 'speed', name: '主轴转速', address: 1, functionCode: 3, dataType: 'u16', scale: 1, unit: 'rpm', role: 'value' },
    { key: 'current', name: '主轴电流', address: 2, functionCode: 3, dataType: 'u16', scale: 0.01, unit: 'A', role: 'value' },
    { key: 'output', name: '累计产量', address: 3, functionCode: 3, dataType: 'u32', scale: 1, unit: '件', role: 'count' },
    { key: 'temperature', name: '温度', address: 5, functionCode: 3, dataType: 's16', scale: 0.1, unit: '℃', role: 'value' }
  ],
  diCounter: [
    { key: 'run_lamp', name: '运行信号', address: 0, functionCode: 2, dataType: 'bool', scale: 1, role: 'status' },
    { key: 'alarm_lamp', name: '报警信号', address: 1, functionCode: 2, dataType: 'bool', scale: 1, role: 'alarm' },
    { key: 'counter', name: '产品计数', address: 0, functionCode: 3, dataType: 'u32', scale: 1, unit: '件', role: 'count' }
  ],
  light: [
    { key: 'run_lamp', name: '运行灯', address: 0, functionCode: 2, dataType: 'bool', scale: 1, role: 'status' },
    { key: 'idle_lamp', name: '待机灯', address: 1, functionCode: 2, dataType: 'bool', scale: 1, role: 'value' },
    { key: 'alarm_lamp', name: '报警灯', address: 2, functionCode: 2, dataType: 'bool', scale: 1, role: 'value' },
    { key: 'run_signal', name: '运行信号', address: 0, functionCode: 1, dataType: 'bool', scale: 1, role: 'value' }
  ],
  energy: [
    { key: 'voltage', name: '电压', address: 0, functionCode: 3, dataType: 'u16', scale: 0.1, unit: 'V', role: 'value' },
    { key: 'current', name: '电流', address: 1, functionCode: 3, dataType: 'u16', scale: 0.01, unit: 'A', role: 'value' },
    { key: 'power', name: '有功功率', address: 2, functionCode: 3, dataType: 'u32', scale: 0.001, unit: 'kW', role: 'power' },
    { key: 'energy', name: '累计电量', address: 4, functionCode: 3, dataType: 'u32', scale: 0.01, unit: 'kWh', role: 'energy' }
  ],
  empty: []
}

export const PROTOCOLS = [
  { value: 'modbus-tcp', label: 'modbus-tcp（网口 · 标准 Modbus TCP）' },
  { value: 'modbus-rtu-tcp', label: 'modbus-rtu-tcp（串口服务器透传）' },
  { value: 'modbus-rtu', label: 'modbus-rtu（本地 485 / 串口）' },
  { value: 'sim', label: 'sim（模拟，没硬件也能试）' }
]

export const BRANDS = [
  { value: '', label: '未指定（只显示原始码）' },
  { value: 'mitsubishi', label: '三菱伺服' },
  { value: 'delta', label: '台达伺服' },
  { value: 'yaskawa', label: '安川伺服' },
  { value: 'panasonic', label: '松下伺服' },
  { value: 'huichuan', label: '汇川伺服' },
  { value: 'plc', label: 'PLC 自定义报警字' }
]

/** 与旧版 genKey 算法逐字一致（PRD §1.1） */
export function genKey(code: string) {
  const alphabet = 'abcdefghijkmnpqrstuvwxyz23456789'
  let s = ''
  for (let i = 0; i < 24; i++) s += alphabet[Math.floor(Math.random() * alphabet.length)]
  const c = (code || 'dev').trim().toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 10) || 'dev'
  return `${c}_${s}`
}

export const devicesApi = {
  list: () => http.get<any[]>('/api/devices'),
  /** 编辑回填：编码是主键，config 接口返回 pointsJson */
  config: (code: string) => http.get<any>(`/api/devices/${encodeURIComponent(code)}/config`),
  save: (body: any) => http.post<any>('/api/devices', body),
  update: (id: number, body: any) => http.put<any>(`/api/devices/${id}`, body),
  remove: (id: number) => http.del(`/api/devices/${id}`),
  reload: () => http.post('/api/devices/reload'),
  resetKey: (code: string) =>
    http.post<{ ok: boolean; apiKey?: string; message?: string }>(`/api/devices/${encodeURIComponent(code)}/apikey`),
  toggleKey: (code: string, enabled: boolean) =>
    http.post<{ ok: boolean; message?: string }>(`/api/devices/${encodeURIComponent(code)}/apikey/toggle`, { enabled }),
  history: (code: string, point: string, limit = 180) =>
    http.get<{ data: any[] }>(`/api/devices/${encodeURIComponent(code)}/history?point=${encodeURIComponent(point)}&limit=${limit}`)
}
