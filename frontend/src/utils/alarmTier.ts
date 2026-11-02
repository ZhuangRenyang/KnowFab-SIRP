/**
 * 告警分级限值（DH-14）的领域逻辑。
 *
 * <b>为什么单独一个文件：</b>档位这套规则同时被三处用到——表单校验、档位编辑器、
 * 规则列表的描述文案。三处各写一份比较逻辑，迟早出现「这里判递增那里判递减」这种
 * 最难查的错。这里放一份，其余地方都调它。
 *
 * <b>等级只有三档：</b>MINOR 预警 < MAJOR 告警 < CRITICAL 停机。
 * 等级文案全应用只有一份（alarmDict.lv.*），这里只管顺序和数字，不管显示成什么字。
 */

/** 一档限值：等级 + 阈值 */
export interface AlarmTier {
  level: 'MINOR' | 'MAJOR' | 'CRITICAL'
  threshold: number
}

/**
 * 表单里的一行档位。阈值用字符串存——用户正在输入「8」的时候不能被当成数字 8，
 * 空串也不能被当成 0。
 * <p>
 * id 是行的稳定标识：档位行会按等级重排，Vue 靠 id 认行，
 * 少了它两个输入框的内容会串（改一行变成改另一行）。
 */
export interface TierRow {
  id: number
  level: AlarmTier['level']
  threshold: string
}

/** 翻译函数（和 api/alarms.ts 的 TT 同形） */
export type Tr = (key: string, params?: Record<string, unknown>) => string

/** 等级从低到高的顺序。MINOR 预警 < MAJOR 告警 < CRITICAL 停机 */
export const LEVEL_RANK: Record<AlarmTier['level'], number> = {
  MINOR: 1, MAJOR: 2, CRITICAL: 3
}

/** 等级从低到高排列的档位列表——界面上一眼看得出递进关系 */
export const LEVEL_ORDER: AlarmTier['level'][] = ['MINOR', 'MAJOR', 'CRITICAL']

let tierRowSeq = 0

/** 档位行的稳定 id（表单里增删档位时用） */
export function nextTierRowId(): number {
  tierRowSeq += 1
  return tierRowSeq
}

/** 是不是分级规则（配了档位）。老规则没有档位，判定行为和以前一模一样 */
export function isTiered(r: { tiers?: AlarmTier[] }): boolean {
  return !!(r.tiers && r.tiers.length)
}

/**
 * 规则实际生效的档位。
 * 配了 tiers 就用 tiers；没配（老规则）就用单值 level + threshold 展开成一条，
 * 这样界面上新老规则是同一套「档位」概念，不用给用户两套心智。
 */
export function ruleTiers(r: { tiers?: AlarmTier[]; level: AlarmTier['level']; threshold: number | null }): AlarmTier[] {
  if (r.tiers && r.tiers.length) {
    return r.tiers.filter((t) => t && t.threshold !== null && t.threshold !== undefined)
  }
  if (r.threshold === null || r.threshold === undefined) return []
  return [{ level: r.level || 'MAJOR', threshold: r.threshold }]
}

/** 阈值显示：整数不带小数点，跟后端 AlarmTierResolver.fmt 一个口径 */
export function fmtNum(v: number): string {
  if (!Number.isFinite(v)) return '--'
  return Number.isInteger(v) ? String(v) : String(Math.round(v * 100) / 100)
}

/** 档位串：预警>80 / 告警>85 / 停机>90（按等级从低到高排） */
export function tierDesc(tiers: AlarmTier[], op: string, t: Tr, unit = ''): string {
  const u = unit ? ' ' + unit : ''
  return [...tiers]
    .sort((a, b) => LEVEL_RANK[a.level] - LEVEL_RANK[b.level])
    .map((x) => `${t('alarmDict.lv.' + x.level.toLowerCase())}${op}${fmtNum(x.threshold)}`)
    .join(' / ') + u
}

/** 档位行按等级从低到高排。id 跟着行走，重排不会把两个输入框的内容串了 */
export function sortTierRows(rows: TierRow[]): TierRow[] {
  return [...rows].sort((a, b) => LEVEL_RANK[a.level] - LEVEL_RANK[b.level])
}

