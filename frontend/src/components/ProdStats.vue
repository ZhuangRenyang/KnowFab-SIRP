<template>
  <div class="pstats">
    <div class="ps-head">
      <span class="ps-title">产量统计</span>
      <span v-if="st && st.skuName" class="ps-meta">产品：{{ st.skuName }}</span>
      <span v-if="st && st.auto" class="ps-meta">
        自动入库{{ st.warehouseName ? ' → ' + st.warehouseName : '' }} · 每日 {{ st.resetHour }} 点清零
      </span>
      <span class="ps-spacer" />
      <el-radio-group v-if="st && (st.hasCount || st.auto)" v-model="mode" size="small">
        <el-radio-button value="day">日表</el-radio-button>
        <el-radio-button value="month">月表</el-radio-button>
        <el-radio-button value="year">年表</el-radio-button>
        <el-radio-button value="all">综合</el-radio-button>
      </el-radio-group>
    </div>

    <template v-if="st && (st.hasCount || st.auto)">
      <div class="ps-kpis num">
        <div class="ps-kpi">今日<b>{{ st.today }}</b></div>
        <div class="ps-kpi">本月<b>{{ st.month }}</b></div>
        <div class="ps-kpi">今年<b>{{ st.year }}</b></div>
      </div>
      <div ref="el" class="ps-chart" :class="{ tall: mode === 'all' }" />
      <div class="ps-note">
        <template v-if="mode === 'day'">
          从今日 {{ st.resetHour }} 点（清零时刻）起算的 24 小时，还没到的小时为 0；各小时之和 = 今日
        </template>
        <template v-else-if="mode === 'month'">
          本月 1 日到今天，按每日 {{ st.resetHour }} 点清零切日；各点之和 = 本月
        </template>
        <template v-else-if="mode === 'year'">
          今年 1-12 月合计；各月之和 = 今年
        </template>
        <template v-else>
          近一年每日产量：滚轮或拖动下方滑块缩放，年 → 月 → 日；虚线是 7 日均值
        </template>
      </div>
    </template>
    <div v-else-if="st" class="ps-empty">
      该设备未绑定成品，或点表里没有 role=count 的产量点——在「编辑配置 → 生产入库联动」里启用后，
      每产一件自动入成品仓，这里出日/月/年产量图表。
    </div>
    <div v-else class="ps-empty">加载中…</div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { useEcharts } from '@/composables/useEcharts'
import { prodApi, type ProdStatus } from '@/api/erp'

/* 机器级产量统计四张表（用户口径）：
   日表=当日 24 小时（从清零时刻起算） / 月表=当月每天 / 年表=今年 12 个月 / 综合=近一年可缩放曲线。
   数据源 prod_hourly.output（增量已处理计数器清零与断线补记）。
   四张表与「今日/本月/今年」KPI 同一口径：日表各点之和=今日，月表=本月，年表=今年。 */
const props = defineProps<{ code: string }>()

type Mode = 'day' | 'month' | 'year' | 'all'
const mode = ref<Mode>('day')
const el = ref<HTMLElement>()
const { init, setOption } = useEcharts()
const st = ref<ProdStatus | null>(null)
/** 图表 DOM 是 v-if 出来的：st 到位后才能 init，只初始化一次（修复旧版首次打开不渲染） */
let inited = false

const num = (v: number) => Number(v).toLocaleString('zh-CN')

/** 纵轴刻度按 500 / 1000 这类整单位取：在 1/2/5×10ⁿ 里挑「≤5 格就能盖住最大值」的最小步长 */
function niceStep(max: number): number {
  if (max <= 0) return 1
  const ladder: number[] = []
  for (let e = 0; e <= 7; e++) {
    for (const m of [1, 2, 5]) ladder.push(m * 10 ** e)
  }
  for (const s of ladder) {
    if (Math.ceil(max / s) <= 5) return s
  }
  return 100000000
}

