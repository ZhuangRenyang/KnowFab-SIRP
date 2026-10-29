<template>
  <!-- 老设备（继电器/行程开关接入）通常只有 count 点：没有数值点位就不渲染这张图，
       别把计数值当"原始值"画出一条无意义的爬升曲线 -->
  <div v-if="hasValuePoint" class="trend">
    <!-- 区块标题（2026-10-29 用户截图反馈"这个表没有名字"）：
         图内那行 11px 灰字几乎看不见，改成图外显式标题，把"画的是哪个点位"写清楚 -->
    <div class="trend-head">
      <b>点位趋势</b>
      <span class="trend-name">{{ pointTitle }}</span>
      <span class="trend-tip">采集质量曲线 · 不是产量统计</span>
    </div>
    <div v-if="noData" class="trend-empty">数据积累中…（选中设备后自动拉取近 180 条趋势）</div>
    <div v-show="!noData" ref="el" class="trend-canvas" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref, watch } from 'vue'
import { useEcharts } from '@/composables/useEcharts'
import { useWsStore } from '@/stores/ws'
import { devicesApi } from '@/api/devices'

/* 详情趋势：默认取第一个 role=value 点（无则第一个点），近 180 条；
   选中期间收到该设备 values 推送且距上次拉取 >2 秒时自动重拉（PRD §1.1，与旧版一致） */
const props = defineProps<{ device: any }>()

const el = ref<HTMLElement>()
const { init, setOption } = useEcharts()
const ws = useWsStore()
const noData = ref(false)

/** 只有存在 role=value 点（转速/温度/电流这类可观测数值）才提供原始值曲线 */
const hasValuePoint = computed(() =>
  (props.device?.points || []).some((p: any) => p.role === 'value'))

const point = computed(() => {
  const pts = props.device?.points || []
  return pts.find((p: any) => p.role === 'value') || null
})

/** 标题里点名：哪个点位、什么单位（例如「转速 (rpm)」） */
const pointTitle = computed(() => {
  const p = point.value
  if (!p) return ''
  return p.name + (p.unit ? ` (${p.unit})` : '')
})

let lastHistAt = 0
const cssv = (n: string) => getComputedStyle(document.documentElement).getPropertyValue(n).trim()

async function load() {
  const d = props.device
  const p = point.value
  if (!d || !p || !hasValuePoint.value) return
  try {
    const j = await devicesApi.history(d.code, p.key ?? p.pointKey ?? p.name)
    lastHistAt = Date.now()
    const hist = j.data || []
    noData.value = hist.length < 2
    const title = p.name + (p.unit ? ` (${p.unit})` : '')
    setOption({
      grid: { left: 44, right: 12, top: 10, bottom: 22 },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: hist.map((x: any, i: number) => fmtT(x.ts) || String(i + 1)) },
      yAxis: { type: 'value', scale: true },
      series: [{ name: title, type: 'line', showSymbol: false, smooth: true,
                 data: hist.map((x: any) => x.v) }]
    })
  } catch { /* 静默，等下次推送重试 */ }
}

function fmtT(t: any): string {
  if (t == null) return ''
  const d = new Date(t)
  if (isNaN(d.getTime())) return String(t)
  const p2 = (n: number) => String(n).padStart(2, '0')
  return `${p2(d.getHours())}:${p2(d.getMinutes())}:${p2(d.getSeconds())}`
}

let off: (() => void) | null = null
function bind() {
  off?.()
  const code = props.device?.code
  if (!code) return
  off = ws.on('values', (map) => {
    if (map[code] && Date.now() - lastHistAt > 2000) load()
  })
}

watch(() => props.device?.code, () => { bind(); load() }, { immediate: true })
onScopeDispose(() => off?.())
onMounted(() => { if (el.value) init(el.value) })
</script>

<style scoped>
.trend { margin-top: var(--space-3); }
/* 区块标题：图表必须有名字，避免和产量统计混淆 */
.trend-head {
  display: flex; align-items: baseline; gap: 8px; margin-bottom: 4px;
}
.trend-head b { font-size: var(--text-xs); font-weight: 500; color: var(--fg-2); }
.trend-name { font-size: var(--text-2xs); color: var(--fg); }
.trend-tip { font-size: var(--text-2xs); color: var(--muted); }
.trend-canvas { width: 100%; height: 150px; }
.trend-empty {
  height: 150px; display: flex; align-items: center; justify-content: center;
  color: var(--muted); font-size: var(--text-sm);
  background: var(--surface-2); border-radius: var(--radius-md);
}
</style>
