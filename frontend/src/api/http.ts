/* 唯一的 HTTP 出口：统一 401/403 处理、JSON/FormData 约定。
   业务代码禁止各自处理 401（PRD 风险 6 / 架构师方案 §5.3）。 */

export class ApiError extends Error {
  status: number
  body: unknown
  constructor(status: number, message: string, body: unknown) {
    super(message)
    this.status = status
    this.body = body
  }
}

let onUnauthorized: (() => void) | null = null
/** 由 main.ts 装配：401 时清空登录态并跳登录页 */
export function setUnauthorizedHandler(fn: (() => void) | null) {
  onUnauthorized = fn
}

async function request<T = unknown>(url: string, opts: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { ...((opts.headers as Record<string, string>) || {}) }
  if (opts.body && !(opts.body instanceof FormData) && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json'
  }
  const res = await fetch(url, { ...opts, headers })
  if (res.status === 401) {
    onUnauthorized?.()
    throw new ApiError(401, '请先登录', {})
  }
  const ct = res.headers.get('content-type') || ''
  const body: any = ct.includes('application/json')
    ? await res.json().catch(() => ({}))
    : await res.text()
  if (!res.ok) {
    throw new ApiError(res.status, (body && body.message) || `请求失败 ${res.status}`, body)
  }
  return body as T
}

export const http = {
  get: <T = unknown>(url: string) => request<T>(url),
  post: <T = unknown>(url: string, body?: unknown) =>
    request<T>(url, { method: 'POST', body: body instanceof FormData ? body : JSON.stringify(body ?? {}) }),
  put: <T = unknown>(url: string, body?: unknown) =>
    request<T>(url, { method: 'PUT', body: body instanceof FormData ? body : JSON.stringify(body ?? {}) }),
  del: <T = unknown>(url: string, body?: unknown) =>
    request<T>(url, { method: 'DELETE', body: body === undefined ? undefined : JSON.stringify(body) })
}
