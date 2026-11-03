/* ============================================================
   AQL 查表与判定 —— 纯函数
   ------------------------------------------------------------
   全项目唯一有算法风险的地方，所以这里的每一条规则都写清了「为什么」，
   并留了可执行的锚点自测（见 runAqlSelfTest）。

   三条不能碰的红线：

   ① ac = -1 是零容忍，不能归一化成 0。
      -1 走"发现 1 件即整批拒收"的一票否决分支；0 走抽样分支。
      归一化之后界面会显示成"普通的一格 Ac=0"，判定岗看不出这批是全退。

   ② 查不到方案就查不到，不许前端兜底算一个数。
      resolveSampling 返回 found=false，界面做「两个出路」卡片
      （改批量档 / 补方案），不显示一个自己编出来的 Ac。

   ③ 判定只看 defectQty（不合格品件数），不看 defectCr+Ma+Mi（缺陷项数）。
      一件产品同时有尺寸和外观两个缺陷，在 AQL 里仍算 1 件不合格品。
      按项数判会判反：10 项 → 5 件，n=80/Ac=5，按件数合格、按项数不合格。
   ============================================================ */

import {
  AC_ZERO_TOLERANCE,
  AQL_REFERENCE_ROWS,
  MIN_LOT,
  RE_ZERO_TOLERANCE,
  type AqlTableRow
} from './aqlTable'

export { AC_ZERO_TOLERANCE, RE_ZERO_TOLERANCE }

/* ---------- 查表 ---------- */

/** 查表入参 */
export interface SamplingQuery {
  /** 送检批量 N */
  lotQty: number
  /** 检验水平 */
  level: string
  /** 检验严格度 */
  strictLevel: string
  /** AQL 百分比数值，1.0 表示 1.0% */
  aql: number
  /**
   * 致命缺陷分级。传 'CR' 时强制零容忍（Ac=-1/Re=1），与后端
   * ErpAqlService.resolveForSeverity 一致。
   */
  severity?: string | null
  /**
   * 服务端的方案行。传了就以它为准查（真值在服务端，不在前端）；
   * 不传才用内置参考表做纯预览。
   */
  plans?: readonly AqlTableRow[]
}

/** 查表依据的一步。code 是 i18n 词条后缀，params 是插值参数 */
export interface TraceStep {
  code: string
  params: Record<string, string | number>
}

/** 查表结果 */
export interface SamplingResult {
  /** 是否命中方案 */
  found: boolean
  /** 样本量字码；查不到或按全检执行时可能为空 */
  codeLetter: string | null
  /** 样本量 n */
  sampleSize: number
  /** 接收数。-1 = 零容忍，0 = 接收 0 件（方案表里真的有这一格） */
  ac: number
  /** 拒收数。一次抽样里恒等于 Ac + 1 */
  re: number
  /** ac === -1 */
  zeroTolerance: boolean
  /** 是否按全检执行（样本量 ≥ 批量，或批量过小） */
  fullInspection: boolean
  /** 查表依据，界面弹层逐条展示 */
  trace: TraceStep[]
  /** 服务端给的说明（如"样本量不小于批量，按全检执行"）。本地预览时为空 */
  note?: string | null
}

/** 水平归一化：接受 GENERAL_II 与现场的 II / 2 两种写法 */
export function normalizeLevel(raw?: string | null): string {
  if (!raw || !raw.trim()) return 'GENERAL_II'
  const v = raw.trim().toUpperCase().replace(/[-\s]/g, '_')
  if (v === 'I' || v === '1' || v === 'GENERAL_1' || v === 'GENERAL_I') return 'GENERAL_I'
  if (v === 'III' || v === '3' || v === 'GENERAL_3' || v === 'GENERAL_III') return 'GENERAL_III'
  return 'GENERAL_II'
}

/** 严格度归一化；空值 = 正常检验（国标规定的初始状态） */
export function normalizeStrict(raw?: string | null): string {
  if (!raw || !raw.trim()) return 'NORMAL'
  const v = raw.trim().toUpperCase()
  if (v === 'TIGHTENED' || v === 'T') return 'TIGHTENED'
  if (v === 'REDUCED' || v === 'R') return 'REDUCED'
  return 'NORMAL'
}

/** 在方案行里找命中的那一格 */
function findRow(
  plans: readonly AqlTableRow[],
  level: string,
  strict: string,
  lotQty: number,
  aql: number
): AqlTableRow | null {
  for (const p of plans) {
    if (p.lotFrom === undefined) continue
    const row = p as AqlTableRow & { level?: string; strictLevel?: string }
    // 服务端方案行带 level/strictLevel，本地参考表只有一般 II × 正常
    if (row.level && row.level !== level) continue
    if (row.strictLevel && row.strictLevel !== strict) continue
    if (lotQty < p.lotFrom || lotQty > p.lotTo) continue
    if (Math.abs(p.aql - aql) > 1e-9) continue
    return p
  }
  return null
}

/**
 * 查表。
 *
 * 优先级：调用方传入的 plans（服务端方案表）> 内置参考表。
 * 命中与否由 found 表达，**不命中时 sampleSize/ac/re 一律给 0，
 * 不给任何"看起来能用"的默认值**——调用方必须先处理 found=false。
 */
