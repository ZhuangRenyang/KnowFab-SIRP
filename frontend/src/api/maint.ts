import { http } from './http'

/**
 * 设备维修闭环。
 *
 * 「诊断」不是从零算——它把**报警码字典含义 + 这台机历史同类记录 +
 * 设备信息**一起喂给模型（或未配 AI 时用本地规则），所以：
 * - 有历史时，建议里会直接点出「上次是某个原因造成的」
 * - 没配 AI 时照样有内容，但 `aiSource` 会是 `LOCAL`，界面必须标出来
 */
export interface MaintOrder {
  orderNo: string
  alarmEventId?: number
  deviceCode: string
  deviceName?: string
  alarmCode?: string
  alarmText?: string
  /** 建议原文（AI 或本地规则） */
  aiAdvice?: string
  /** AI = 模型给的；LOCAL = 本地规则（未配 AI 或调用失败） */
  aiSource?: 'AI' | 'LOCAL'
  aiAt?: number
  aiElapsedMs?: number
  causeCategory?: string
  causeCategoryText?: string
  rootCause?: string
  solution?: string
  partsReplaced?: string
  downtimeMin?: number
  status: 'ANALYZED' | 'IN_PROGRESS' | 'RESOLVED' | 'CANCELLED'
  statusText?: string
  createdBy?: string
  createdAt?: number
  startedAt?: number
  resolvedAt?: number
  resolvedBy?: string
}

/** 历史同类维修记录的摘要 */
export interface MaintSample {
  orderNo: string
  rootCause?: string
  solution?: string
  partsReplaced?: string
  downtimeMin?: number
  causeCategoryText?: string
  resolvedAt?: number
}

export interface MaintAnalyzeResult {
  ok: boolean
  message?: string
  /** true = 这条报警已有未结案的单，直接返回了那张（没重复建） */
  reused?: boolean
  order?: MaintOrder
  history?: MaintSample[]
}

export const maintApi = {
  /** 对一条报警做诊断。同步接口——可能要等几秒（AI 通常 3~8 秒） */
  analyze: (alarmEventId: number) =>
    http.post<MaintAnalyzeResult>('/api/erp/maint/analyze', { alarmEventId }),

  start: (orderNo: string) =>
    http.post<{ ok: boolean; message?: string; order?: MaintOrder }>(
      `/api/erp/maint/${encodeURIComponent(orderNo)}/start`, {}),

  /**
   * 结案：回填实际原因。
   * `causeCategory` 与 `rootCause` 是**必填**——这两项是「下次同类报警能参考」的全部依据。
   */
  resolve: (orderNo: string, body: {
    causeCategory: string
    rootCause: string
    solution?: string
    partsReplaced?: string
    downtimeMin?: number | null
  }) => http.post<{ ok: boolean; message?: string; order?: MaintOrder }>(
    `/api/erp/maint/${encodeURIComponent(orderNo)}/resolve`, body),

  /** 取消（误报/重复报）：理由必填，否则统计不出「多少报警是误报」 */
  cancel: (orderNo: string, reason: string) =>
    http.post<{ ok: boolean; message?: string; order?: MaintOrder }>(
      `/api/erp/maint/${encodeURIComponent(orderNo)}/cancel`, { reason }),

  list: (status?: string, kw?: string, page = 0, size = 20) => {
    const q = new URLSearchParams()
    if (status) q.set('status', status)
    if (kw) q.set('kw', kw)
    q.set('page', String(page))
    q.set('size', String(size))
    return http.get<{ ok: boolean; total: number; rows: MaintOrder[] }>(
      '/api/erp/maint?' + q.toString())
  },

  /**
   * 经验统计。三个维度回答三个真问题：
   * 哪类故障最多（备件储备）/ 哪台机最爱坏（预防性维护）/ 哪个码最常见（该不该补进字典）。
   */
  stats: () => http.get<{
    ok: boolean
    total: number
    resolved: number
    /** 结案率低 = 建了单没人修，或修了没人回填 */
    resolveRate: number | null
    byCause: { category: string; text: string; count: number }[]
    byDevice: { deviceCode: string; deviceName: string; count: number; avgDowntimeMin: number | null }[]
    byAlarmCode: { alarmCode: string; count: number; avgDowntimeMin: number | null }[]
  }>('/api/erp/maint/stats'),

  categories: () => http.get<{
    ok: boolean
    categories: { value: string; text: string }[]
    statuses: { value: string; text: string }[]
  }>('/api/erp/maint/categories')
}
