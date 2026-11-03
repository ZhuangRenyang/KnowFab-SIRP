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

   ⚠️ **本表只覆盖「正常检验」**。加严（国标表 2-B）与放宽（表 2-C）是另外两张表：
   行数不同（表 2-B 多一行 S=3150）、样本量不同（表 2-C 整体降两档，
   A/B/C 都是 2 件、R 只有 800）、Ac 也不同（M@AQL1.5 三张表分别是 10/11、8/9、6/7）。

   所以**加严/放宽一律以服务端为准**：本地预览只在正常检验下可信。
   为什么不在前端也抄两份：抄了就要维护三份，改一边忘两边的风险
   远大于"预览短暂不准"的代价（组件本来就标注「服务端会重算」）。

   正常检验这一份与后端 ErpAqlStandardGrid.GRID 逐格对齐（90 格 = 15 批量档 × 6 个 AQL）。
   改这里之前先改后端，两边必须同步。
   ============================================================ */

/**
 * 批量档下限（含）——国标表 1 的 **15 档**（与后端 ErpAqlLevelMap.LOT_FROM 一致）。
 *
 * 之前这里只有 11 档（到 10000），批量超过 1 万时本地预览查不到方案，
 * 只有服务端能查到 —— 预览与真值不一致，用户会以为"系统有问题"。
 */
export const LOT_FROM =
  [2, 9, 16, 26, 51, 91, 151, 281, 501, 1201, 3201, 10001, 35001, 150001, 500001] as const
/** 批量档上限（含）；最后一档是「500001 及以上」 */
export const LOT_TO =
  [8, 15, 25, 50, 90, 150, 280, 500, 1200, 3200, 10000, 35000, 150000, 500000, 999999999] as const
/**
 * 样本量字码——**一般检验水平 II 列的 15 个字码**（A~Q，跳过 I 与 O）。
 *
 * 注意这**不是**表 2-A 的全部 16 行（A~R）：表 1 的水平 II 列最大只给到 Q。
 * 表 2-A 里的 R 行只在其它检验水平（如 III）下才会用到，
 * 而本表是「一般 II 正常检验」的固定参考表，所以只到 Q。
 */
export const CODE_LETTER =
  ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M', 'N', 'P', 'Q'] as const
/** 样本量 n（国标表 1 的字码列，n=2 ~ 1250） */
export const SAMPLE_SIZE =
  [2, 3, 5, 8, 13, 20, 32, 50, 80, 125, 200, 315, 500, 800, 1250] as const
/** 可选 AQL 值 */
export const AQL_VALUES = [0.65, 1.0, 1.5, 2.5, 4.0, 6.5] as const

/**
 * 国标表 2-A 的原始格子（GB/T 2828.1-2012，一般 II × 正常检验）。
 *
 * 数值格写成 "Ac Re"（如 "0 1"）；箭头格写成 "↓"（向下借）或 "↑"（向上借）。
 *
 * **为什么必须保留箭头**：国标表 2-A 里有 27 个箭头格，国标 10.3 规定
 * 「若在相交处是箭头，则沿着箭头方向读出第一个接收数 Ac 和拒收数 Re，
 *   然后由其接收数和拒收数所在行向左，在样本量栏内读出相应的样本量 n」。
 * 即箭头格要**借**相邻行的方案，Ac/Re 与样本量 n 一起借。
 *
 * 漏掉这一步的后果：接收数往往碰巧一样（箭头多指向 Ac 相同的相邻行），
 * 所以只看 Ac 看不出错；但样本量会差一档 —— **抽样强度不足，该判不合格的批次被放行**。
 * 比如 H 档（批量 281~500）在 AQL 0.65 下要抽 80 件（借 J 行），不是 50 件。
 *
 * 与后端 ErpAqlStandardGrid.GRID 逐格对齐，改这里之前先改后端。
 */
const STANDARD_GRID: readonly (readonly string[])[] = [
  //  0.65    1.0     1.5     2.5     4.0     6.5
  ['↓', '↓', '↓', '↓', '↓', '0 1'],       // A  2~8
  ['↓', '↓', '↓', '↓', '0 1', '↑'],       // B  9~15
  ['↓', '↓', '↓', '0 1', '↑', '↓'],       // C  16~25
  ['↓', '↓', '0 1', '↑', '↓', '1 2'],      // D  26~50
  ['↓', '0 1', '↑', '↓', '1 2', '2 3'],     // E  51~90
  ['0 1', '↑', '↓', '1 2', '2 3', '3 4'],   // F  91~150
  ['↑', '↓', '1 2', '2 3', '3 4', '5 6'],   // G  151~280
  ['↓', '1 2', '2 3', '3 4', '5 6', '7 8'],  // H  281~500
  ['1 2', '2 3', '3 4', '5 6', '7 8', '10 11'],   // J  501~1200
  ['2 3', '3 4', '5 6', '7 8', '10 11', '14 15'],  // K  1201~3200
  ['3 4', '5 6', '7 8', '10 11', '14 15', '21 22']  // L  3201~10000
]

/** 参考表的一行 */
export interface AqlTableRow {
  /**
   * 检验水平与严格度（可选）。
   *
   * 本地内置参考表只有一般 II / 正常检验（表里写死），所以这两个字段是空的；
   * 服务端方案表才有完整值。抽样下拉按「实际有哪些组合」列选项时就靠它们。
   */
  level?: string
  strictLevel?: string
  lotFrom: number
  lotTo: number
  codeLetter: string
  sampleSize: number
  ac: number
  re: number
  aql: number
}

/**
 * 按国标 10.3 解析一格：箭头格沿箭头方向借第一个数字格，**Ac/Re 与样本量一起借**。
 */
function resolveCell(bandIndex: number, aqlIndex: number): { ac: number; re: number; sampleSize: number } {
  const raw = STANDARD_GRID[bandIndex][aqlIndex]
  if (raw !== '↓' && raw !== '↑') {
    const [ac, re] = raw.split(/\s+/).map(Number)
    return { ac, re, sampleSize: SAMPLE_SIZE[bandIndex] }
  }
  const step = raw === '↓' ? 1 : -1
  for (let i = bandIndex + step; i >= 0 && i < CODE_LETTER.length; i += step) {
    const t = STANDARD_GRID[i][aqlIndex]
    if (t === '↓' || t === '↑') continue
    const [ac, re] = t.split(/\s+/).map(Number)
    return { ac, re, sampleSize: SAMPLE_SIZE[i] }
  }
  // 箭头指向表外：GB/T 2828.1 表 2-A 里不会出现。退化成 Ac=0 而不是编一个假方案。
  return { ac: 0, re: 1, sampleSize: SAMPLE_SIZE[bandIndex] }
}

/** 展开成 66 行（11 批量档 × 6 个 AQL），顺序与后端 findAllOrdered() 一致 */
function expand(): AqlTableRow[] {
  const rows: AqlTableRow[] = []
  for (let i = 0; i < LOT_FROM.length; i++) {
    for (let j = 0; j < AQL_VALUES.length; j++) {
      const c = resolveCell(i, j)
      rows.push({
        lotFrom: LOT_FROM[i],
        lotTo: LOT_TO[i],
        codeLetter: CODE_LETTER[i],
        sampleSize: c.sampleSize,
        ac: c.ac,
        re: c.re,
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
