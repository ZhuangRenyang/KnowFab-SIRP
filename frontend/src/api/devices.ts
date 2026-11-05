import { http } from './http'

/* 设备契约：与后端 DeviceUpsert / DeviceView / keybar 端点逐字段对齐（PRD §1） */

export const TPL: Record<string, any[]> = {
  /* OPC UA 示例：点位靠 nodeId 定位（写法同 UaExpert）。
     ns 索引各品牌不同：西门子 S7-1500 常见 ns=3，汇川常见 ns=4，以现场 UaExpert 里看到的为准。 */
  opcua: [
    { key: 'status', name: '运行状态', nodeId: 'ns=2;s=Machine1.Status', dataType: 'u16', scale: 1, unit: '', role: 'status' },
    { key: 'output', name: '累计产量', nodeId: 'ns=2;s=Machine1.Output', dataType: 'u32', scale: 1, unit: '件', role: 'count' },
    { key: 'speed', name: '主轴转速', nodeId: 'ns=2;s=Machine1.Speed', dataType: 'f32', scale: 1, unit: 'rpm', role: 'value' },
    { key: 'temperature', name: '温度', nodeId: 'ns=2;s=Machine1.Temperature', dataType: 'f32', scale: 1, unit: '℃', role: 'value' },
    { key: 'alarm', name: '报警标志', nodeId: 'ns=2;s=Machine1.Alarm', dataType: 'u16', scale: 1, unit: '', role: 'alarm' }
  ],
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
  /**
   * 继电器 / 远程控制模板：线圈（功能码 1）配 writable=true 才能在详情页控制。
   * 状态反馈走离散输入（功能码 2），只读。
   */
  relay: [
    { key: 'relay1', name: '继电器 1（可控制）', address: 0, functionCode: 1, dataType: 'bool', scale: 1, role: 'value', writable: true },
    { key: 'relay2', name: '继电器 2（可控制）', address: 1, functionCode: 1, dataType: 'bool', scale: 1, role: 'value', writable: true },
    { key: 'relay1_st', name: '继电器 1 状态反馈', address: 0, functionCode: 2, dataType: 'bool', scale: 1, role: 'status' },
    { key: 'relay2_st', name: '继电器 2 状态反馈', address: 1, functionCode: 2, dataType: 'bool', scale: 1, role: 'status' }
  ],
  empty: []
}

/* label 保留中文作为兜底，labelKey 供界面按当前语言取 */
export const PROTOCOLS = [
  { value: 'modbus-tcp', label: 'modbus-tcp（网口 · 标准 Modbus TCP）', labelKey: 'protocol.modbusTcp' },
  { value: 'modbus-rtu-tcp', label: 'modbus-rtu-tcp（串口服务器透传）', labelKey: 'protocol.rtuTcp' },
  { value: 'modbus-rtu', label: 'modbus-rtu（本地 485 / 串口）', labelKey: 'protocol.rtu' },
  { value: 'opcua', label: 'opcua（OPC UA，S7-1500/汇川/欧姆龙等）', labelKey: 'protocol.opcua' },
  { value: 'sim', label: 'sim（模拟，没硬件也能试）', labelKey: 'protocol.sim' }
]

export const BRANDS = [
  { value: '', label: '未指定（只显示原始码）', labelKey: 'brand.none' },
  { value: 'mitsubishi', label: '三菱伺服', labelKey: 'brand.mitsubishi' },
  { value: 'delta', label: '台达伺服', labelKey: 'brand.delta' },
  { value: 'yaskawa', label: '安川伺服', labelKey: 'brand.yaskawa' },
  { value: 'panasonic', label: '松下伺服（MINAS A6 / A5）', labelKey: 'brand.panasonic' },
  // 松下 A4 用的是两位报警码，与 A6 的三位码不是同一套编号。
  // 必须分开选，否则同一台机器会命中另一代的含义。
  { value: 'panasonic-a4', label: '松下伺服（MINAS A4 老款）', labelKey: 'brand.panasonicA4' },
  { value: 'huichuan', label: '汇川伺服', labelKey: 'brand.huichuan' },
  { value: 'plc', label: 'PLC 自定义报警字', labelKey: 'brand.plc' }
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
  /** 远程写入（控制）：点位必须 writable=true，线圈写 0/1，寄存器写 u16 */
  write: (code: string, pointKey: string, value: number | string) =>
    http.post<{ ok: boolean; message?: string }>(`/api/devices/${encodeURIComponent(code)}/write`,
      { pointKey, value }),
  resetKey: (code: string) =>
    http.post<{ ok: boolean; apiKey?: string; message?: string }>(`/api/devices/${encodeURIComponent(code)}/apikey`),
  toggleKey: (code: string, enabled: boolean) =>
    http.post<{ ok: boolean; message?: string }>(`/api/devices/${encodeURIComponent(code)}/apikey/toggle`, { enabled }),
  history: (code: string, point: string, limit = 180) =>
    http.get<{ data: any[] }>(`/api/devices/${encodeURIComponent(code)}/history?point=${encodeURIComponent(point)}&limit=${limit}`)
}
