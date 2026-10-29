/**
 * 设备模板库（2026-10-29）
 *
 * 背景：用户问"能不能把市面上所有厂家的型号都预设好"。现实是 Modbus 没有统一语义，
 * 厂家地址表千差万别，不可能穷举。这里做的是「能确定的部分预设好」：
 *   - 协议 / 端口 / 从站号 / 功能码 / 采集周期等**通信参数**（这些基本是标准）
 *   - 常见设备的**典型点位结构**（名称、单位、缩放、角色），地址给常见默认值
 *   - 每条都带 note 说明确定性：verified = 本项目实测过；否则一律标「示例，以手册为准」
 *
 * 维护约定：新增模板必须写清 note；不确定的地址不要伪装成"官方"。
 * 后续可演进成后端模板表（支持客户自己存模板），当前先随前端发布。
 */

import { http } from './http'

export interface PointTpl {
  key: string
  name: string
  address: number
  functionCode: number
  dataType: 'u16' | 'u32' | 'bool' | 'i16'
  scale: number
  unit: string
  decimals?: number
  role: 'count' | 'status' | 'alarm' | 'value' | ''
}

export interface DeviceTpl {
  id: string
  brand: string
  model: string
  /** verified=本项目实测；example=常见默认，需核对手册 */
  confidence: 'verified' | 'example'
  protocol: string
  port: number
  unitId: number
  pollIntervalMs: number
  timeoutMs: number
  /** 485 串口参数（modbus-rtu 才用；9600-8-N-1 是行业默认） */
  serialPort?: string
  baudRate?: number
  dataBits?: number
  stopBits?: number
  parity?: number
  note: string
  points: PointTpl[]
}

