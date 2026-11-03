/* ============================================================
   AQL 锚点自测（可执行）
   ------------------------------------------------------------
   项目当前没有测试运行器，且不允许新增依赖，所以这里做成一个可调用的纯函数：
   在浏览器控制台 runAqlSelfTest() 就能跑；将来接上 vitest 也能直接当单测用。

   覆盖：
   · 三个国标锚点（QUALITY-SPEC §6，人工对纸本核过，禁止用算法反推）
   · 零容忍不被归一化成 0
   · 查不到方案时 found=false 且不返回兜底 Ac
   · 判定全分支（CR 一票否决 / 零容忍 / 边界 =ac / =re / 不自洽）
   ============================================================ */

import { previewVerdict, resolveSampling } from './aql'

/* ---------- 锚点自测 ---------- */

/** 锚点用例：GB/T 2828.1 表 2-A，人工对纸本核过（SPEC §6） */
export const AQL_ANCHORS = [
  { lotQty: 800, level: 'GENERAL_II', strictLevel: 'NORMAL', aql: 1.0, codeLetter: 'J', sampleSize: 80, ac: 2, re: 3 },
  { lotQty: 800, level: 'GENERAL_II', strictLevel: 'NORMAL', aql: 2.5, codeLetter: 'J', sampleSize: 80, ac: 5, re: 6 },
  { lotQty: 30, level: 'GENERAL_II', strictLevel: 'NORMAL', aql: 0.65, codeLetter: 'D', sampleSize: 8, ac: 0, re: 1 }
] as const

/** 自测一条 */
export interface SelfTestCase {
  name: string
  passed: boolean
  detail: string
}

/**
 * 锚点自测（可执行）。
 *
 * 项目当前没有测试运行器，且不允许新增依赖，所以这里做成一个可调用的纯函数：
 * 在浏览器控制台 `runAqlSelfTest()` 就能跑，将来接上 vitest 也能直接当单测用。
 *
 * 覆盖：
 * · 三个国标锚点（SPEC §6，人工对纸本核过，禁止用算法反推）
 * · 零容忍不被归一化成 0
 * · 查不到方案时 found=false 且不返回兜底 Ac
 * · 判定全分支（CR 一票否决 / 零容忍 / 边界 =ac / =re / 不自洽）
 */
export function runAqlSelfTest(): { passed: boolean; total: number; cases: SelfTestCase[] } {
  const cases: SelfTestCase[] = []
  const check = (name: string, passed: boolean, detail = '') =>
    cases.push({ name, passed, detail: detail || (passed ? '' : '不匹配') })

  for (const a of AQL_ANCHORS) {
    const r = resolveSampling({
      lotQty: a.lotQty, level: a.level, strictLevel: a.strictLevel, aql: a.aql
    })
    const ok = r.found && r.codeLetter === a.codeLetter &&
      r.sampleSize === a.sampleSize && r.ac === a.ac && r.re === a.re
    check(`锚点 ${a.codeLetter} 批量${a.lotQty} AQL${a.aql}`, ok,
      ok ? '' : `期望 ${a.codeLetter}/n=${a.sampleSize}/Ac=${a.ac}/Re=${a.re}，` +
        `实际 ${r.codeLetter}/n=${r.sampleSize}/Ac=${r.ac}/Re=${r.re}`)
  }

  const cr = resolveSampling({
    lotQty: 800, level: 'GENERAL_II', strictLevel: 'NORMAL', aql: 1.0, severity: 'CR'
  })
  check('CR 致命缺陷 → 零容忍 Ac=-1 且不被归一化成 0',
    cr.zeroTolerance && cr.ac === -1 && cr.re === 1,
    `实际 ac=${cr.ac} re=${cr.re} zeroTolerance=${cr.zeroTolerance}`)

  const miss = resolveSampling({
    lotQty: 999999, level: 'GENERAL_I', strictLevel: 'NORMAL', aql: 0.65
  })
  check('查不到方案 → found=false 且不返回兜底 Ac',
    !miss.found && miss.ac === 0 && miss.sampleSize === 0,
    `found=${miss.found} ac=${miss.ac}`)

  const j1 = previewVerdict({ defectCr: 1, defectQty: 0, ac: 2, re: 3 })
  check('判定：CR 一票否决', j1.verdict === 'FAIL' && j1.why === 'CR_FOUND')
  const j2 = previewVerdict({ defectCr: 0, defectQty: 1, ac: -1, re: 1 })
  check('判定：零容忍下 1 件即 FAIL', j2.verdict === 'FAIL' && j2.why === 'ZERO_TOLERANCE')
  const j3 = previewVerdict({ defectCr: 0, defectQty: 2, ac: 2, re: 3 })
  check('判定：等于 Ac 判 PASS', j3.verdict === 'PASS' && j3.why === 'WITHIN_AC')
  const j4 = previewVerdict({ defectCr: 0, defectQty: 3, ac: 2, re: 3 })
  check('判定：等于 Re 判 FAIL', j4.verdict === 'FAIL' && j4.why === 'OVER_RE')
  const j5 = previewVerdict({ defectCr: 0, defectQty: 9, ac: 2, re: 3 })
  check('判定：数据不自洽时不做猜测', j5.inconsistent === true)

  const passed = cases.filter((c) => c.passed).length
  return { passed: passed === cases.length, total: cases.length, cases }
}
