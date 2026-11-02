<template>
  <div class="trend">
    <div class="head">
      <div class="hd-left">
        <span class="hd-title">{{ title }}</span>
        <span class="hd-sub">{{ subtitle }}</span>
      </div>
      <div class="hd-right">
        <el-radio-group v-if="showToggle" v-model="mode" size="small">
          <el-radio-button value="oee">{{ t('oee.trendModeOee') }}</el-radio-button>
          <el-radio-button value="factors">{{ t('oee.trendModeFactors') }}</el-radio-button>
        </el-radio-group>
      </div>
    </div>
    <div class="chart-wrap">
      <div ref="chartEl" class="chart"></div>
      <!-- 一个点都画不出来的时候，光标着一张空网格，客户只会以为系统坏了。
           把「缺什么」写在图上 -->
      <div v-if="!hasAny" class="empty-overlay">
        <div class="eo-title">{{ t('oee.trendNoDataAll') }}</div>
        <div v-for="(n, i) in reasons" :key="i" class="eo-note">{{ n }}</div>
      </div>
    </div>
    <div class="foot">
      <span class="legend"><i class="ln ma"></i>{{ t('oee.trendMa') }}</span>
      <span class="legend"><i class="ln raw"></i>{{ t('oee.trendRaw') }}</span>
      <span class="legend"><i class="sw band"></i>{{ t('oee.trendLowCoverage') }}</span>
      <span class="legend note">{{ t('oee.trendBreakNote') }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useEcharts } from '@/composables/useEcharts'
import { movingAverage, pick, lowCoverageAreas, type OeePoint } from '@/utils/oeeTrend'

const { t } = useI18n()

const props = withDefaults(defineProps<{
  dates: string[]
  points: (OeePoint | null)[]
  title?: string
  subtitle?: string
  showToggle?: boolean
  /** 移动平均窗口（天） */
  window?: number
}>(), { title: '', subtitle: '', showToggle: false, window: 7 })

const mode = ref<'oee' | 'factors'>('oee')
const chartEl = ref<HTMLElement>()
const { init, setOption, dispose } = useEcharts()

const css = (n: string) => getComputedStyle(document.documentElement).getPropertyValue(n).trim()

/** 数据完整度低于这个百分比，那天的数就别当真（跟后端的口径一致） */
const LOW_COVERAGE = 90

const oeeLine = computed(() => pick(props.points, 'oee'))
const maLine = computed(() => movingAverage(oeeLine.value, props.window))

/** 这个模式下有没有能画出来的点 */
function hasDataFor(keys: Array<keyof OeePoint>): boolean {
  return props.points.some((p) => p && keys.some((k) => typeof p[k] === 'number'))
}

const hasOee = computed(() => hasDataFor(['oee']))
const hasFactors = computed(() => hasDataFor(['availability', 'performance', 'quality']))

/**
 * 实际画的模式。
 * <p>
 * 全厂 OEE 常常算不出来（缺不良数数据、少一台设备配节拍），
 * 那就退一步把能算的那几项画出来——「可用率」本身是能算的，
 * 比给一张空图有用。空图只在该真的一点都画不出来时才出现。
 */
const effectiveMode = computed(() => {
  if (mode.value === 'oee' && !hasOee.value && hasFactors.value) {
    return 'factors'
  }
  return mode.value
})

/** 一个点都画不出来？（该真的一点数据都没有） */
const hasAny = computed(() =>
  effectiveMode.value === 'oee' ? hasOee.value : hasFactors.value)

/** 缺什么导致的——把后端给的人话说明摆出来 */
const reasons = computed(() => {
  const set = new Set<string>()
  props.points.forEach((p) => (p?.notes || []).forEach((n) => set.add(n)))
  return [...set].slice(0, 3)
})

