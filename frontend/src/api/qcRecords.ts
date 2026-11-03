/* ============================================================
   质检 · 不良品 / 处置 / 看板 / 对账 的数据结构
   ------------------------------------------------------------
   从 qcTypes.ts 拆出来：那边放检验单与主数据，这边放记录类。
   两边都从 @/api/qc 导出，组件不用关心文件怎么分的。

   字段名以**后端实际返回**为准（后端 a22ca64 起做了"去文案化"）：
   后端只给数与代号，面向用户的文案一律前端 i18n 写——
   后端塞中文的话，英文界面会蹦中文。
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
  /** 分级。**是码不是文案**（severityLabel 已取消，前端自己翻 qc.severity.*） */
  severity: QcSeverity | null
  /** 总件数 */
  qty: number
  /** 已处置件数（处置流水的镜像列，只追加不修改） */
  disposedQty: number
  /**
   * 待处置件数。
   * **直接用后端给的，别用 qty − disposedQty 自己减** ——
   * 两列都是 DECIMAL(18,4)，减出来可能和后端差 0.0001。
   */
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
  /** 后端算好的展示状态。前端只做映射，不许自己拼 */
  displayStatus: QcDisplayStatus
  /** 缺陷代码缺失（未分类）。没有它就不能处置 */
  codeMissing?: boolean
  /** 已撤销次数 */
  voidCount?: number
  /**
   * 处置摘要：结构化数组，**不是中文串**。
   * 前端自己按 i18n 拼文案并排序（别按后端给的顺序直接显示）。
   */
  dispositionSummary?: { action: QcDisposition; qty: number }[]
  /** 该记录的处置流水（详情接口返回） */
  dispositions?: QcDispositionRow[]
}

