import { onMounted, onScopeDispose } from 'vue'

/* 唯一合法的轮询入口（架构方案 R2 硬约束）：
   - 视图内禁止裸 setInterval，一律走本 composable
   - onScopeDispose 自动清理，路由切换零残留
   - document.hidden 时跳过执行，恢复可见立即补一次（与旧"页面可见时才跑"语义一致） */
export function usePolling(fn: () => any, ms: number, opts: { immediate?: boolean } = {}) {
  const immediate = opts.immediate ?? true
  let timer: number | null = null

  const run = () => { if (!document.hidden) fn() }
  const start = () => {
    if (timer != null) return
    if (immediate) fn()
    timer = window.setInterval(run, ms)
  }
  const stop = () => {
    if (timer != null) { window.clearInterval(timer); timer = null }
  }
  const onVis = () => { if (!document.hidden) fn() }

  onMounted(() => {
    start()
    document.addEventListener('visibilitychange', onVis)
  })
  onScopeDispose(() => {
    stop()
    document.removeEventListener('visibilitychange', onVis)
  })

  return { start, stop }
}
