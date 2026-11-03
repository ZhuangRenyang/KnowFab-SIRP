/* ============================================================
   质检与不良品处置 · 前端类型契约
   ------------------------------------------------------------
   与后端 com.factory.hub.quality.* 一一对应。

   为什么要单独一个类型文件：
   api/qc.ts 里既有枚举常量、映射函数、请求函数，还有二十多个接口。
   塞进一个文件必然超 300 行，拆开又不能让调用方到处改 import 路径
   —— 所以类型放这里，qc.ts 原样 re-export，调用方只认 @/api/qc。

   枚举取值的来源（**以已提交的后端实现为准**，不是按口头约定猜的）：
   · 检验单状态 DRAFT/SUBMITTED/JUDGED/CANCELLED ← ErpQcInspection.STATUSES
   · 判定结论 PASS/FAIL/CONDITIONAL ← ErpQcInspection.RESULT_*
     （CONDITIONAL 是人工选的让步接收，算法永远不产出）
   · 不良记录来源 DEVICE/INSPECTION/MANUAL/RETURN ← ErpQcDefect.SRC_*
   · 缺陷分级 CR/MA/MI ← ErpQcDefectCode.SEVERITIES
   · 处置动作 SCRAP/REWORK/CONCESSION ← ErpQcDisposition.ACTIONS
   · 抽样来源 AQL/FIXED/MANUAL ← ErpQcInspection.SAMPLE_*
   另保留 PENDING/DONE/VOID 三个别名：后端换枚举时前端不会白屏。
   ============================================================ */

/* ---------- 检验单 ---------- */

/** 检验类型：IQC 来料 / FAI 首件 / IPQC 过程 / FQC 成品 */
export type QcType = 'IQC' | 'FAI' | 'IPQC' | 'FQC'

/**
 * 检验单状态。
 * 前四个是后端现役值；后三个是别名，映射函数一并认，
 * 后端改枚举时界面不会退化成白屏。
 */
export type QcInspectionStatus =
  | 'DRAFT' | 'SUBMITTED' | 'JUDGED' | 'CANCELLED'
  | 'PENDING' | 'DONE' | 'VOID'

/** 判定结论。CONDITIONAL（让步接收）只能人工选，算法不产出 */
export type QcVerdict = 'PASS' | 'FAIL' | 'CONDITIONAL'

/** 抽样参数的来源三档。FIXED 与 MANUAL 必须分开统计 */
export type QcSampleSource = 'AQL' | 'FIXED' | 'MANUAL'

/* ---------- 缺陷与不良 ---------- */

/** 缺陷分级：CR 致命（一票否决）/ MA 严重 / MI 轻微 */
export type QcSeverity = 'CR' | 'MA' | 'MI'

/** 不良记录来源：设备采集 / 检验判定 / 人工补录 / 客户退货 */
export type QcSource = 'DEVICE' | 'INSPECTION' | 'MANUAL' | 'RETURN'

/** 不良记录状态（后端三态）：PENDING / DISPOSED / CANCELLED */
export type QcNcrStatus = 'PENDING' | 'DISPOSED' | 'CANCELLED'

/**
 * 列表展示用的派生态。
 * 前三个与后端状态一一对应；后两个是「处置了一半」的两种具体形态：
 * · REWORK_WAIT_FQC 返工已建单、还没做复检
 * · REWORK_FQC_FAIL 返工复检有失败件
 * 「处置了一半」不设独立状态，用数字表达（已处置 3 · 待处置 5）。
 */
export type QcDisplayStatus =
  | 'PENDING' | 'DISPOSED' | 'CANCELLED'
  | 'REWORK_WAIT_FQC' | 'REWORK_FQC_FAIL'

/** 处置动作：报废 / 返工 / 让步接收 */
export type QcDisposition = 'SCRAP' | 'REWORK' | 'CONCESSION'

/* ---------- AQL ---------- */

/** 检验水平。SPECIAL_* 为预留（GB/T 2828.1 特殊检验水平） */
export type QcLevel =
  | 'GENERAL_I' | 'GENERAL_II' | 'GENERAL_III'
  | 'SPECIAL_S1' | 'SPECIAL_S2' | 'SPECIAL_S3' | 'SPECIAL_S4'

/** 检验严格度。TIGHTENED/REDUCED 表里有字段，本期不做自动转移 */
export type QcStrictLevel = 'NORMAL' | 'TIGHTENED' | 'REDUCED'

/* ---------- 其他枚举 ---------- */

/**
 * 出货放行被拦的原因。
 * 两种原因的下一步完全不同，不能合并成一句「被拦了」：
 * · NO_ACK   有让步流水但没客户同意凭证 → 下一步是登记客户同意
 * · NO_RELEASE 禁发仓里没有任何放行依据 → 下一步是去不良品页处置
 */
export type QcBlockReason = 'NO_ACK' | 'NO_RELEASE'

/** 退货原因。必选、无默认值：默认 OTHER 会让质量退货被系统性漏掉 */
export type QcReturnReason = 'QUALITY' | 'OTHER'

