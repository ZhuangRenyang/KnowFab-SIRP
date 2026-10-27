import { http } from './http'

/* 报警域 API 与字典。文案/默认值与旧版逐字一致（PRD：1:1 平移，不顺手改）。 */

export interface AlarmSummary {
  open: number
  todayNew: number
  todayRecovered: number
  ruleEnabled: number
  notifyEnabled: boolean
  notifyChannels: number
  trend: { date: string; count: number }[]
  topDevices: { name: string; code: string; count: number }[]
}

export interface AlarmItem {
  id: number
  level: 'CRITICAL' | 'MAJOR' | 'MINOR'
  state: 'ACTIVE' | 'ACKED' | 'CLEARED'
  deviceName: string
  deviceCode: string
  location: string
  startedAt: number
  durationMs: number
  clearedAt: number | null
  clearReason: string
  ackBy: string
  message: string
  ruleName: string
}

export interface AlarmPage {
  items: AlarmItem[]
  page: number
  pages: number
  total: number
}

export interface AlarmRule {
  id: string | null
  name: string
  type: 'OFFLINE' | 'STOP' | 'FLAG' | 'THRESHOLD'
  deviceCodes: string
  level: 'CRITICAL' | 'MAJOR' | 'MINOR'
  pointKey: string
  op: string
  threshold: number | null
  forSeconds: number
  recoverSeconds: number
  cooldownSeconds: number
  schedule: string
  days: string
  message: string
  notify: boolean
  enabled: boolean
}

export interface NotifyChannel {
  id: string | null
  name: string
  type: string
  url: string
  token: string
  enabled: boolean
  minLevel: string
}

export interface NotifyConfig {
  enabled: boolean
  quietHours: string
  repeatMinutes: number
  channels: NotifyChannel[]
}

/* ---------- 字典 ---------- */
export const LV: Record<string, string> = { CRITICAL: '严重', MAJOR: '重要', MINOR: '提示' }
export const AST: Record<string, string> = { ACTIVE: '未确认', ACKED: '已确认', CLEARED: '已恢复' }
export const RULE_TYPES: Record<string, string> = {
  OFFLINE: '设备离线（读不到数据）',
  STOP: '设备停机（状态值等于指定值）',
  FLAG: '报警信号（报警位不为 0）',
  THRESHOLD: '数值越限（某点超过阈值）'
}
export const CH_TYPES: Record<string, [string, string]> = {
  WECOM_BOT: ['企业微信群机器人', 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...'],
  SERVERCHAN: ['Server酱（推个人微信）', 'https://sctapi.ftqq.com/SCTxxxxx.send'],
  PUSHPLUS: ['PushPlus（推个人微信）', 'http://www.pushplus.plus/send'],
  DINGTALK: ['钉钉群机器人', 'https://oapi.dingtalk.com/robot/send?access_token=...'],
  FEISHU: ['飞书群机器人', 'https://open.feishu.cn/open-apis/bot/v2/hook/...'],
  WEBHOOK: ['自定义 Webhook', 'https://your-server/alarm-hook'],
  LOG: ['仅记录日志（调试用）', '']
}
export const DAY_NAMES = ['一', '二', '三', '四', '五', '六', '日']
export const MIN_LEVEL_OPTS = [
  { value: 'CRITICAL', label: '只推严重的' },
  { value: 'MAJOR', label: '重要及以上' },
  { value: 'MINOR', label: '全部级别' }
]
export const LEVEL_OPTS = [
  { value: 'ALL', label: '全部级别' },
  { value: 'CRITICAL', label: '严重' },
  { value: 'MAJOR', label: '重要' },
  { value: 'MINOR', label: '提示' }
]
export const STATE_OPTS = [
  { value: 'OPEN', label: '待处理' },
  { value: 'ACTIVE', label: '仅未确认' },
  { value: 'ACKED', label: '仅已确认' },
  { value: 'CLEARED', label: '已恢复' },
  { value: 'ALL', label: '全部' }
]
export const RANGE_OPTS = [
  { value: 'today', label: '今日' },
  { value: '7d', label: '近 7 天' },
  { value: '30d', label: '近 30 天' },
  { value: 'all', label: '全部' }
]

export function newRule(): AlarmRule {
  return {
    id: null, name: '', type: 'STOP', deviceCodes: '', level: 'MAJOR',
    pointKey: '', op: '>', threshold: 0,
    forSeconds: 600, recoverSeconds: 60, cooldownSeconds: 1800,
    schedule: '', days: '1,2,3,4,5,6', message: '', notify: true, enabled: true
  }
}

/* ---------- 时间/时长（读法与后端 AlarmNotifier.fmtDuration 一致） ---------- */
export function fmtTime(ts?: number | null): string {
  if (!ts) return '--'
  const d = new Date(ts), p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

export function fmtDur(ms?: number | null): string {
  if (!ms || ms < 0) return '0 秒'
  const s = Math.floor(ms / 1000)
  if (s < 60) return s + ' 秒'
  const m = Math.floor(s / 60)
  if (m < 60) return s % 60 ? `${m} 分 ${s % 60} 秒` : `${m} 分钟`
  const h = Math.floor(m / 60)
  if (h < 24) return m % 60 ? `${h} 小时 ${m % 60} 分` : `${h} 小时`
  return `${Math.floor(h / 24)} 天 ${h % 24} 小时`
}

export function ruleDesc(r: AlarmRule): string {
  const p = [RULE_TYPES[r.type] || r.type]
  if (r.type === 'STOP') p.push('状态值 = ' + (r.threshold ?? 0))
  if (r.type === 'THRESHOLD') p.push(r.pointKey + ' ' + r.op + ' ' + r.threshold)
  if (r.type === 'FLAG') p.push(r.pointKey ? r.pointKey : '任一报警位')
  p.push('持续 ' + fmtDur(r.forSeconds * 1000))
  p.push(r.deviceCodes ? '设备 ' + r.deviceCodes : '全部设备')
  if (r.schedule) p.push('时段 ' + r.schedule)
  if (r.days) p.push('周 ' + r.days)
  if (!r.notify) p.push('不通知')
  return p.join(' · ')
}

/* ---------- API ---------- */
export const alarmsApi = {
  summary: () => http.get<AlarmSummary>('/api/alarms/summary'),
  list: (f: { state: string; level: string; range: string; q: string; page: number; size: number }) =>
    http.get<AlarmPage>(
      `/api/alarms?state=${f.state}&level=${f.level}&range=${f.range}` +
      `&q=${encodeURIComponent(f.q)}&page=${f.page}&size=${f.size}`
    ),
  ack: (id: number) =>
    http.post<{ ok: boolean; message?: string }>(`/api/alarms/${id}/ack`, { by: '值班人员' }),
  ackAll: () =>
    http.post<{ ok: boolean; message?: string }>('/api/alarms/ack-all', { by: '值班人员' }),
  rules: () => http.get<{ items: { rule: AlarmRule; error?: string }[] }>('/api/alarms/rules'),
  saveRule: (r: AlarmRule) =>
    http.post<{ ok: boolean; message?: string }>('/api/alarms/rules', r),
  deleteRule: (id: string) => http.del(`/api/alarms/rules/${encodeURIComponent(id)}`),
  notify: () => http.get<{ config: NotifyConfig }>('/api/alarms/notify'),
  saveNotify: (cfg: NotifyConfig) =>
    http.post<{ ok: boolean; message?: string }>('/api/alarms/notify', cfg),
  testChannel: (c: NotifyChannel) =>
    http.post<{ ok: boolean; message?: string }>('/api/alarms/notify/test', c)
}