export const DEVICE_TEMPLATES: DeviceTpl[] = [
  {
    id: 'siemens-s7-200-smart-demo',
    brand: '西门子',
    model: 'S7-200 SMART（演示计数程序）',
    confidence: 'verified',
    protocol: 'modbus-tcp',
    port: 502, unitId: 1, pollIntervalMs: 1000, timeoutMs: 800,
    note: '本项目已实测：MBUS_SERVER HoldStart=&VB0、MaxHold=10；VW0=产量计数(I0.0 上升沿)，VW2=影子对照。读数量别超过 10。',
    points: [
      { key: 'cnt', name: '产量计数', address: 0, functionCode: 3, dataType: 'u16', scale: 1, unit: '件', decimals: 0, role: 'count' },
      { key: 'shadow', name: '影子计数(对照)', address: 1, functionCode: 3, dataType: 'u16', scale: 1, unit: '', decimals: 0, role: 'value' }
    ]
  },
  {
    id: 'siemens-s7-200-smart-status',
    brand: '西门子',
    model: 'S7-200 SMART（状态+产量 标准布局）',
    confidence: 'example',
    protocol: 'modbus-tcp',
    port: 502, unitId: 1, pollIntervalMs: 1000, timeoutMs: 800,
    note: '示例布局：VW0=运行状态、VW2=产量计数、VW4=报警码。地址按你们 PLC 程序里的实际映射改。',
    points: [
      { key: 'st', name: '运行状态', address: 0, functionCode: 3, dataType: 'u16', scale: 1, unit: '', decimals: 0, role: 'status' },
      { key: 'cnt', name: '产量计数', address: 1, functionCode: 3, dataType: 'u16', scale: 1, unit: '件', decimals: 0, role: 'count' },
      { key: 'alarm', name: '报警码', address: 2, functionCode: 3, dataType: 'u16', scale: 1, unit: '', decimals: 0, role: 'alarm' }
    ]
  },
  {
    id: 'delta-dvp',
    brand: '台达',
    model: 'DVP 系列 PLC（Modbus 从站）',
    confidence: 'example',
    protocol: 'modbus-tcp',
    port: 502, unitId: 1, pollIntervalMs: 1000, timeoutMs: 800,
    note: '台达 DVP 的 D 寄存器常见从 0x1000 起（D0=4096）。示例按 D0=产量、D1=状态，请对照手册/程序核对。',
    points: [
      { key: 'cnt', name: '产量计数(D0)', address: 4096, functionCode: 3, dataType: 'u16', scale: 1, unit: '件', decimals: 0, role: 'count' },
      { key: 'st', name: '运行状态(D1)', address: 4097, functionCode: 3, dataType: 'u16', scale: 1, unit: '', decimals: 0, role: 'status' }
    ]
  },
  {
    id: 'mitsubishi-fx5u',
    brand: '三菱',
    model: 'FX5U / Q 系列（Modbus TCP 从站）',
    confidence: 'example',
    protocol: 'modbus-tcp',
    port: 502, unitId: 1, pollIntervalMs: 1000, timeoutMs: 800,
    note: '三菱 Modbus 从站的保持寄存器映射在 GX Works 里配置（默认常映射 D0 起）。示例：D0=产量、D1=状态，需按实际配置核对。',
    points: [
      { key: 'cnt', name: '产量计数(D0)', address: 0, functionCode: 3, dataType: 'u16', scale: 1, unit: '件', decimals: 0, role: 'count' },
      { key: 'st', name: '运行状态(D1)', address: 1, functionCode: 3, dataType: 'u16', scale: 1, unit: '', decimals: 0, role: 'status' }
    ]
  },
  {
    id: 'inovance-h3u',
    brand: '汇川',
    model: 'H3U / H5U（Modbus 从站）',
    confidence: 'example',
    protocol: 'modbus-tcp',
    port: 502, unitId: 1, pollIntervalMs: 1000, timeoutMs: 800,
    note: '汇川 PLC 的 D 寄存器 Modbus 映射随固件/配置不同，示例按 D0=0、D1=1。务必对照手册的软元件地址表。',
    points: [
      { key: 'cnt', name: '产量计数(D0)', address: 0, functionCode: 3, dataType: 'u16', scale: 1, unit: '件', decimals: 0, role: 'count' },
      { key: 'st', name: '运行状态(D1)', address: 1, functionCode: 3, dataType: 'u16', scale: 1, unit: '', decimals: 0, role: 'status' }
    ]
  },
  {
    id: 'meter-3phase',
    brand: '通用',
    model: '三相多功能电表（Modbus）',
    confidence: 'example',
    protocol: 'modbus-tcp',
    port: 502, unitId: 1, pollIntervalMs: 2000, timeoutMs: 1000,
    note: '电表寄存器各家差异最大（安科瑞/威胜/正泰/德力西都不同）。这里给常见顺序：电压/电流/有功功率/正向电能(32 位)，单位按 0.1/0.01 缩放，务必按手册核对地址与倍率。',
    points: [
      { key: 'ua', name: 'A 相电压', address: 0, functionCode: 3, dataType: 'u16', scale: 0.1, unit: 'V', decimals: 1, role: 'value' },
      { key: 'ia', name: 'A 相电流', address: 1, functionCode: 3, dataType: 'u16', scale: 0.01, unit: 'A', decimals: 2, role: 'value' },
      { key: 'p', name: '有功功率', address: 2, functionCode: 3, dataType: 'u16', scale: 0.001, unit: 'kW', decimals: 3, role: 'value' },
      { key: 'ep', name: '正向有功电能', address: 4, functionCode: 3, dataType: 'u32', scale: 0.01, unit: 'kWh', decimals: 2, role: 'value' }
    ]
  },
  {
    id: 'vfd-generic',
    brand: '通用',
    model: '变频器（Modbus 示例）',
    confidence: 'example',
    protocol: 'modbus-tcp',
    port: 502, unitId: 1, pollIntervalMs: 1000, timeoutMs: 800,
    note: '变频器（汇川 MD500/台达 VFD/ABB ACS 等）状态字与频率地址各不相同。示例：运行状态、输出频率(0.01Hz)、输出电流(0.1A)、故障码，按手册改地址。',
    points: [
      { key: 'st', name: '运行状态', address: 0, functionCode: 3, dataType: 'u16', scale: 1, unit: '', decimals: 0, role: 'status' },
      { key: 'freq', name: '输出频率', address: 1, functionCode: 3, dataType: 'u16', scale: 0.01, unit: 'Hz', decimals: 2, role: 'value' },
      { key: 'cur', name: '输出电流', address: 2, functionCode: 3, dataType: 'u16', scale: 0.1, unit: 'A', decimals: 1, role: 'value' },
      { key: 'fault', name: '故障码', address: 3, functionCode: 3, dataType: 'u16', scale: 1, unit: '', decimals: 0, role: 'alarm' }
    ]
  },
  {
    id: 'temp-controller',
    brand: '通用',
    model: '温控表（Modbus 示例）',
    confidence: 'example',
    protocol: 'modbus-tcp',
    port: 502, unitId: 1, pollIntervalMs: 1000, timeoutMs: 800,
    note: '欧姆龙 E5CC、宇电 AI 系列、岛电等地址不同。示例：PV=测量值、SV=设定值，常见 0.1℃ 倍率，按手册核对。',
    points: [
      { key: 'pv', name: '测量值 PV', address: 0, functionCode: 3, dataType: 'u16', scale: 0.1, unit: '℃', decimals: 1, role: 'value' },
      { key: 'sv', name: '设定值 SV', address: 1, functionCode: 3, dataType: 'u16', scale: 0.1, unit: '℃', decimals: 1, role: 'value' }
    ]
  },
  {
    id: 'modbus-rtu-generic',
    brand: '通用',
    model: '485 设备（Modbus RTU，9600-8-N-1）',
    confidence: 'example',
    protocol: 'modbus-rtu',
    port: 502, unitId: 1, pollIntervalMs: 1000, timeoutMs: 800,
    serialPort: 'COM3', baudRate: 9600, dataBits: 8, stopBits: 1, parity: 0,
    note: '485 的通信参数基本是全行业固定值：9600 / 8 数据位 / 无校验 / 1 停止位，从站号默认 1（以现场设备拨码或参数菜单为准）。'
      + '串口号不能固定——按电脑实际插的 USB 转 485 选（设备管理器里看 COM 几）。点表地址请对照手册。',
    points: [
      { key: 'cnt', name: '产量计数', address: 0, functionCode: 3, dataType: 'u16', scale: 1, unit: '件', decimals: 0, role: 'count' },
      { key: 'st', name: '运行状态', address: 1, functionCode: 3, dataType: 'u16', scale: 1, unit: '', decimals: 0, role: 'status' }
    ]
  },
]

