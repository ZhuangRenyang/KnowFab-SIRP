import { http } from './http'
import { isTiered, tierDesc } from '@/utils/alarmTier'
import type { AlarmTier } from '@/utils/alarmTier'

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
  /**
   * 多级限值（DH-14）：同一测点的预警 / 告警 / 停机几档。
   * 老规则没有这个字段，仍然用单值 threshold + level 判定——存量机器不用迁移。
   */
  tiers?: AlarmTier[]
  forSeconds: number
  recoverSeconds: number
  cooldownSeconds: number
  schedule: string
  days: string
  message: string
  notify: boolean
  enabled: boolean
}

/**
 * 一档限值：等级 + 阈值
 *
 * 定义在 utils/alarmTier.ts（跟校验逻辑放一起），这里转出去，
 * 免得同一个类型在两个文件里各写一份、改了一个忘了另一个。
 */
export type { AlarmTier, TierRow } from '@/utils/alarmTier'

/* 档位的顺序、校验、描述文案都放在 utils/alarmTier.ts —— 三处共用一份，不各写一遍 */
export {
  LEVEL_RANK, LEVEL_ORDER, nextTierRowId, isTiered, ruleTiers, tierDesc,
  sortTierRows, validateTierRows, fmtNum, coversDevice, unitOfPoint,
} from '@/utils/alarmTier'
export type { Tr } from '@/utils/alarmTier'

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
/* LV / MIN_LEVEL_OPTS 已删：它们的等级文案是「严重/重要/提示」这套旧叫法，
   留着早晚有人拿去用，页面上就会出现两套等级命名。现在统一是「停机/告警/预警」。 */
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
export const LEVEL_OPTS = [
  { value: 'ALL', label: '全部级别' },
  { value: 'CRITICAL', label: '停机' },
  { value: 'MAJOR', label: '告警' },
  { value: 'MINOR', label: '预警' }
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

/* ---------- 时间/时长（中文读法与后端 AlarmNotifier.fmtDuration 一致；传 t 则按语言输出） ---------- */
export type TT = (key: string, params?: Record<string, unknown>) => string

export function fmtTime(ts?: number | null): string {
  if (!ts) return '--'
  const d = new Date(ts), p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

export function fmtDur(ms?: number | null, t?: TT): string {
  /* 无 t：回退中文（与旧版/后端一致） */
  if (!t) {
    if (!ms || ms < 0) return '0 秒'
    const s0 = Math.floor(ms / 1000)
    if (s0 < 60) return s0 + ' 秒'
    const m0 = Math.floor(s0 / 60)
    if (m0 < 60) return s0 % 60 ? `${m0} 分 ${s0 % 60} 秒` : `${m0} 分钟`
    const h0 = Math.floor(m0 / 60)
    if (h0 < 24) return m0 % 60 ? `${h0} 小时 ${m0 % 60} 分` : `${h0} 小时`
    return `${Math.floor(h0 / 24)} 天 ${h0 % 24} 小时`
  }
  if (!ms || ms < 0) return t('alarm.dur.s', { n: 0 })
  const s = Math.floor(ms / 1000)
  if (s < 60) return t('alarm.dur.s', { n: s })
  const m = Math.floor(s / 60)
  if (m < 60) return s % 60 ? t('alarm.dur.minSec', { m, s: s % 60 }) : t('alarm.dur.min', { n: m })
  const h = Math.floor(m / 60)
  if (h < 24) return m % 60 ? t('alarm.dur.hourMin', { h, m: m % 60 }) : t('alarm.dur.hour', { n: h })
  return t('alarm.dur.dayHour', { d: Math.floor(h / 24), h: h % 24 })
}

export function ruleDesc(r: AlarmRule, t: TT, unit = ''): string {
  const p = [t('alarmDict.ruleType.' + r.type.toLowerCase())]
  if (r.type === 'STOP') p.push(t('alarmDict.stopEq', { v: r.threshold ?? 0 }))
  if (r.type === 'THRESHOLD') {
    // 只有**库里真存了档位**的规则才走档位展示。老规则必须保持原来的
    // 「点位 op 阈值」——点位名是客户排查时最需要看的东西，
    // 换成「告警 > 10」就看不出这条规则盯的是哪个测点了。
    // 注意：这里不能用 ruleTiers()，那个函数会把老规则也展开成一行。
    const ts = r.tiers && r.tiers.length ? r.tiers : null
    p.push(ts
      ? tierDesc(ts, r.op, t, unit)
      : r.pointKey + ' ' + r.op + ' ' + r.threshold + (unit ? ' ' + unit : ''))
  }
  if (r.type === 'FLAG') p.push(r.pointKey ? r.pointKey : t('alarmDict.anyFlag'))
  p.push(t('alarmDict.forDur', { dur: fmtDur(r.forSeconds * 1000, t) }))
  p.push(r.deviceCodes ? t('alarmDict.deviceScope', { codes: r.deviceCodes }) : t('alarmDict.allDevices'))
  if (r.schedule) p.push(t('alarmDict.sched', { s: r.schedule }))
  if (r.days) p.push(t('alarmDict.week', { d: r.days }))
  if (!r.notify) p.push(t('alarmDict.noNotify'))
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
  ack: (id: number, by: string) =>
    http.post<{ ok: boolean; message?: string }>(`/api/alarms/${id}/ack`, { by }),
  ackAll: (by: string) =>
    http.post<{ ok: boolean; message?: string }>('/api/alarms/ack-all', { by }),
  rules: () => http.get<{ items: { rule: AlarmRule; error?: string; unit?: string }[] }>('/api/alarms/rules'),
  saveRule: (r: AlarmRule) =>
    http.post<{ ok: boolean; message?: string }>('/api/alarms/rules', r),
  deleteRule: (id: string) => http.del(`/api/alarms/rules/${encodeURIComponent(id)}`),
  notify: () => http.get<{ config: NotifyConfig }>('/api/alarms/notify'),
  saveNotify: (cfg: NotifyConfig) =>
    http.post<{ ok: boolean; message?: string }>('/api/alarms/notify', cfg),
  testChannel: (c: NotifyChannel) =>
    http.post<{ ok: boolean; message?: string }>('/api/alarms/notify/test', c)
}