/** 处置流水（一单可多次处置，只追加、只作废） */
export interface QcDispositionRow {
  id: number
  defectId: number
  seqNo: number
  /** 处置动作。字段名是 action，不是 disposition */
  action: QcDisposition
  qty: number
  /** 返工失败退回件数（只有 REWORK 用）。有效消费 = qty − failQty */
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

/** 处置返回里的告警代号。后端只给代号，文案前端写 */
export type QcWarnCode = 'REWORK_NEED_ISSUE' | 'CONCESSION_NEED_ACK'

/* ---------- 看板 ---------- */

/**
 * 看板聚合。
 *
 * **后端只给数，不给文案**：口径说明那句"两者口径不同是正常的"
 * 由前端 i18n 写（qc.board.caliberNote）。
 */
export interface QcDashboard {
  ok: boolean
  from: string
  to: string
  /** 合格率 %。= 100 − defectRate */
  passRate: number | null
  /** 不良率 %（质检口径） */
  defectRate: number | null
  /** 不良率 %（设备口径）。与质检口径并排显示，两者本就不同源 */
  deviceDefectRate: number | null
  /** 质检口径不良件数 */
  qcDefectQty: number
  /** 质检口径检验总件数（不良率的分子分母） */
  qcInspectedQty: number
  /** 设备口径产出 */
  deviceOutputQty: number
  /** 设备口径不良件数 */
  deviceDefectQty: number
  /** 待处置**件数**（按件算，不按条） */
  pendingQty: number
  /** 待处置**条数**（涉及几张单） */
  pendingRecords: number
  /** 未分类**件数** */
  untriagedQty: number
  /** 未分类**条数** */
  untriagedRecords: number
  /** 报废件数 */
  scrapQty: number
  /** 报废损失金额。取不到显示 --，不显示 0 */
  scrapLoss: number | null
  /** 让步件数 */
  concessionQty: number
  /** 让价损失金额 */
  concessionAmount: number | null
  /** 让步率 % */
  concessionRate: number | null
  /**
   * 逐日趋势。
   * 后端 dashboard 目前**还没有** trend[]，前端先留位置（空态说明接口待提供）。
   */
  daily?: QcDailyPoint[]
  /** 缺陷等级构成 */
  severityMix?: { severity: QcSeverity; qty: number }[]
}

/** 逐日趋势的一点 */
export interface QcDailyPoint {
  date: string
  inspected: number
  defectQty: number
  passRate: number | null
}

/**
 * 帕累托的一行。
 *
 * key === null 的那行是「未分类」——设备通道的记录天生没缺陷代码，
 * 摊到别的分类里帕累托就成废图了。后端固定把它单独放一行。
 */
export interface QcParetoRow {
  /** 分组键：缺陷代码 / 设备码 / 工序码。null = 未分类那一行 */
  key: string | null
  defectCodeId?: number | null
  /** 显示名。null = 未分类 */
  name: string | null
  severity?: string | null
  qty: number
  /** 占比 % */
  pct: number
  /** 累计占比 % */
  cumPct: number
}

/* ---------- 双通道对账 ---------- */

/**
 * 对账的一行（一个工单一组数）。
 *
 * gap = manualQty − deviceQty。
 * challenged 没有第三态：两边不一致就是 true。
 * 正常差异（不同批次 / 不同缺陷）也是 true —— 但那是噪声，
 * 界面只在明确列出来时才提示，不做全局弹窗。
 */
export interface QcReconItem {
  workOrderNo: string
  deviceCode?: string | null
  /** 设备采集数（prod_hourly.defect 之和） */
  deviceQty: number
  /**
   * **人工判的数**（只算人工/检验/退货，不含设备通道）。
   *
   * 口径说明：设备通道的行是 prod_hourly 的镜像，不是"人的判断"——
   * 把它算进质检口径就变成自己跟自己比，gap 永远是 0。
   */
  manualQty: number
  /** manualQty − deviceQty */
  gap: number
  /** 两边不一致 */
  challenged: boolean
  /**
   * 生效值：走统一口径（未定责时设备优先、人工不叠加；定责后以定责为准）。
   * 这才是"这个工单算几件不良"的答案。
   */
  effectiveQty?: number
  /** 这台设备配了 reject 点位吗？没配就没有"设备数"这一说 */
  deviceConfigured?: boolean
  /** 设备当天采到数了吗 */
  deviceCollected?: boolean
  /**
   * 来源代号（不是文案，英文界面不能蹦中文）：
   * DEVICE_PRIORITY 设备优先 / MANUAL_ONLY 只有人工 / NO_DEVICE 没有设备数
   */
  source?: string
  reason?: string | null
}

/** 对账查询结果 */
export interface QcReconResult {
  ok: boolean
  items: QcReconItem[]
}

/**
 * 设备基线（人工补录时对照用）。
 *
 * source 是**代号不是文案**：
 * · DEVICE_PRIORITY 设备配了 reject 点位，采集值优先，人工不叠加
 * · MANUAL_ONLY    没配点位，人工是唯一来源
 * · NO_DEVICE      工单没指定设备
 */
export type QcBaselineSource = 'DEVICE_PRIORITY' | 'MANUAL_ONLY' | 'NO_DEVICE'

/** 设备基线 */
export interface QcDeviceBaseline {
  ok: boolean
  /** 设备已采集的件数 */
  deviceCollectedQty: number
  /** 已登记的人工件数 */
  existingManualQty: number
  /** 设备配了 reject 点位。没有它 → 显示"没有数"，不是"0 件" */
  deviceHasRejectPoint: boolean
  /** 合并后的合计 */
  projectedTotal: number
  /** 合并规则代号 */
  source: QcBaselineSource
}

/**
 * 对账条需要的最小字段集。
 * QcReconItem 与 QcDeviceBaseline 显示的是同一组数，字段名却不同，
 * 所以这里定一个公共形态，各组件按需喂字段。
 */
export interface QcReconView {
  deviceQty?: number
  manualQty?: number
  deviceConfigured?: boolean
  deviceCollected?: boolean
  effectiveQty?: number
  gap?: number
  challenged?: boolean
  source?: string
}

/* ---------- 出货放行 ---------- */

/** 单个 SKU 的放行检查结果 */
export interface QcConcessionHit {
  skuId: number
  skuCode?: string | null
  skuName?: string | null
  blocked: boolean
  /** 拦截原因。两种原因的下一步完全不同，不能合并成一句"被拦了" */
  blockReason: QcBlockReason | null
  /** 下一步动作的**代号**：REGISTER_ACK / DISPOSE_FIRST */
  actionHint?: string | null
  /** 命中的让步流水 */
  records?: {
    dispositionId: number
    defectNo: string
    qty: number
    concessionSoNo: string | null
    approver: string | null
    customerAckNo: string | null
  }[]
}

/** 放行检查整体结果 */
export interface QcConcessionRelease {
  ok: boolean
  sourceWarehouseId?: number | null
  sourceWarehouseName?: string | null
  /** 源仓是禁发仓 */
  fromSpecialWh: boolean
  /** 整体被拦 */
  blocked: boolean
  hits: QcConcessionHit[]
  /** 限制说明的**代号**，如 SKU_LEVEL_MATCH */
  limitCode?: string | null
}
