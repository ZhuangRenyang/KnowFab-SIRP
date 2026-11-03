import { computed, type ComputedRef } from 'vue'
import type { SamplingResult, TraceStep } from '@/utils/aql'
import { resolveSampling } from '@/utils/aql'
import type { AqlTableRow } from '@/utils/aqlTable'

/**
 * 抽样结果的合成与显示文案。
 *
 * <p><b>为什么单独一个 composable</b>：结果的取法有三级优先级（服务端 → 服务端没有 →
 * 本地预览），加上 Ac 的三种显示（零容忍 / 未配置 / 具体数），加起来四十来行。
 * 放在组件里会让组件超过 300 行的门禁，而且这段逻辑是<b>纯计算</b>——
 * 抽出来能直接单测，不用起组件。
 *
 * <p><b>结果取法的优先级</b>：
 * <ol>
 *   <li>{@code resolved} —— 服务端 {@code /aql/resolve} 的返回。**这是判定真值**：
 *       客户的方案表是可维护的（验厂常要求按自己的 AQL 组合执行），
 *       前端内置的参考表只是预览，服务端说多少就是多少；</li>
 *   <li>服务端查不到（found=false）—— 原样透传，不自己兜底算一个 Ac；</li>
 *   <li>服务端结果还没到 —— 用本地参考表先显示一个预览。</li>
 * </ol>
 * trace（查表依据）用服务端方案表算，这样说明和结论来自同一份数据。
 */
export function useAqlResult(params: {
  lotQty: number
  level: string
  strictLevel: string
  aql: number
  plans: AqlTableRow[]
  resolved?: SamplingResult | null
}): {
  result: ComputedRef<SamplingResult | null>
  trace: ComputedRef<TraceStep[]>
} {
  const result = computed<SamplingResult | null>(() => {
    const local = resolveSampling({
      lotQty: params.lotQty,
      level: params.level,
      strictLevel: params.strictLevel,
      aql: params.aql,
      plans: params.plans
    })
    if (!params.resolved) return local
    // 服务端结果 + 本地算出的查表依据
    return { ...params.resolved, trace: local.trace }
  })

  const trace = computed<TraceStep[]>(() => result.value?.trace || [])

  return { result, trace }
}

/**
 * Ac 的显示。
 * -1 = 零容忍（CR 致命缺陷），显示「0 件（零容忍）」——
 * 不能归一化成 0，否则判定岗看不出这批是"发现一件就整批退"。
 */
export function acTextOf(
  r: SamplingResult | null | undefined,
  t: (key: string, params?: Record<string, unknown>) => string
): string {
  if (!r || !r.found) return '--'
  if (r.zeroTolerance) return t('qc.aql.acZeroTol')
  if (r.ac === 0) return t('qc.aql.acNotSet')
  return t('qc.aql.acValue', { ac: r.ac, re: r.re })
}