const series = computed(() => {
  const acc = css('--accent')
  const muted = css('--muted')
  const list: any[] = []
  if (effectiveMode.value === 'oee') {
    list.push({
      name: t('oee.trendRaw'), type: 'line', smooth: false, connectNulls: false,
      symbol: 'circle', symbolSize: 3, z: 2,
      lineStyle: { width: 1, color: muted, opacity: .7 },
      itemStyle: { color: muted },
      /* 数据没采全的点画大一点、换个颜色：
         今天是常态（一天还没过完），孤立的单天标不出色带，只能靠符号提示 */
      data: oeeLine.value.map((v, i) => {
        const p = props.points[i]
        const low = !p || (p.coverage ?? 0) < LOW_COVERAGE
        return {
          value: v,
          symbolSize: low ? 9 : 3,
          itemStyle: low ? { color: css('--idle'), borderColor: css('--border'), borderWidth: 1 } : { color: muted }
        }
      })
    })
    list.push({
      name: t('oee.trendMa'), type: 'line', smooth: true, connectNulls: false,
      symbol: 'none', z: 3,
      lineStyle: { width: 2.5, color: acc },
      itemStyle: { color: acc },
      data: maLine.value,
      /* 数据完整度低的日期标一块底色，提醒「这天的数别当真」 */
      markArea: {
        silent: true,
        itemStyle: { color: css('--surface-2'), opacity: .85 },
        data: lowCoverageAreas(props.dates, props.points).map(([a, b]) => [
          { xAxis: a }, { xAxis: b }
        ])
      }
    })
  } else {
    const lines: Array<[string, keyof OeePoint, string]> = [
      [t('oee.availability'), 'availability', css('--run')],
      [t('oee.performance'), 'performance', css('--idle')],
      [t('oee.quality'), 'quality', css('--chart-tariff-flat')]
    ]
    lines.forEach(([name, key, color]) => {
      list.push({
        name, type: 'line', smooth: true, connectNulls: false, symbol: 'none', z: 2,
        lineStyle: { width: 1.8, color },
        itemStyle: { color },
        data: pick(props.points, key)
      })
    })
    list.push({
      name: t('oee.trendMa'), type: 'line', smooth: true, connectNulls: false,
      symbol: 'none', z: 3,
      lineStyle: { width: 2.5, color: acc, type: 'dashed' },
      itemStyle: { color: acc },
      data: maLine.value
    })
  }
  return list
})

function draw() {
  if (!chartEl.value || !props.dates.length) return
  setOption({
    grid: { left: 6, right: 12, top: 26, bottom: 4, containLabel: true },
    legend: { show: effectiveMode.value === 'factors', top: 0, itemWidth: 14, itemHeight: 8 },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line' },
      formatter: (params: any[]) => {
        if (!params?.length) return ''
        const i = params[0].dataIndex
        const p = props.points[i]
        const date = props.dates[i]
        if (!p) {
          return `${date}<br/>${t('oee.trendNoData')}`
        }
        const row = (k: string, v: number | null | undefined) =>
          `${k}: ${v === null || v === undefined ? '--' : v.toFixed(1)}%`
        const ma = maLine.value[i]
        return [
          `<b>${date}</b>`,
          row(t('oee.trendMa'), ma),
          row(t('oee.availability'), p.availability),
          row(t('oee.performance'), p.performance),
          row(t('oee.quality'), p.quality),
          `${t('oee.trendCoverage')}: ${(p.coverage ?? 0).toFixed(0)}%`
        ].join('<br/>')
      }
    },
    xAxis: {
      type: 'category',
      data: props.dates.map((d) => d.slice(5)),
      axisLabel: { interval: (i: number) => Math.max(0, Math.ceil(props.dates.length / 12) - 1) }
    },
    yAxis: { type: 'value', min: 0, max: 100, axisLabel: { formatter: '{value}%' } },
    series: series.value
  })
}

onMounted(() => { if (chartEl.value) init(chartEl.value); draw() })
watch(() => [props.dates, props.points, effectiveMode.value], draw, { deep: true })
onScopeDispose(() => dispose())
</script>

<style scoped>
.trend { display: flex; flex-direction: column; gap: var(--space-2); }
.head { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-2); }
.hd-title { font-size: var(--text-sm); font-weight: 600; }
.hd-sub { font-size: var(--text-2xs); color: var(--meta); }
.chart-wrap { position: relative; }
.chart { height: 240px; }
.empty-overlay {
  position: absolute; inset: 0; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 6px; text-align: center;
  background: var(--surface); padding: var(--space-4);
}
.eo-title { font-size: var(--text-sm); color: var(--muted); }
.eo-note { font-size: var(--text-2xs); color: var(--meta); line-height: 1.7; max-width: 520px; }
.foot { display: flex; align-items: center; gap: var(--space-4); flex-wrap: wrap; }
.legend { display: inline-flex; align-items: center; gap: 6px; font-size: var(--text-2xs); color: var(--muted); }
.legend.note { color: var(--meta); }
.ln { width: 16px; height: 0; border-top-width: 2px; border-top-style: solid; }
.ln.ma { border-color: var(--accent); border-top-width: 2.5px; }
.ln.raw { border-color: var(--muted); border-top-width: 1px; }
.sw { width: 12px; height: 10px; border-radius: 2px; }
.sw.band { background: var(--surface-2); border: 1px solid var(--border-soft); }
</style>
