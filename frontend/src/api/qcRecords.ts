/* ============================================================
   质检 · 不良品 / 处置 / 看板 / 对账 的数据结构
   ------------------------------------------------------------
   从 qcTypes.ts 拆出来：那边放检验单与主数据，这边放记录类。
   两边都从 @/api/qc 导出，组件不用关心文件怎么分的。
   ============================================================ */

import type {
  QcBlockReason,
  QcDisposition,
  QcDisplayStatus,
  QcNcrStatus,
  QcSeverity,
  QcSource
} from './qcTypes'

/* ---------- 不良品与处置 ---------- */

/** 不良记录（不良品队列的一行） */
export interface QcDefectRow {
  defectNo: string
  source: QcSource
  sourceKey: string
  inspectionNo: string | null
  workOrderNo: string | null
  deviceCode: string | null
  skuId: number | null
  skuCode: string | null
  skuName: string | null
  lotNo: string | null
  defectCodeId: number | null
  defectCode: string | null
  defectName: string | null
  severity: QcSeverity | null
  /** 总件数 */
  qty: number
  /** 已处置件数（由处置流水 sum 出来，只追加不修改） */
  disposedQty: number
  /** 待处置件数 = qty - disposedQty */
  pendingQty: number
  /** 已入账。false = 不良从未入账，报废不动库存 */
  inStock: boolean
  srcBillNo: string | null
  warehouseId: number | null
  foundAt: number
  registeredBy: string | null
  status: QcNcrStatus
  remark: string | null
  voidedAt: number | null
  voidedBy: string | null
  voidReason: string | null
  /** 列表展示用的派生状态 */
  displayStatus: QcDisplayStatus
  /** 该记录的处置流水（列表接口带不带取决于后端，前端有就显示） */
  dispositions?: QcDispositionRow[]
}

/** 处置流水（一单可多次处置，只追加、只作废） */
export interface QcDispositionRow {
  id: number
  defectId: number
  seqNo: number
  action: QcDisposition
  qty: number
  /** 返工失败退回件数（只有 REWORK 用）。有效消费 = qty - failQty */
  failQty: number
  failReason: string | null
  stockBillNo: string | null
  reworkOrderNo: string | null
  fqcInspectionNo: string | null
  concessionPartnerId: number | null
  concessionSoNo: string | null
  concessionApprover: string | null
  concessionAckBillNo: string | null
  concessionAckAt: number | null
  concessionAckBy: string | null
  concessionAmount: number | null
  costAmount: number | null
  costPartial: boolean
  operator: string
  disposedAt: number
  remark: string | null
  voidedAt: number | null
  voidedBy: string | null
  voidReason: string | null
}

/* ---------- 看板 ---------- */

/** 看板聚合。口径说明由后端给出，前端原样展示，不自己算 */
export interface QcDashboard {
  ok: boolean
  from: string
  to: string
  /** 合格率 % */
  passRate: number | null
  /** 不良率 % */
  defectRate: number | null
  /** 待处置件数（按件算，不按条） */
  pendingQty: number
  /** 待处置记录数（按条算） */
  pendingRecords: number
  /** 让步率 % */
  concessionRate: number | null
  /** 报废损失金额 */
  scrapLoss: number | null
  /** 检验总件数 */
  totalInspected: number
  /** 判定不合格总件数 */
  totalDefectQty: number
  /** 未分类记录数（帕累托里会落成「未分类」行） */
  untriagedCount: number
  /** 口径说明原文 */
  caliberNote: string
  /** 逐日趋势 */
  daily: QcDailyPoint[]
  /** 缺陷等级构成 */
  severityMix: { severity: QcSeverity; qty: number }[]
}

/** 逐日趋势的一点 */
export interface QcDailyPoint {
  date: string
  inspected: number
  defectQty: number
  passRate: number | null
}

/** 帕累托的一行 */
export interface QcParetoRow {
  /** 缺陷代码或设备编码 */
  key: string
  /** 显示名 */
  name: string
  qty: number
  /** 占比 % */
  pct: number
  /** 累计占比 % */
  cumPct: number
  /** true = 「未分类」那一行 */
  untriaged: boolean
}

/* ---------- 双通道对账 ---------- */

/**
 * 双通道对账结果。
 * 对象 A（生产不良数）内部的两条采集通道：设备点位 vs 人工补录。
 * 合并规则见 QUALITY-SPEC §2.5：有设备数据以设备为准，两边都 > 0 标记 challenged
 * 而不是相加。
 */
export interface QcRecon {
  ok: boolean
  workOrderNo: string
  /** 设备采到的不良件数 */
  deviceQty: number
  /** 人工补录的不良件数 */
  manualQty: number
  /** 设备配了 reject 点位 */
  deviceConfigured: boolean
  /** 当日有设备采集 */
  deviceCollected: boolean
  /** 合并后生效的件数 */
  effectiveQty: number
  /** 以哪条通道为准 */
  source: 'DEVICE' | 'MANUAL' | 'CHALLENGED'
  /** 口径说明原文 */
  note: string
}

/** 设备基线（人工补录时对照用） */
export interface QcDeviceBaseline {
  ok: boolean
  workOrderNo: string
  deviceQty: number
  deviceConfigured: boolean
  deviceCollected: boolean
  /** 同小时人工已补录的件数 */
  manualQty: number
  note: string
}

/* ---------- 出货放行 ---------- */

/** 单个 SKU 的放行检查结果 */
export interface QcConcessionHit {
  skuId: number
  skuCode: string | null
  skuName: string | null
  blocked: boolean
  blockReason: QcBlockReason | null
  concessionNo: string | null
  ackBillNo: string | null
}

/** 放行检查整体结果 */
export interface QcConcessionRelease {
  ok: boolean
  /** 源仓是禁发仓 */
  fromSpecialWh: boolean
  /** 整体被拦 */
  blocked: boolean
  hits: QcConcessionHit[]
}

