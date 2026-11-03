/* ============================================================
   质检与不良品处置 · 前端 API 契约（统一出口）
   ------------------------------------------------------------
   组件只从 '@/api/qc' 取东西，不要分别 import 五个文件——
   那样过两个月就没人知道哪个文件放的是什么了。

   分成几个文件的原因（不是洁癖，是行数上限 300）：
     qcTypes.ts    枚举类型 + 主数据 + 检验单结构
     qcRecords.ts  不良品 / 处置 / 看板 / 对账结构
     qcInputs.ts   请求体
     qcEnums.ts    枚举常量 + i18n 词条映射
     qcTags.ts     标签色 + 派生状态
     qcMaster.ts   主数据 + AQL 方案接口
     qcOps.ts      检验单 / 不良品 / 处置 / 看板接口

   约定：
   · 前缀统一 /api/erp/qc/
   · 判定的唯一依据是 defectQty（不合格品件数），不是缺陷项数
   · ac = -1 是零容忍，绝不归一化成 0
   · 查不到方案就是查不到，不在前端兜底算
   ============================================================ */

export * from './qcTypes'
export * from './qcRecords'
export * from './qcInputs'
export * from './qcEnums'
export * from './qcTags'
export { qcDefectCodeApi, qcAqlApi } from './qcMaster'
export type { QcDefectCodeListResult, QcAqlPlanListResult, QcApiOk } from './qcMaster'
export { qcInspectionApi, qcDefectApi, qcReportApi } from './qcOps'
export type {
  QcOk,
  QcInspectionListResult,
  QcDefectListResult,
  QcParetoResult
} from './qcOps'