/**
 * 校验档位配置。返回给用户看的中文提示，没问题返回 null。
 * 前端先拦一道（体验好），后端也会校验一遍（配置是别人改的）。
 *
 * <b>单调性反了必须说清是哪两档反了：</b>只说「配置错误」用户根本不知道该改哪一档，
 * 而阈值配反了报出来的级别是错的（该停机只报个预警），比不报还危险。
 */
export function validateTierRows(rows: TierRow[], op: string, t: Tr): string | null {
  if (!rows.length) return t('alarmRule.tierRequired')
  const lvName = (l: AlarmTier['level']) => t('alarmDict.lv.' + l.toLowerCase())
  const seen = new Set<string>()
  const nums: AlarmTier[] = []
  for (const r of rows) {
    const raw = r.threshold.trim()
    const n = Number(raw)
    if (raw === '' || !Number.isFinite(n)) {
      return t('alarmRule.tierThresholdRequired', { lv: lvName(r.level) })
    }
    if (seen.has(r.level)) return t('alarmRule.tierDupLevel', { lv: lvName(r.level) })
    seen.add(r.level)
    nums.push({ level: r.level, threshold: n })
  }
  // 等值型（= / ≠）没有「更严重」这回事，多档没有意义
  if (nums.length > 1 && (op === '==' || op === '!=')) return t('alarmRule.tierEqNotAllowed')
  const sorted = [...nums].sort((a, b) => LEVEL_RANK[a.level] - LEVEL_RANK[b.level])
  // 越限方向是「大于」时等级越高阈值应越大；是「小于」时应越小
  const up = op === '>' || op === '>='
  for (let i = 1; i < sorted.length; i++) {
    const lo = sorted[i - 1], cur = sorted[i]
    if (up ? cur.threshold > lo.threshold : cur.threshold < lo.threshold) continue
    return t(up ? 'alarmRule.tierNotAsc' : 'alarmRule.tierNotDesc', {
      a: lvName(lo.level), av: fmtNum(lo.threshold),
      b: lvName(cur.level), bv: fmtNum(cur.threshold), op
    })
  }
  return null
}

/* ---------- 规则类型与比较符（也放这儿：都是告警域的常量） ---------- */

/** 四类规则 */
export const RULE_TYPE_KEYS = ['OFFLINE', 'STOP', 'FLAG', 'THRESHOLD'] as const

/** 比较符。l 是给人看的写法（≥ ≤ ≠），v 是发给后端的原值 */
export const OPS = [
  { v: '>', l: '>' }, { v: '>=', l: '≥' }, { v: '<', l: '<' },
  { v: '<=', l: '≤' }, { v: '==', l: '=' }, { v: '!=', l: '≠' }
]

/* ---------- 阈值单位：规则本身不存单位，从点位定义里查 ---------- */

/** 设备点位的一小截（只取要用的字段） */
export interface PointLike {
  pointKey?: string
  key?: string
  unit?: string
}

/** 设备的一小截 */
export interface DeviceLike {
  code?: string
  points?: PointLike[]
}

/**
 * 这条规则管不管这台设备。跟后端 AlarmRule.covers 一个口径：
 * 适用设备留空 = 全部设备；支持 CNC-* 这种前缀通配。
 */
export function coversDevice(deviceCodes: string, deviceCode: string): boolean {
  const codes = (deviceCodes || '').trim()
  if (!codes) return true
  const target = (deviceCode || '').toLowerCase()
  for (const s of codes.split(',')) {
    const c = s.trim().toLowerCase()
    if (!c) continue
    if (c === target) return true
    if (c.endsWith('*') && target.startsWith(c.slice(0, -1))) return true
  }
  return false
}

/**
 * 阈值单位：在规则管的设备的点表里找同名点位，取它的单位。
 * 同一个数值在温度点是 ℃、在压力点是 MPa，写错了客户会照着错单位去判断。
 */
export function unitOfPoint(devices: DeviceLike[], deviceCodes: string, pointKey: string): string {
  const key = (pointKey || '').trim().toLowerCase()
  if (!key) return ''
  for (const d of devices || []) {
    if (!coversDevice(deviceCodes, d.code || '')) continue
    for (const p of d.points || []) {
      const pk = (p.pointKey ?? p.key ?? '').toLowerCase()
      if (pk === key && p.unit) return p.unit.trim()
    }
  }
  return ''
}
