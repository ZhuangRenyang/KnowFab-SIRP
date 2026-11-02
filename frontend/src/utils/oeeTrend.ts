/**
 * OEE 趋势的算法规子（DH-15）。
 *
 * <p><b>为什么必须有移动平均</b>：单日的 OEE 是瞬时快照，样本量小的时候抖得很厉害——
 * 今天 400 件里 10 件不良（良率 97.5%），明天 0 件（100%），后天 20 件（95%），
 * 这不是设备在变，是分母太小。满屏锯齿的图客户只会觉得「这系统在乱跳」，
 * 真正要看的趋势得先平滑掉。
 *
 * <p><b>为什么 null 不能当 0</b>：缺理想节拍或缺不良数的那天，OEE 是「算不出来」，
 * 不是「效率是 0」。当成 0 的话，图上会多出一段贴着底的直线，
 * 看着像「那几天设备全废了」——那是编出来的结论。
 */

/** OEE 趋势上一个点的内容（跟后端返回的 oee 字段对齐） */
export interface OeePoint {
  availability: number | null
  performance: number | null
  quality: number | null
  oee: number | null
  /** 数据完整度（%）：采到的时长 ÷ 计划生产时间。低了说明这天别当真 */
  coverage?: number
  notes?: string[]
}

/** 移动平均（尾随窗口，含当天）。null 的天跳过而不是当 0；有效值不足 2 个就不给均线 */
export function movingAverage(values: (number | null)[], window: number): (number | null)[] {
  const w = Math.max(2, Math.floor(window || 2))
  const out: (number | null)[] = []
  for (let i = 0; i < values.length; i++) {
    const slice = values.slice(Math.max(0, i - w + 1), i + 1)
      .filter((v): v is number => v !== null && v !== undefined && !Number.isNaN(v))
    out.push(slice.length >= 2 ? Math.round((slice.reduce((a, b) => a + b, 0) / slice.length) * 100) / 100 : null)
  }
  return out
}

/** 从逐日的 OEE 区块里抽一条线。缺的那天是 null（图上断线），不是 0 */
export function pick(points: (OeePoint | null | undefined)[], key: keyof OeePoint): (number | null)[] {
  return points.map((p) => {
    if (!p) return null
    const v = p[key]
    return typeof v === 'number' && !Number.isNaN(v) ? v : null
  })
}

/**
 * 数据完整度低的日期区间（连续的一段算一段）。
 * ECharts 的 markArea 要的是 [[起, 止], ...]，断开的要分成多段。
 */
export function lowCoverageAreas(
  dates: string[],
  points: (OeePoint | null | undefined)[],
  threshold = 90
): Array<[string, string]> {
  const areas: Array<[string, string]> = []
  let start: string | null = null
  for (let i = 0; i < dates.length; i++) {
    const p = points[i]
    const cov = p?.coverage
    // 没数据的那天也算「这天不可信」，一并标出来
    const bad = !p || cov === undefined || cov === null || cov < threshold
    if (bad && start === null) {
      start = dates[i]
    } else if (!bad && start !== null) {
      // 孤立的一天色带宽度为 0，图上根本看不见（今天没采全就是这种情况），
      // 往前借一天，色带才看得见
      const end = dates[i - 1]
      areas.push([i - 2 >= 0 && start === dates[i - 1] ? dates[i - 2] : start, end])
      start = null
    }
  }
  if (start !== null) {
    areas.push([start, dates[dates.length - 1]])
  }
  return areas
}