async function load() {
  try {
    st.value = await prodApi.status(props.code)
    if (st.value.hasCount || st.value.auto) {
      await nextTick()                       // 等 v-if 把图表 DOM 渲染出来
      if (!inited && el.value) {
        init(el.value)
        inited = true
      }
      await loadChart()
    }
  } catch { /* 详情页静默，下一轮刷新重试 */ }
}

async function loadChart() {
  try {
    const r = await prodApi.chart(props.code, mode.value)
    if (r.ok) render(r.points || [])
  } catch { /* 静默 */ }
}

function render(points: { label: string; qty: number }[]) {
  const labels = points.map((p) => p.label)
  const data = points.map((p) => Number(p.qty) || 0)
  const maxV = Math.max(...data, 0)
  const step = niceStep(maxV)
  const yAxis = {
    type: 'value' as const,
    max: Math.max(step, Math.ceil(maxV / step) * step),
    interval: step,
    axisLabel: { formatter: (v: number) => num(v) }
  }
  const tooltip = {
    trigger: 'axis' as const,
    valueFormatter: (v: unknown) => num(Number(v))
  }

  if (mode.value === 'day') {
    setOption({
      grid: { left: 56, right: 14, top: 14, bottom: 24 },
      tooltip,
      xAxis: { type: 'category', data: labels },
      yAxis,
      series: [{ name: '小时产量', type: 'bar', barMaxWidth: 14, data }]
    })
    return
  }
  if (mode.value === 'month' || mode.value === 'year') {
    setOption({
      grid: { left: 56, right: 14, top: 14, bottom: 24 },
      tooltip,
      xAxis: { type: 'category', data: labels },
      yAxis,
      series: [{
        name: mode.value === 'month' ? '日产量' : '月产量',
        type: 'line', smooth: true, showSymbol: true, symbolSize: 4, data
      }]
    })
    return
  }
  // 综合：近一年每日产量 + 7 日均线，滚轮 / 滑块缩放 年 → 月 → 日
  const ma = data.map((_, i) => {
    const s = Math.max(0, i - 6)
    const seg = data.slice(s, i + 1)
    return Number((seg.reduce((a, b) => a + b, 0) / seg.length).toFixed(2))
  })
  setOption({
    grid: { left: 56, right: 14, top: 26, bottom: 46 },
    tooltip,
    legend: { top: 0, right: 0, itemWidth: 10, itemHeight: 6 },
    xAxis: { type: 'category', data: labels },
    yAxis,
    dataZoom: [
      { type: 'inside', start: 0, end: 100 },
      { type: 'slider', height: 16, bottom: 6 }
    ],
    series: [
      { name: '日产量', type: 'line', smooth: true, showSymbol: false, data,
        areaStyle: { opacity: 0.08 } },
      { name: '7日均值', type: 'line', smooth: true, showSymbol: false, data: ma,
        lineStyle: { width: 1, type: 'dashed' } }
    ]
  })
}

watch(mode, loadChart)
watch(() => props.code, load)
onMounted(load)
</script>

<style scoped>
.pstats { margin-top: var(--space-3); }
.ps-head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.ps-spacer { flex: 1; }
.ps-title { font-size: var(--text-sm); font-weight: 500; }
.ps-meta { font-size: var(--text-xs); color: var(--muted); }
.ps-kpis { display: flex; gap: var(--space-3); margin: 8px 0; flex-wrap: wrap; }
.ps-kpi {
  background: var(--surface-2); border-radius: var(--radius-md); padding: 6px 14px;
  font-size: var(--text-xs); color: var(--muted);
  display: flex; align-items: baseline; gap: 6px;
}
.ps-kpi b { font-size: var(--text-lg); font-weight: 500; color: var(--fg); }
.ps-chart { width: 100%; height: 170px; }
.ps-chart.tall { height: 210px; }
.ps-note { font-size: var(--text-2xs); color: var(--muted); padding-top: 2px; }
.ps-empty {
  background: var(--surface-2); border-radius: var(--radius-md);
  padding: 14px; font-size: var(--text-xs); color: var(--muted);
}
</style>
