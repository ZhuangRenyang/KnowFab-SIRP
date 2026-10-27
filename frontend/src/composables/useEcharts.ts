import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, MarkLineComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { onScopeDispose, watch } from 'vue'
import { useThemeStore } from '@/stores/theme'

/* ECharts 按需注册（架构方案 §3：入口体积预算内），主题色全部取自 CSS 变量 Token，
   深浅切换时重建实例（PRD 风险 5：图表取色跟随主题，无硬编码色）。 */
echarts.use([BarChart, LineChart, GridComponent, TooltipComponent, LegendComponent, MarkLineComponent, CanvasRenderer])

const css = (n: string) => getComputedStyle(document.documentElement).getPropertyValue(n).trim()

function buildTheme(): Record<string, any> {
  return {
    color: [
      css('--accent'), css('--run'), css('--idle'), css('--alarm'), css('--stop'),
      css('--chart-tariff-sharp'), css('--chart-tariff-peak'), css('--chart-tariff-flat'),
      css('--chart-tariff-valley'), css('--chart-tariff-unpriced')
    ],
    textStyle: { fontFamily: css('--font-body') },
    categoryAxis: {
      axisLine: { lineStyle: { color: css('--border') } },
      axisTick: { show: false },
      axisLabel: { color: css('--muted'), fontSize: 11 },
      splitLine: { show: false }
    },
    valueAxis: {
      axisLine: { show: false },
      axisLabel: { color: css('--muted'), fontSize: 11 },
      splitLine: { lineStyle: { color: css('--border-soft') } }
    },
    legend: { textStyle: { color: css('--fg-2'), fontSize: 12 } },
    tooltip: {
      backgroundColor: css('--surface-3'),
      borderColor: css('--border'),
      textStyle: { color: css('--fg'), fontSize: 12 }
    }
  }
}

export function useEcharts() {
  const theme = useThemeStore()
  let chart: echarts.ECharts | null = null
  let target: HTMLElement | null = null
  let opt: echarts.EChartsCoreOption | null = null
  let ro: ResizeObserver | null = null

  function init(el: HTMLElement) {
    target = el
    chart = echarts.init(el, buildTheme() as any)
    ro = new ResizeObserver(() => chart?.resize())
    ro.observe(el)
    /* init 前如有缓存的 option（数据先到、DOM 后挂载），挂载后立即重放 */
    if (opt) chart.setOption(opt, true)
  }

  function setOption(o: echarts.EChartsCoreOption) {
    opt = o
    chart?.setOption(o, true)
  }

  /* 主题翻转：销毁重建，保证 canvas 取色与页面一致 */
  watch(() => theme.light, () => {
    if (!target) return
    chart?.dispose()
    chart = echarts.init(target, buildTheme() as any)
    if (opt) chart.setOption(opt, true)
  })

  onScopeDispose(() => {
    ro?.disconnect()
    chart?.dispose()
    chart = null
  })

  return { init, setOption }
}