/* ---------------- 客户自定义模板（落库，可存可删） ---------------- */

export interface CustomTpl {
  id: number
  source: 'custom'
  name: string
  brand?: string
  model?: string
  protocol: string
  port: number
  unitId: number
  pollIntervalMs: number
  timeoutMs: number
  serialPort?: string
  baudRate?: number
  dataBits?: number
  stopBits?: number
  parity?: number
  pointsJson: string
  note?: string
  updatedAt?: number
}

export interface TplSaveBody {
  name?: string
  brand?: string
  model?: string
  protocol: string
  port: number
  unitId: number
  pollIntervalMs: number
  timeoutMs: number
  serialPort?: string
  baudRate?: number
  dataBits?: number
  stopBits?: number
  parity?: number
  pointsJson: string
  note?: string
}

export const deviceTplApi = {
  list: () => http.get<{ ok: boolean; items: CustomTpl[] }>('/api/device-templates'),
  create: (b: TplSaveBody) =>
    http.post<{ ok: boolean; message?: string; item?: CustomTpl }>('/api/device-templates', b),
  /** 从现有设备抓取（协议/端口/从站/点表/角色全带过来） */
  fromDevice: (code: string, b?: { name?: string; brand?: string; note?: string }) =>
    http.post<{ ok: boolean; message?: string; item?: CustomTpl }>(
      `/api/device-templates/from-device/${encodeURIComponent(code)}`, b ?? {}),
  remove: (id: number) =>
    http.del<{ ok: boolean; message?: string }>(`/api/device-templates/${id}`)
}
