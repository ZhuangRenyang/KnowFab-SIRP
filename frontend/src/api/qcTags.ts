/* ============================================================
   质检标签色与派生状态
   ------------------------------------------------------------
   同一个状态在检验单列表、详情抽屉、不良品队列里都要映射成标签色。
   各写各的就会出现「列表里 PENDING 是黄点、抽屉里 PENDING 是灰点」，
   用户会以为这是两种东西。集中之后改一次全局生效。

   未知码一律退化成中性灰：
   · 标红会让人以为出了事故
   · 标绿更糟 —— 它会让人以为这条已经处理好了
   ============================================================ */

import type { QcDisplayStatus } from './qcTypes'

/** 标签类型（Element Plus el-tag 的 type） */
export type QcTagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

const UNKNOWN_TAG: QcTagType = 'info'

/**
 * 检验单状态 → 标签色。
 * DRAFT 灰（还没动）、SUBMITTED 蓝（等判定）、JUDGED 绿（判完了）、
 * CANCELLED 灰（作废）。VOID/PENDING/DONE 是别名，各归各的位。
 */
export function qcStatusType(s: string): QcTagType {
  switch (s) {
    case 'DRAFT':
    case 'PENDING':
      return 'info'
    case 'SUBMITTED':
      return 'primary'
    case 'JUDGED':
    case 'DONE':
      return 'success'
    case 'CANCELLED':
    case 'VOID':
      return 'info'
    default:
      return UNKNOWN_TAG
  }
}

/** 判定结论 → 标签色。CONDITIONAL 用警告色：不是没问题，是有条件地放行 */
export function verdictType(v: string): QcTagType {
  switch (v) {
    case 'PASS':
      return 'success'
    case 'FAIL':
      return 'danger'
    case 'CONDITIONAL':
      return 'warning'
    default:
      return UNKNOWN_TAG
  }
}

/** 不良记录状态 → 标签色 */
export function ncrStatusType(s: string): QcTagType {
  switch (s) {
    case 'PENDING':
      return 'warning'
    case 'DISPOSED':
      return 'success'
    case 'CANCELLED':
      return 'info'
    default:
      return UNKNOWN_TAG
  }
}

/**
 * 派生展示状态 → 标签色。
 * REWORK_FQC_FAIL 用红色：返工复检没过，这条基本已经判死刑。
 * REWORK_WAIT_FQC 用蓝色：还在等复检，不是失败。
 */
export function displayStatusType(s: string): QcTagType {
  switch (s) {
    case 'PENDING':
      return 'warning'
    case 'DISPOSED':
      return 'success'
    case 'CANCELLED':
      return 'info'
    case 'REWORK_WAIT_FQC':
      return 'primary'
    case 'REWORK_FQC_FAIL':
      return 'danger'
    default:
      return UNKNOWN_TAG
  }
}

/** 缺陷分级 → 标签色。CR 一票否决，必须最显眼 */
export function gradeType(s: string): QcTagType {
  switch (s) {
    case 'CR':
      return 'danger'
    case 'MA':
      return 'warning'
    case 'MI':
      return 'info'
    default:
      return UNKNOWN_TAG
  }
}

/** 来源 → 标签色 */
export function sourceType(s: string): QcTagType {
  switch (s) {
    case 'DEVICE':
      return 'primary'
    case 'INSPECTION':
      return 'success'
    case 'MANUAL':
      return 'warning'
    case 'RETURN':
      return 'danger'
    default:
      return UNKNOWN_TAG
  }
}

/** 处置动作 → 标签色 */
export function dispositionType(s: string): QcTagType {
  switch (s) {
    case 'SCRAP':
      return 'danger'
    case 'REWORK':
      return 'primary'
    case 'CONCESSION':
      return 'warning'
    default:
      return UNKNOWN_TAG
  }
}

/**
 * 由不良记录 + 处置流水派生列表展示状态。
 *
 * 「处置了一半」不设独立状态（后端只有 PENDING/DISPOSED/CANCELLED 三态），
 * 用数字表达：已处置 3 · 待处置 5。但列表上还是得让人一眼看出这条处在什么阶段，
 * 所以派生两个展示态：
 * · REWORK_WAIT_FQC 已建返工单、还没做复检
 * · REWORK_FQC_FAIL 返工有失败件
 */
export function deriveDisplayStatus(d: {
  status: string
  voidedAt?: number | null
  pendingQty?: number
  dispositions?: {
    action: string
    failQty?: number
    fqcInspectionNo?: string | null
    voidedAt?: number | null
  }[]
}): QcDisplayStatus {
  if (d.voidedAt) return 'CANCELLED'
  if (d.status === 'CANCELLED') return 'CANCELLED'
  const live = (d.dispositions || []).filter((x) => !x.voidedAt)
  const rework = live.filter((x) => x.action === 'REWORK')
  if (rework.length) {
    if (rework.some((x) => (x.failQty || 0) > 0)) return 'REWORK_FQC_FAIL'
    if (rework.some((x) => !x.fqcInspectionNo)) return 'REWORK_WAIT_FQC'
  }
  if (d.status === 'DISPOSED') return 'DISPOSED'
  return 'PENDING'
}

/**
 * 放行拦截原因 → 「下一步做什么」。
 *
 * 两种原因的下一步完全不同，**不能合并成一句「被拦了」**：
 * · NO_ACK   有让步流水但没客户同意凭证 → 去登记客户同意
 * · NO_RELEASE 禁发仓里没有任何放行依据 → 去不良品页处置
 * 分不清的话，用户会反复去登记客户同意，而真正该做的是去处置不良品。
 */
export function blockReasonLabel(
  t: (k: string) => string,
  reason: string | null | undefined
): string {
  if (reason === 'NO_ACK') return t('qc.blockReason.NO_ACK')
  if (reason === 'NO_RELEASE') return t('qc.blockReason.NO_RELEASE')
  return t('qc.blockReason.unknown')
}

/** 放行拦截原因 → 下一步动作的 i18n key */
export function blockReasonActionKey(reason: string | null | undefined): string {
  if (reason === 'NO_ACK') return 'qc.blockAction.ack'
  if (reason === 'NO_RELEASE') return 'qc.blockAction.dispose'
  return 'qc.blockAction.unknown'
}

/** 严重度排序，CR 在前 —— 列表默认按这个排，致命问题永远在最上面 */
export const SEVERITY_ORDER: Record<string, number> = { CR: 0, MA: 1, MI: 2 }