export function resolveSampling(q: SamplingQuery): SamplingResult {
  const level = normalizeLevel(q.level)
  const strict = normalizeStrict(q.strictLevel)
  const lot = Math.floor(Number(q.lotQty) || 0)
  const aql = Number(q.aql)
  const plans = q.plans && q.plans.length ? q.plans : AQL_REFERENCE_ROWS
  const trace: TraceStep[] = []

  trace.push({ code: 'query', params: { lot, level, strict, aql } })

  if (lot < MIN_LOT) {
    // 批量小于最小抽样档：直接全检，Ac 强制 0 / Re 1
    trace.push({ code: 'minLot', params: { lot } })
    return {
      found: true, codeLetter: null, sampleSize: lot, ac: 0, re: 1,
      zeroTolerance: false, fullInspection: true, trace
    }
  }

  const row = findRow(plans, level, strict, lot, aql)
  if (!row) {
    trace.push({ code: 'noPlan', params: { lot, level, strict, aql } })
    return {
      found: false, codeLetter: null, sampleSize: 0, ac: 0, re: 0,
      zeroTolerance: false, fullInspection: false, trace
    }
  }

  trace.push({ code: 'lotBand', params: { lot, from: row.lotFrom, to: row.lotTo } })
  trace.push({ code: 'letter', params: { letter: row.codeLetter } })

  let sampleSize = row.sampleSize
  let ac = row.ac
  let re = row.re
  let fullInspection = false

  if (row.sampleSize >= lot) {
    // 方案样本量不小于批量 → 按全检执行（Ac/Re 仍按这一格的）
    sampleSize = lot
    fullInspection = true
    trace.push({ code: 'fullBySize', params: { sample: row.sampleSize, lot } })
  } else {
    trace.push({ code: 'sample', params: { sample: sampleSize } })
  }

  if (q.severity === 'CR') {
    // 致命缺陷：任意样本量一律零容忍，发现 1 件即整批拒收
    ac = AC_ZERO_TOLERANCE
    re = RE_ZERO_TOLERANCE
    trace.push({ code: 'zeroTolerance', params: {} })
  }

  trace.push({ code: 'acRe', params: { ac, re } })

  return {
    found: true,
    codeLetter: row.codeLetter,
    sampleSize,
    ac,
    re,
    zeroTolerance: ac === AC_ZERO_TOLERANCE,
    fullInspection,
    trace
  }
}

/* ---------- 判定 ---------- */

/** 判定原因码，与后端 ErpInspectionJudge 的 WHY_* 一一对应 */
export type JudgeWhy =
  | 'CR_FOUND'
  | 'ZERO_TOLERANCE'
  | 'AC_ZERO_EXCEEDED'
  | 'WITHIN_AC'
  | 'NO_DEFECT'
  | 'OVER_RE'

/** 判定入参 */
export interface JudgeInput {
  /** 致命缺陷项数。> 0 一票否决 */
  defectCr: number
  /** 不合格品件数（不是缺陷项数） */
  defectQty: number
  /** 接收数 */
  ac: number
  /** 拒收数 */
  re: number
  /** 是否全检 */
  fullInspection?: boolean
}

/** 判定结果 */
export interface JudgeOutcome {
  verdict: 'PASS' | 'FAIL'
  why: JudgeWhy
  /** 自洽性有问题（数据不自洽时不能猜，交给后端抛异常） */
  inconsistent: boolean
}

/**
 * 判定预览。与后端 ErpInspectionJudge.judge 同规则，
 * 目的是让检验员在提交前就看到结论，不是替代后端判定。
 *
 * 差别：后端在数据不自洽时抛异常，这里返回 inconsistent=true，
 * 由界面提示"数据对不上，请核对"而不是给一个猜出来的结论。
 */
export function previewVerdict(inp: JudgeInput): JudgeOutcome {
  const cr = Number(inp.defectCr) || 0
  const qty = Number(inp.defectQty) || 0
  const ac = Number(inp.ac)
  const re = Number(inp.re)

  if (!Number.isFinite(ac) || !Number.isFinite(re) || ac < -1 || re <= ac) {
    return { verdict: 'FAIL', why: 'OVER_RE', inconsistent: true }
  }
  if (cr > 0) return { verdict: 'FAIL', why: 'CR_FOUND', inconsistent: false }
  if (ac === AC_ZERO_TOLERANCE) {
    return qty > 0
      ? { verdict: 'FAIL', why: 'ZERO_TOLERANCE', inconsistent: false }
      : { verdict: 'PASS', why: 'NO_DEFECT', inconsistent: false }
  }
  if (qty <= ac) return { verdict: 'PASS', why: 'WITHIN_AC', inconsistent: false }
  if (qty >= re) {
    return {
      verdict: 'FAIL',
      why: ac === 0 ? 'AC_ZERO_EXCEEDED' : 'OVER_RE',
      inconsistent: false
    }
  }
  return { verdict: 'FAIL', why: 'OVER_RE', inconsistent: true }
}

/* ---------- 判定说明的排版 ---------- */

/** 判定说明的两行 */
export interface JudgeHint {
  /** 主行的 i18n 词条后缀：qc.why.<why> */
  why: JudgeWhy
  /** 副行原文（后端给的 judgeText / judgeHint.message） */
  text: string
  /** 全检还是抽检 */
  fullInspection: boolean
}

/** 后端文案里已经带模式前缀的三种情形（后端 ErpInspectionJudge.label 的输出） */
const MODE_PREFIX_RE = /^(全检|抽检)[：:]\s*/

/**
 * 把后端给的判定说明拆成主行 / 副行。
 *
 * 主行用 qc.why.* 的翻译（6 个原因码），**不加模式前缀**——
 * 后端 message 里后 3 条本来就自带「抽检：」，再加一次会变成「抽检：抽检：」。
 * 副行保留后端原文，只把开头那个可能重复的模式前缀摘掉，
 * 让界面统一按「{模式}：{原文}」渲染，模式词走 i18n。
 */
export function splitJudgeHint(text: string | null | undefined, fullInspection: boolean): string {
  return String(text ?? '').replace(MODE_PREFIX_RE, '').trim()
}