/** 批量操作时被跳过的原因（记录已处置 / 与选中项冲突 / 找不到 / 状态不允许） */
export type QcSkipReason = 'DISPOSED' | 'CONFLICT' | 'NOT_FOUND' | 'INVALID_STATE'

/* ---------- 主数据 ---------- */

/** 缺陷分类（质量字典） */
export interface QcDefectCode {
  id: number
  code: string
  name: string
  severity: QcSeverity
  category: string | null
  /** 默认 AQL（百分比，1.0 = 1.0%）。只是建单预填，不是标准答案 */
  defaultAql: number | null
  remark: string | null
  sortNo: number
  /** 停用后不再出现在新建下拉里，历史记录照常显示 */
  enabled: boolean
}

/** AQL 抽样方案的一行（= 标准表里的一个格子） */
export interface QcAqlPlan {
  id: number
  level: QcLevel
  strictLevel: QcStrictLevel
  lotFrom: number
  lotTo: number
  /** 样本量字码 A~R */
  codeLetter: string
  /** 样本量 n */
  sampleSize: number
  /** 接收数 Ac。-1 = 零容忍（CR），0 = 一件都不许有 */
  ac: number
  /** 拒收数 Re。一次抽样里恒等于 Ac + 1 */
  re: number
  aql: number
  /** false = 这一格是推算的，还没对着纸本核对过 */
  verified: boolean
  zeroTolerance: boolean
  remark: string | null
}

/** 查表结果 */
export interface QcAqlResolve {
  ok: boolean
  codeLetter: string | null
  sampleSize: number
  ac: number
  re: number
  planId: number | null
  fullInspection: boolean
  note: string | null
  /** ac === -1 */
  zeroTolerance: boolean
  /** 失败时给出（NO_AQL_PLAN 等），供界面做「两个出路」卡片 */
  code?: string
  message?: string
}

/* ---------- 检验单 ---------- */

/** 检验单头。字段与后端 ErpQcInspectionView.header() 完全对齐 */
export interface QcInspectionHeader {
  inspectionNo: string
  inspectionType: QcType
  status: QcInspectionStatus
  result: QcVerdict | null
  skuId: number
  skuCode: string | null
  skuName: string
  skuSpec: string | null
  skuUnit: string | null
  partnerId: number | null
  partnerName: string | null
  lotNo: string | null
  /** 送检批量 N（抽样方案按它查档） */
  lotQty: number
  srcBillNo: string | null
  deviceCode: string | null
  sampleSource: QcSampleSource
  sampleLevel: QcLevel | null
  strictLevel: QcStrictLevel | null
  aql: number | null
  codeLetter: string | null
  sampleSize: number | null
  ac: number | null
  re: number | null
  zeroTolerance: boolean
  fullInspection: boolean
  /** 不合格品数 —— 判定唯一依据，不等于 defectCr+Ma+Mi */
  defectQty: number
  defectCr: number
  defectMa: number
  defectMi: number
  inspector: string | null
  inspectedAt: number | null
  judgedBy: string | null
  judgedAt: number | null
  remark: string | null
  createdBy: string | null
  createdAt: number
  updatedAt: number
  voidedAt: number | null
  voidReason: string | null
}

/** 检验项明细行 */
export interface QcInspectionItem {
  id: number
  lineNo: number
  itemName: string
  standard: string | null
  method: string | null
  defectCodeId: number | null
  defectCode: string | null
  defectName: string | null
  severity: QcSeverity | null
  measured: string | null
  result: 'OK' | 'NG'
  /** 本项判为不合格的件数（与单头件数不是同一口径） */
  defectQty: number
  remark: string | null
}

/**
 * 检验单详情。
 *
 * 后端返回的是 `{ ok, header, items, defects }` 三层结构，
 * 前端在 api/qcOps 里把 header 摊平到顶层（`{...header, ok, items, defects}`），
 * 组件里就能直接 `detail.inspectionNo`，不用到处写 `detail.header.xxx`。
 */
export interface QcInspectionDetail extends QcInspectionHeader {
  ok: boolean
  items: QcInspectionItem[]
  /** 这张检验单产生的不良记录 */
  defects: QcInspectionDefect[]
}

/** 检验单关联的不良记录（精简视图） */
export interface QcInspectionDefect {
  defectNo: string
  qty: number
  inStock: boolean
  status: QcNcrStatus
  severity: QcSeverity | null
}

/** 检验单列表行 */
export interface QcInspectionRow extends QcInspectionHeader {
  /** 列表用的派生展示状态 */
  displayStatus: QcDisplayStatus
}

/** 判定结果 */
export interface QcJudgeResult {
  ok: boolean
  message?: string
  result?: QcVerdict
  /** 后端判定原因码：CR_FOUND / ZERO_TOLERANCE / AC_ZERO_EXCEEDED / WITHIN_AC / NO_DEFECT / OVER_RE */
  why?: string
  /** 后端给的判定说明原文 */
  judgeText?: string
}

