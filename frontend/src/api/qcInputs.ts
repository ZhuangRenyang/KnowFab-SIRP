/* ============================================================
   质检 · 请求体类型
   ------------------------------------------------------------
   建单、改单、补录、三种处置、批量操作、撤销。
   和 qcTypes.ts（响应结构）分开：请求体的字段比响应多且更易变，
   放一起会让那个文件超 300 行。
   ============================================================ */

import type {
  QcDisposition,
  QcLevel,
  QcSampleSource,
  QcSeverity,
  QcSkipReason,
  QcStrictLevel,
  QcType
} from './qcTypes'

/** 缺陷分类维护（新建 / 编辑） */
export interface QcDefectCodeInput {
  code: string
  name: string
  severity: QcSeverity
  category?: string | null
  defaultAql?: number | null
  remark?: string | null
  sortNo?: number
  enabled?: boolean
}

/** AQL 方案维护 */
export interface QcAqlPlanInput {
  level: QcLevel
  strictLevel: QcStrictLevel
  lotFrom: number
  lotTo: number
  codeLetter: string
  sampleSize: number
  /** -1 = 零容忍 */
  ac: number
  aql: number
  remark?: string | null
  verified?: boolean
}

/** 检验项行 */
export interface QcInspectionItemInput {
  itemName: string
  standard?: string | null
  method?: string | null
  defectCodeId?: number | null
  defectCode?: string | null
  defectName?: string | null
  severity?: QcSeverity | null
  measured?: string | null
  result?: 'OK' | 'NG'
  defectQty?: number
  remark?: string | null
}

/** 建检验单 */
export interface QcInspectionInput {
  inspectionType: QcType
  skuId: number
  lotQty: number
  lotNo?: string | null
  partnerId?: number | null
  srcBillNo?: string | null
  deviceCode?: string | null
  /** 抽样参数。缺省时后端按类型走默认（IQC/FQC 走 AQL，FAI n=1，IPQC 手填） */
  sampleLevel?: QcLevel | null
  strictLevel?: QcStrictLevel | null
  aql?: number | null
  sampleSize?: number | null
  /** FIXED / MANUAL 时由前端指定的样本量 */
  sampleSource?: QcSampleSource | null
  inspector?: string | null
  remark?: string | null
  items: QcInspectionItemInput[]
}

/** 人工补录不良记录 */
export interface QcDefectCreateInput {
  skuId?: number | null
  skuCode?: string | null
  skuName?: string | null
  qty: number
  defectCodeId?: number | null
  defectCode?: string | null
  defectName?: string | null
  severity?: QcSeverity | null
  lotNo?: string | null
  deviceCode?: string | null
  workOrderNo?: string | null
  inspectionNo?: string | null
  /** 货是否已入账。false = 报废不动库存 */
  inStock?: boolean
  srcBillNo?: string | null
  warehouseId?: number | null
  foundAt?: number | null
  remark?: string | null
}

/** 报废处置 */
export interface QcScrapInput {
  qty: number
  costAmount?: number | null
  remark?: string | null
}

/** 返工处置 */
export interface QcReworkInput {
  qty: number
  /** 返工失败退回件数。有效消费 = qty - failQty */
  failQty?: number
  failReason?: string | null
  costAmount?: number | null
  remark?: string | null
}

/** 让步接收 */
export interface QcConcessionInput {
  qty: number
  concessionPartnerId?: number | null
  concessionSoNo?: string | null
  /** 让步审批人。让步是商务决定，要能追溯谁批的 */
  concessionApprover?: string | null
  /** 让价损失 */
  concessionAmount?: number | null
  costAmount?: number | null
  remark?: string | null
}

/** 撤销处置。必须写原因 */
export interface QcRevokeInput {
  reason: string
}

/** 批量分类 */
export interface QcBatchTriageInput {
  defectNos: string[]
  defectCodeId?: number | null
  defectCode?: string | null
  defectName?: string | null
  severity?: QcSeverity | null
}

/** 批量处置 */
export interface QcBatchDisposeInput {
  defectNos: string[]
  action: QcDisposition
  qty?: number
  remark?: string | null
}

/** 批量操作结果：成功几条、跳过几条（各带原因） */
export interface QcBatchResult {
  ok: boolean
  message?: string
  /** 成功处理的单号 */
  done: string[]
  /** 被跳过的单号 + 原因 */
  skipped: { defectNo: string; reason: QcSkipReason; message?: string }[]
}
