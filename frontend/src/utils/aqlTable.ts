/* ============================================================
   AQL 参考表（GB/T 2828.1-2012 表 2-A，一般 II × 正常检验）
   ------------------------------------------------------------
   注意：这张表是**本地预览用**，不是判定真值。

   判定真值在后端 erp_qc_aql_plan 表里，那张表是可维护的
   （客户验厂常要求按自己的 AQL 组合执行，常量改一次要发版）。
   前端内置这张表只为了两件事：
     1. 用户还没点保存时，实时显示"会抽多少、允收多少"；
     2. 生成查表依据（"批量 501~1200 落在哪一档 → 字码 J"）。

   真正的 Ac/Re 一律以服务端 /aql/resolve 的返回为准；
   服务端说查不到（NO_AQL_PLAN）就是查不到，界面做「两个出路」卡片，
   **不许前端自己兜底算一个数糊过去**——那样会把主数据缺漏永久藏起来。

   本表与后端 ErpAqlSeeder.buildPlans() 逐格对齐（66 行 = 11 批量档 × 6 个 AQL）。
   改这里之前先改后端，两边必须同步。
   ============================================================ */

/** 批量档下限（含） */
export const LOT_FROM = [2, 9, 16, 26, 51, 91, 151, 281, 501, 1201, 3201] as const
/** 批量档上限（含） */
export const LOT_TO = [8, 15, 25, 50, 90, 150, 280, 500, 1200, 3200, 10000] as const
/** 样本量字码 A~R（跳过 I，与国标一致） */
export const CODE_LETTER = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L'] as const
/** 样本量 n */
export const SAMPLE_SIZE = [2, 3, 5, 8, 13, 20, 32, 50, 80, 125, 200] as const
/** 可选 AQL 值 */
export const AQL_VALUES = [0.65, 1.0, 1.5, 2.5, 4.0, 6.5] as const

/**
 * 接收数矩阵：[批量档下标][AQL 下标] → Ac。
 * Re 恒等于 Ac + 1（Ac = -1 时 Re = 1）。
 */
const AC_MATRIX: readonly (readonly number[])[] = [
  [0, 0, 0, 0, 0, 0],   // A  2~8
  [0, 0, 0, 0, 0, 0],   // B  9~15
  [0, 0, 0, 0, 0, 1],   // C  16~25
  [0, 0, 0, 0, 1, 1],   // D  26~50
  [0, 0, 0, 1, 1, 2],   // E  51~90
  [0, 0, 1, 1, 2, 3],   // F  91~150
  [0, 1, 1, 2, 3, 5],   // G  151~280
  [1, 1, 2, 3, 5, 7],   // H  281~500
  [1, 2, 3, 5, 7, 10],  // J  501~1200
  [2, 3, 5, 7, 10, 14], // K  1201~3200
  [3, 5, 7, 10, 14, 21] // L  3201~10000
]

/** 参考表的一行 */
export interface AqlTableRow {
  lotFrom: number
  lotTo: number
  codeLetter: string
  sampleSize: number
  ac: number
  re: number
  aql: number
}

/** 展开成 66 行（11 批量档 × 6 个 AQL），顺序与后端 findAllOrdered() 一致 */
function expand(): AqlTableRow[] {
  const rows: AqlTableRow[] = []
  for (let i = 0; i < LOT_FROM.length; i++) {
    for (let j = 0; j < AQL_VALUES.length; j++) {
      const ac = AC_MATRIX[i][j]
      rows.push({
        lotFrom: LOT_FROM[i],
        lotTo: LOT_TO[i],
        codeLetter: CODE_LETTER[i],
        sampleSize: SAMPLE_SIZE[i],
        ac,
        re: ac + 1,
        aql: AQL_VALUES[j]
      })
    }
  }
  return rows
}

/** 本地参考表（只读，展开一次） */
export const AQL_REFERENCE_ROWS: readonly AqlTableRow[] = expand()

/**
 * 最小批量档。低于这个值后端直接判全检（ErpAqlService.MIN_LOT = 2）。
 */
export const MIN_LOT = 2

/**
 * 零容忍的 Ac 值。
 * -1 = "一件都不许有"（CR 致命缺陷触发，走一票否决分支）；
 * 0 = "一件都不许有但走抽样分支"（方案表里 Ac 真的等于 0 的格子）。
 * 两者绝不能混：-1 归一化成 0 之后，界面会显示成"普通的一格 Ac=0"，
 * 判定岗就看不出这批是"发现一件就整批退"。
 */
export const AC_ZERO_TOLERANCE = -1

/** 零容忍对应的拒收数 */
export const RE_ZERO_TOLERANCE = 1
