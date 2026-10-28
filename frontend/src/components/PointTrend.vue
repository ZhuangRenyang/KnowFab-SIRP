<template>
  <div class="trend">
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

const point = computed(() => {
  const pts = props.device?.points || []
  return pts.find((p: any) => p.role === 'value') || pts[0] || null
})

let lastHistAt = 0
const cssv = (n: string) => getComputedStyle(document.documentElement).getPropertyValue(n).trim()

async function load() {
  const d = props.device
  const p = point.value
  if (!d || !p) return
  try {
    const j = await devicesApi.history(d.code, p.key ?? p.pointKey ?? p.name)
    lastHistAt = Date.now()
    const hist = j.data || []
    noData.value = hist.length < 2
    const title = p.name + (p.unit ? ` (${p.unit})` : '')
    setOption({
      /* 两个标题：左边说明这是"点位原始值"，右边提示产量看下方统计——
         这张图曾被当成产量曲线（后端字段是 ts，前端读 t 导致横轴全是序号，更像了） */
      title: [
        { text: `点位原始值 · ${title}`, left: 4, top: 2,
          textStyle: { fontSize: 11, color: cssv('--muted'), fontWeight: 400 } },
        { text: '检查采集质量用 · 产量统计见下方', right: 8, top: 3,
          textStyle: { fontSize: 10, color: cssv('--meta'), fontWeight: 400 } }
      ],
      grid: { left: 44, right: 12, top: 26, bottom: 22 },
      xAxis: { type: 'category', data: hist.map((x: any, i: number) => fmtT(x.ts) || String(i + 1)) },
      yAxis: { type: 'value', scale: true },
      series: [{ type: 'line', showSymbol: false, smooth: true, data: hist.map((x: any) => x.v) }]
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
.trend-canvas { width: 100%; height: 150px; }
.trend-empty {
  height: 150px; display: flex; align-items: center; justify-content: center;
  color: var(--muted); font-size: var(--text-sm);
  background: var(--surface-2); border-radius: var(--radius-md);
}
</style>
