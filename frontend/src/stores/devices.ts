import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { http } from '@/api/http'
import { useWsStore } from './ws'

/* 设备口径（与旧版 statusOf/pickKpis/fmt 逐字对齐，PRD §1.1） */

export function statusOf(d: any): { v: number; label: string; labelKey: string; type: 'run' | 'idle' | 'stop' | 'alarm' | 'off' } {
  if (!d.online) return { v: -1, label: '离线', labelKey: 'status.offline', type: 'off' }
  const s = (d.points || []).find((p: any) => p.role === 'status' && p.value != null)
  if (!s) return { v: 2, label: '在线', labelKey: 'status.online', type: 'run' }
  const n = Number(s.value)
  const map: Record<number, { label: string; labelKey: string; type: 'run' | 'idle' | 'stop' | 'alarm' }> = {
    0: { label: '停机', labelKey: 'status.stopped', type: 'stop' },
    1: { label: '待机', labelKey: 'status.idle', type: 'idle' },
    2: { label: '运行', labelKey: 'status.running', type: 'run' },
    3: { label: '报警', labelKey: 'status.alarm', type: 'alarm' }
  }
  const info = map[n] || { label: '未知', labelKey: 'status.unknown', type: 'stop' as const }
  return { v: n, label: info.label, labelKey: info.labelKey, type: info.type }
}

export function pickKpis(d: any) {
  const pts = d.points || []
  const counts = pts.filter((p: any) => p.role === 'count')
  const vals = pts.filter((p: any) => p.role === 'value' || p.role === 'alarm')
  /* 第一个 count 点显示「今日产量」（后端增量统计口径），不展示寄存器累计值：
     老工厂寄存器里是几十万的累计读数，直接当产量展示会误导。
     其余 count 点保留寄存器值（辅助对照用）。 */
  const mapped = counts.map((p: any, i: number) =>
    i === 0 ? { ...p, name: '今日产量', labelKey: 'device.todayOutput', value: Number(d.todayOutput ?? 0) } : p
  )
  return [...mapped, ...vals].slice(0, 4)
}

export function fmt(v: any): string | number {
  if (v === null || v === undefined) return '--'
  if (typeof v === 'number') return Number.isInteger(v) ? v : Math.round(v * 100) / 100
  return v
}

export const useDevicesStore = defineStore('devices', () => {
  const list = ref<any[]>([])
  const loaded = ref(false)
  const loading = ref(false)
  const lastError = ref('')

  const stats = computed(() => {
    let online = 0, running = 0, alarm = 0
    for (const d of list.value) {
      const v = statusOf(d).v
      if (d.online) online++
      if (v === 2) running++
      if (v === 3) alarm++
    }
    return { total: list.value.length, online, running, alarm }
  })

  async function load(silent = false) {
    if (!silent) loading.value = true
    lastError.value = ''
    try {
      list.value = await http.get<any[]>('/api/devices')
      loaded.value = true
    } catch (e: any) {
      if (!silent) lastError.value = e?.message || '加载失败'
    } finally {
      if (!silent) loading.value = false
    }
  }

  /* 兜底轮询：WS 推送负责秒级，这一道保证即使 WS 断了或推送里没带字段，
     卡片上的「今日产量」也会自己往前走（静默刷新，不转圈不闪屏） */
  let autoTimer: number | null = null
  function startAutoRefresh() {
    stopAutoRefresh()
    autoTimer = window.setInterval(() => {
      if (document.visibilityState === 'visible') load(true)
    }, 5000)
  }
  function stopAutoRefresh() {
    if (autoTimer !== null) {
      window.clearInterval(autoTimer)
      autoTimer = null
    }
  }

  async function reloadAll() {
    await http.post('/api/devices/reload')
    await load()
  }

  /* WS 合并帧应用：只改内存对象，Vue 响应式做最小化 DOM 更新 */
  function applyValues(map: Record<string, Record<string, any>>) {
    for (const d of list.value) {
      const per = map[d.code]
      if (!per) continue
      for (const p of d.points || []) {
        const it = per[p.pointKey ?? p.key]
        if (it) Object.assign(p, it)
      }
      d.online = true
    }
  }

  function applyStatus(s: { deviceCode: string; online: boolean; error?: string; lastOkTs?: number }) {
    const d = list.value.find((x) => x.code === s.deviceCode)
    if (!d) return
    d.online = s.online
    d.lastError = s.error
    d.lastOkTs = s.lastOkTs
  }

  function applyProd(p: { deviceCode: string; todayOutput: number }) {
    const d = list.value.find((x) => x.code === p.deviceCode)
    if (!d) return
    d.todayOutput = p.todayOutput
  }

  function bindWs() {
    const ws = useWsStore()
    ws.on('values', applyValues)
    ws.on('status', applyStatus)
    ws.on('prod', applyProd)
  }

  return { list, loaded, loading, lastError, stats, load, reloadAll, bindWs, startAutoRefresh, stopAutoRefresh }
})
