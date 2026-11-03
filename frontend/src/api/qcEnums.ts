/* ============================================================
   质检枚举常量（后端枚举 → 前端取值）
   ------------------------------------------------------------
   与后端 com.factory.hub.quality.* 的枚举逐个对应。
   新增枚举必须同时更新 locales/zh-CN.ts 与 en-US.ts 的 qc.* 词条。
   ============================================================ */

import type {
  QcBlockReason,
  QcDisplayStatus,
  QcDisposition,
  QcInspectionStatus,
  QcLevel,
  QcNcrStatus,
  QcReturnReason,
  QcSampleSource,
  QcSeverity,
  QcSkipReason,
  QcSource,
  QcStrictLevel,
  QcType,
  QcVerdict
} from './qcTypes'

export const QC_TYPES: readonly QcType[] = ['IQC', 'FAI', 'IPQC', 'FQC']

/**
 * 检验单状态。
 * 前四个是后端现役值（DRAFT/SUBMITTED/JUDGED/CANCELLED）；
 * 后三个是别名（PENDING/DONE/VOID）——后端换枚举时前端不会白屏。
 */
export const QC_STATUSES: readonly QcInspectionStatus[] = [
  'DRAFT', 'SUBMITTED', 'JUDGED', 'CANCELLED', 'PENDING', 'DONE', 'VOID'
]

/** 判定结论。CONDITIONAL（让步接收）只能人工选，算法不产出 */
export const QC_VERDICTS: readonly QcVerdict[] = ['PASS', 'FAIL', 'CONDITIONAL']

/** 不良记录状态 */
export const NCR_STATUSES: readonly QcNcrStatus[] = ['PENDING', 'DISPOSED', 'CANCELLED']

/** 列表展示用的派生状态 */
export const DISPLAY_STATUSES: readonly QcDisplayStatus[] = [
  'PENDING', 'DISPOSED', 'CANCELLED', 'REWORK_WAIT_FQC', 'REWORK_FQC_FAIL'
]

/** 不良记录来源 */
export const QC_SOURCES: readonly QcSource[] = ['DEVICE', 'INSPECTION', 'MANUAL', 'RETURN']

/** 缺陷分级 */
export const SEVERITIES: readonly QcSeverity[] = ['CR', 'MA', 'MI']

/** 处置动作 */
export const DISPOSITIONS: readonly QcDisposition[] = ['SCRAP', 'REWORK', 'CONCESSION']

/**
 * 检验水平。
 *
 * **顺序 = 国标表 1 的列顺序**（特殊检验水平 S-1~S-4 在前，一般 I/II/III 在后）。
 * 下拉按这个顺序显示，用户对照标准翻表时不用二次排序。
 */
export const QC_LEVELS: readonly QcLevel[] = [
  'SPECIAL_S1', 'SPECIAL_S2', 'SPECIAL_S3', 'SPECIAL_S4',
  'GENERAL_I', 'GENERAL_II', 'GENERAL_III'
]

/** 检验严格度 */
export const STRICT_LEVELS: readonly QcStrictLevel[] = ['NORMAL', 'TIGHTENED', 'REDUCED']

/** 抽样参数来源三档。FIXED 与 MANUAL 必须分开统计 */
export const SAMPLE_SOURCES: readonly QcSampleSource[] = ['AQL', 'FIXED', 'MANUAL']

/** 出货放行被拦的原因 */
export const BLOCK_REASONS: readonly QcBlockReason[] = ['NO_ACK', 'NO_RELEASE']

/** 退货原因 */
export const RETURN_REASONS: readonly QcReturnReason[] = ['QUALITY', 'OTHER']

/** 批量操作跳过原因 */
export const SKIP_REASONS: readonly QcSkipReason[] = [
  'DISPOSED', 'CONFLICT', 'NOT_FOUND', 'INVALID_STATE', 'ERROR'
]

/* ---------- i18n 词条 ---------- */

/** 枚举组 → i18n 词条前缀。与 locales/*.ts 里的 qc.* 结构一一对应 */
export const QC_ENUM_PREFIX = {
  type: 'qc.type',
  status: 'qc.status',
  verdict: 'qc.verdict',
  ncrStatus: 'qc.ncrStatus',
  displayStatus: 'qc.displayStatus',
  source: 'qc.source',
  severity: 'qc.severity',
  disposition: 'qc.disposition',
  level: 'qc.level',
  strictLevel: 'qc.strictLevel',
  sampleSource: 'qc.sampleSource',
  blockReason: 'qc.blockReason',
  returnReason: 'qc.returnReason',
  skipReason: 'qc.skipReason'
} as const

/** 枚举组名 */
export type QcEnumGroup = keyof typeof QC_ENUM_PREFIX

/** 每个枚举组的合法取值集合（用于判断未知码） */
export const QC_ENUM_VALUES: Record<QcEnumGroup, readonly string[]> = {
  type: QC_TYPES,
  status: QC_STATUSES,
  verdict: QC_VERDICTS,
  ncrStatus: NCR_STATUSES,
  displayStatus: DISPLAY_STATUSES,
  source: QC_SOURCES,
  severity: SEVERITIES,
  disposition: DISPOSITIONS,
  level: QC_LEVELS,
  strictLevel: STRICT_LEVELS,
  sampleSource: SAMPLE_SOURCES,
  blockReason: BLOCK_REASONS,
  returnReason: RETURN_REASONS,
  skipReason: SKIP_REASONS
}

/**
 * 取枚举文案。
 *
 * 未知码 → 中性文案（qc.enum.unknown）。
 * **不许显示码本身**：一串 "REWORK_WAIT_FQC" 出现在标签上，
 * 用户既看不懂也不知道出没出错；标成中性 + title 里藏原值才是对的。
 */
export function qcEnumLabel(
  t: (k: string) => string,
  group: QcEnumGroup,
  value: string | null | undefined
): string {
  if (!value) return t('qc.enum.unknown')
  if (!QC_ENUM_VALUES[group].includes(value)) return t('qc.enum.unknown')
  return t(`${QC_ENUM_PREFIX[group]}.${value}`)
}

/** 未知码时把原值放进 title，方便对账时看后端到底返回了什么 */
export function qcEnumTitle(
  group: QcEnumGroup,
  value: string | null | undefined
): string {
  if (!value) return ''
  return QC_ENUM_VALUES[group].includes(value) ? '' : String(value)
}
