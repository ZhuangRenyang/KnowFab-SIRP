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
  /** AI = 模型给的；AGENT = 对话里由 AI 助手判断并建单；LOCAL = 本地规则 */
  aiSource?: 'AI' | 'AGENT' | 'LOCAL'
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
  /** 负责人（DH-85）。空 = 未指派。与 resolvedBy（事后谁修的）不是一回事 */
  assignee?: string | null
  assignedAt?: number | null
  assignedBy?: string | null
}

/** 可被派单的人（DH-85） */
export interface MaintAssignee {
  username: string
  displayName?: string
  role: string
}

/**
 * 维修单已领备件（**成本口径**）。
 *
 * 数量与金额都是绝对值：单据行上的 qty 带方向符号（出库为负），
 * 但「领了 2 个、花了 11.6 块」是正数概念，后端已经翻好号。
 */
export interface MaintParts {
  ok: boolean
  srcOrderNo?: string
  bills: {
    billNo: string
    type: string
    typeLabel: string
    warehouseName?: string | null
    qty: number
    amount: number
    createdAt: number
    operator?: string
  }[]
  lines: {
    billNo: string
    skuId: number
    skuCode: string
    skuName: string
    unit?: string
    qty: number
    unitPrice?: number | null
    amount?: number | null
  }[]
  billCount: number
  totalQty: number
  totalAmount: number
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
  /**
   * 我的待办（DH-85）：派给当前登录人、且未完成的单（ANALYZED / IN_PROGRESS）。
   *
   * 后端直接用 `assignee + status` 查工单表，**不建待办表**——
   * 同一个事实存两份迟早不同步（DH-80：库存两个来源同屏两个数，就是这么来的）。
   * 所以这里拿到的就是工单本身，不是一份「待办副本」。
   */
  my: () => http.get<MaintOrder[]>('/api/erp/maint/my'),

  /**
   * 指派负责人（DH-85）。
   * 传空字符串 = 取消指派。只有 ADMIN/MANAGER/LEADER 能调（后端 403 兜底）。
   */
  assign: (orderNo: string, assignee: string) =>
    http.post<{ ok: boolean; message?: string; assignee?: string | null; order?: MaintOrder }>(
      `/api/erp/maint/${encodeURIComponent(orderNo)}/assign`, { assignee }),

  /** 可选人员：只含能接单的角色（ADMIN/MANAGER/LEADER），不下发密码等字段 */
  assignable: () => http.get<MaintAssignee[]>('/api/erp/maint/assignable'),

  /** 对一条报警做诊断。同步接口——可能要等几秒（AI 通常 3~8 秒） */
  analyze: (alarmEventId: number) =>
    http.post<MaintAnalyzeResult>('/api/erp/maint/analyze', { alarmEventId }),

  start: (orderNo: string) =>
    http.post<{ ok: boolean; message?: string; order?: MaintOrder }>(
      `/api/erp/maint/${encodeURIComponent(orderNo)}/start`, {}),

  /**
   * 领用备件：生成维修领料单（MAINT_OUT）并真实扣库存。
   *
   * 为什么必须走单据：以前维修单只有 `partsReplaced` 一串自由文本，库存一分不动 ——
   * 备件账实不符就是从这儿开始的，而且「这台机一年吃掉多少备件」永远算不出来。
   * 库存不足会整单失败（后端行锁校验），不会写出半张单。
   */
  issueParts: (orderNo: string, body: {
    warehouseId: number
    lines: { skuId: number; qty: number }[]
  }) => http.post<{ ok: boolean; message?: string; billNo?: string; parts?: MaintParts }>(
    `/api/erp/maint/${encodeURIComponent(orderNo)}/parts`, body),

  /** 这张维修单领过的备件与成本（成本口径，作废单不计） */
  parts: (orderNo: string) =>
    http.get<MaintParts>(`/api/erp/maint/${encodeURIComponent(orderNo)}/parts`),

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
