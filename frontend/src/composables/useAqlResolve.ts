import { ref, watch } from 'vue'
import { qcAqlApi } from '@/api/qc'
import { resolveSampling, type SamplingResult } from '@/utils/aql'
import type { AqlTableRow } from '@/utils/aqlTable'

/**
 * AQL 查表（建单时用）。
 *
 * 为什么要单独抽出来：这段逻辑有两个坑，抽出来才好复用也好测。
 *
 * ① **判定真值在服务端**。客户的方案表是可维护的（验厂常要求按自己的
 *    AQL 组合执行），前端内置的参考表只是预览用的。服务端说多少就是多少。
 *
 * ② **查不到就查不到**。服务端返回 NO_AQL_PLAN 时，这里**不填兜底数**，
 *    只把 found=false 传下去，让界面弹「两个出路」卡片。
 *    前端自己编一个 Ac 糊过去的话，主数据缺漏这件事就永远查不出来了——
 *    而主数据缺漏恰恰是这个模块最需要暴露的问题。
 *
 * 查表依据（trace）用服务端的方案表算，这样"说明"和"结论"来自同一份数据，
 * 不会出现结论按服务端、说明按本地表的两张皮。
 */
export function useAqlResolve() {
  /** 服务端方案表 */
  const plans = ref<AqlTableRow[]>([])
  /** 服务端 /aql/resolve 的结果 */
  const serverResult = ref<SamplingResult | null>(null)
  /** 主数据缺失 → 兜底成临时全检（Ac 强制 0 / Re 1），并标出来 */
  const manualMode = ref(false)

  /** 拉方案表。取不到就留空，查表依据退回本地参考表 */
  async function loadPlans() {
    try {
      const r = await qcAqlApi.plans()
      plans.value = (r.rows || []).map((p) => ({
        // level / strictLevel 必须带上：抽样下拉要按「实际有哪些组合」列选项，
        // 丢了这两个字段就只能列全枚举 → 用户选到没数据的组合必然报"没配方案"
        level: p.level,
        strictLevel: p.strictLevel,
        lotFrom: p.lotFrom, lotTo: p.lotTo, codeLetter: p.codeLetter,
        sampleSize: p.sampleSize, ac: p.ac, re: p.re, aql: p.aql
      }))
    } catch {
      plans.value = []
    }
  }

  /**
   * 调服务端查表。
   * @param p 查询条件
   * @param severity 致命缺陷分级。CR 时服务端强制零容忍（Ac=-1）
   */
  async function resolve(p: {
    lotQty: number
    level: string
    strictLevel: string
    aql: number
  }) {
    try {
      const r = await qcAqlApi.resolve({
        level: p.level,
        strictLevel: p.strictLevel,
        lotQty: p.lotQty,
        aql: p.aql
      })
      if (!r.ok) {
        // 查不到：不填兜底数，把 code 原样带上去
        serverResult.value = {
          found: false, codeLetter: null, sampleSize: 0, ac: 0, re: 0,
          zeroTolerance: false, fullInspection: false, trace: [],
          note: r.message || null,
          reasonCode: r.code || null
        }
        manualMode.value = true
        return
      }
      serverResult.value = {
        found: true,
        codeLetter: r.codeLetter,
        sampleSize: r.sampleSize,
        ac: r.ac,
        re: r.re,
        zeroTolerance: r.zeroTolerance,
        fullInspection: r.fullInspection,
        trace: [],
        note: null,
        reasonCode: r.reasonCode || null
      }
      manualMode.value = false
    } catch {
      // 请求本身失败：退回本地预览，不显示兜底（失败 ≠ 主数据缺失）
      serverResult.value = null
      manualMode.value = false
    }
  }

  /** 本地预览（服务端结果还没到时用） */
  function localPreview(p: { lotQty: number; level: string; strictLevel: string; aql: number }) {
    return resolveSampling({
      lotQty: p.lotQty,
      level: p.level,
      strictLevel: p.strictLevel,
      aql: p.aql,
      plans: plans.value
    })
  }

  /** 服务端结果到达后，兜底标记跟着结果走 */
  watch(serverResult, (r) => {
    if (r) manualMode.value = !r.found
  })

  return { plans, serverResult, manualMode, loadPlans, resolve, localPreview }
}
