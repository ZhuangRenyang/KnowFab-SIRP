import { defineStore } from 'pinia'
import { ref } from 'vue'

type Handler = (payload: any) => void

/* /ws 应用级单例（架构方案 R1）：
   - 登录后 connect，登出/401 时 close，视图只订阅不持有
   - values 消息 250ms 合并节流（PRD 风险 2：500 台 / 293 条每秒不卡的关键）
   - alarm:* 事件 1.5s 节流（与现行为一致）
   - 重连指数步进 1s/2s/4s/8s 封顶（总监裁决 C4：恢复窗口不宽于现状） */
export const useWsStore = defineStore('ws', () => {
  const connected = ref(false)
  const handlers = new Map<string, Set<Handler>>()
  let sock: WebSocket | null = null
  let retry = 0
  let closedByUs = false
  let valueBuf = new Map<string, Map<string, any>>()
  let flushTimer: number | null = null
  let alarmTimer: number | null = null

  function on(type: string, fn: Handler): () => void {
    let set = handlers.get(type)
    if (!set) { set = new Set(); handlers.set(type, set) }
    set.add(fn)
    return () => { set!.delete(fn) }
  }

  function emit(type: string, payload: any) {
    handlers.get(type)?.forEach((f) => {
      try { f(payload) } catch (e) { console.error('[ws] handler error', type, e) }
    })
  }

  function flushValues() {
    flushTimer = null
    if (valueBuf.size === 0) return
    const out: Record<string, Record<string, any>> = {}
    valueBuf.forEach((per, code) => {
      out[code] = {}
      per.forEach((it, k) => { out[code][k] = it })
    })
    valueBuf = new Map()
    emit('values', out)
  }

  function scheduleReconnect() {
    const delay = Math.min(1000 * 2 ** retry, 8000)
    retry++
    window.setTimeout(() => { if (!closedByUs) connect() }, delay)
  }

  function connect() {
    if (sock && (sock.readyState === WebSocket.OPEN || sock.readyState === WebSocket.CONNECTING)) return
    closedByUs = false
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const s = new WebSocket(`${proto}://${location.host}/ws`)
    sock = s
    s.onopen = () => { retry = 0; connected.value = true }
    s.onmessage = (e) => {
      let msg: any
      try { msg = JSON.parse(e.data) } catch { return }
      if (msg.type === 'values') {
        const per = valueBuf.get(msg.deviceCode) ?? new Map()
        for (const it of msg.items || []) per.set(it.pointKey, it)
        valueBuf.set(msg.deviceCode, per)
        if (flushTimer == null) flushTimer = window.setTimeout(flushValues, 250)
        // 今日产量随点位变化一起推过来，卡片数字才能秒级跟涨
        if (msg.todayOutput != null) {
          emit('prod', { deviceCode: msg.deviceCode, todayOutput: msg.todayOutput })
        }
      } else if (msg.type === 'status') {
        emit('status', msg.data)
      } else if (msg.type === 'alarm:new' || msg.type === 'alarm:clear' || msg.type === 'alarm:burst') {
        if (alarmTimer == null) {
          alarmTimer = window.setTimeout(() => { alarmTimer = null; emit('alarms:dirty', msg.type) }, 1500)
        }
      }
    }
    s.onclose = () => { connected.value = false; if (!closedByUs) scheduleReconnect() }
    s.onerror = () => { try { s.close() } catch { /* noop */ } }
  }

  function close() {
    closedByUs = true
    try { sock?.close() } catch { /* noop */ }
    sock = null
    connected.value = false
  }

  return { connected, on, connect, close }
})
