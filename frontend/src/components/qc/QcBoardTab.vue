<template>
  <div>
    <!-- 五张概览卡：合格率 / 不良率 / 待处置 / 让步率 / 报废损失 -->
    <div class="kpi-row kpi-5">
      <div class="kpi" :class="kpiTone('passRate')">
        <div class="kpi-label">{{ t('qc.board.kpiPass') }}</div>
        <div class="kpi-value num">{{ pct(data?.passRate) }}</div>
      </div>
      <div class="kpi" :class="kpiTone('defectRate')">
        <div class="kpi-label">{{ t('qc.board.kpiDefect') }}</div>
        <div class="kpi-value num">{{ pct(data?.defectRate) }}</div>
      </div>
      <div class="kpi" :class="kpiTone('pendingQty')">
        <div class="kpi-label">{{ t('qc.board.kpiPending') }}</div>
        <div class="kpi-value num">{{ qty(data?.pendingQty ?? 0, 0) }}</div>
        <div class="kpi-sub">{{ t('qc.board.kpiPendingSub', { n: data?.pendingRecords ?? 0 }) }}</div>
      </div>
      <div class="kpi" :class="kpiTone('concessionRate')">
        <div class="kpi-label">{{ t('qc.board.kpiConcession') }}</div>
        <div class="kpi-value num">{{ pct(data?.concessionRate) }}</div>
      </div>
      <div class="kpi" :class="kpiTone('scrapLoss')">
        <div class="kpi-label">{{ t('qc.board.kpiScrap') }}</div>
        <div class="kpi-value num">{{ money(data?.scrapLoss) }}</div>
      </div>
    </div>

    <!-- 口径说明由前端 i18n 写：后端只给数与结构，不给面向用户的文案
         （后端塞中文则英文界面蹦中文，塞英文则中文界面蹦英文） -->
    <p class="hint caliber">{{ t('qc.board.caliberNote') }}</p>

    <el-card shadow="never" class="card">
      <div class="tab-toolbar">
        <el-date-picker
          v-model="range"
          type="daterange"
          value-format="YYYY-MM-DD"
          :range-separator="t('qc.insp.to')"
          :start-placeholder="t('qc.insp.from')"
          :end-placeholder="t('qc.insp.to')"
          class="w240"
          @change="load"
        />
        <el-radio-group v-model="quick" size="small" @change="onQuick">
          <el-radio-button value="month">{{ t('cost.thisMonth') }}</el-radio-button>
          <el-radio-button value="last">{{ t('cost.lastMonth') }}</el-radio-button>
          <el-radio-button value="90">{{ t('cost.last90') }}</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('base.refresh') }}</el-button>
        <div class="grow" />
        <el-radio-group v-model="paretoBy" size="small">
          <el-radio-button value="code">{{ t('qc.board.byCode') }}</el-radio-button>
          <el-radio-button value="device">{{ t('qc.board.byDevice') }}</el-radio-button>
        </el-radio-group>
      </div>

      <el-alert
        v-if="errMsg"
        type="error"
        :closable="false"
        show-icon
        class="mb12"
        :title="t('qc.board.loadFail')"
        :description="errMsg"
      >
        <template #default>
          <el-button size="small" :icon="Refresh" @click="load">{{ t('qc.board.retry') }}</el-button>
        </template>
      </el-alert>

      <div class="chart-grid">
        <div class="chart-box">
          <div class="chart-title">{{ t('qc.board.paretoTitle') }}</div>
          <div ref="paretoEl" class="chart" />
          <p v-if="!paretoRows.length" class="chart-empty">{{ t('qc.board.paretoEmpty') }}</p>
        </div>
        <div class="chart-box">
          <div class="chart-title">{{ t('qc.board.trendTitle') }}</div>
          <div ref="trendEl" class="chart" />
          <p v-if="!daily.length" class="chart-empty">{{ t('qc.board.trendEmpty') }}</p>
        </div>
      </div>

      <!-- 缺陷等级构成：不用图，直接列出来更快 -->
      <QcSeverityMix :mix="severityMix" />

      <!-- 未分类：单独拎出来，否则它会淹在帕累托里看不见 -->
      <el-alert
        v-if="(data?.untriagedRecords ?? 0) > 0"
        type="warning"
        :closable="false"
        show-icon
        class="mt12"
        :title="t('qc.board.untriagedTitle')"
        :description="t('qc.board.untriagedDesc', { n: data?.untriagedRecords ?? 0 })"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Refresh } from '@element-plus/icons-vue'
import { qcReportApi, type QcDashboard, type QcParetoRow } from '@/api/qc'
import { useEcharts } from '@/composables/useEcharts'
import { money, qty } from '@/utils/format'
import QcSeverityMix from './QcSeverityMix.vue'

const { t } = useI18n()

const data = ref<QcDashboard | null>(null)
const paretoRows = ref<QcParetoRow[]>([])
const loading = ref(false)
const errMsg = ref('')
const range = ref<[string, string] | null>(null)
const quick = ref<'month' | 'last' | '90'>('month')
const paretoBy = ref<'code' | 'device'>('code')

const paretoEl = ref<HTMLElement | null>(null)
const trendEl = ref<HTMLElement | null>(null)
const paretoChart = useEcharts()
const trendChart = useEcharts()
/** 图表只初始化一次。重复 init 会泄漏 canvas 实例 */
const paretoReady = ref(false)
const trendReady = ref(false)

const daily = computed(() => data.value?.daily || [])
const severityMix = computed(() => data.value?.severityMix || [])

const pct = (v: number | null | undefined) =>
  v == null ? '--' : `${Number(v).toFixed(1)}%`

/**
 * 卡片的语义色。
 * 比率类指标**不用** .up / .down（那是"涨跌"语义）——
 * 合格率套 .up 会变成红色，看着像出了问题。用 .kpi.warn / .kpi.danger。
 */
function kpiTone(key: keyof QcDashboard): string {
  const d = data.value
  if (!d) return ''
  if (key === 'passRate') return d.passRate != null && d.passRate < 95 ? 'warn' : ''
  if (key === 'defectRate') return d.defectRate != null && d.defectRate > 5 ? 'danger' : ''
  if (key === 'pendingQty') return d.pendingQty > 0 ? 'warn' : ''
  if (key === 'concessionRate') return d.concessionRate != null && d.concessionRate > 10 ? 'warn' : ''
  if (key === 'scrapLoss') return d.scrapLoss ? 'warn' : ''
  return ''
}

function pad(n: number) {
  return String(n).padStart(2, '0')
}
function fmt(d: Date) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function onQuick() {
  const now = new Date()
  if (quick.value === 'month') {
    range.value = [fmt(new Date(now.getFullYear(), now.getMonth(), 1)), fmt(now)]
  } else if (quick.value === 'last') {
    range.value = [
      fmt(new Date(now.getFullYear(), now.getMonth() - 1, 1)),
      fmt(new Date(now.getFullYear(), now.getMonth(), 0))
    ]
  } else {
    range.value = [fmt(new Date(now.getTime() - 89 * 86400000)), fmt(now)]
  }
  load()
}

async function load() {
  loading.value = true
  errMsg.value = ''
  try {
    const [from, to] = range.value || []
    const [d, p] = await Promise.all([
      qcReportApi.dashboard({ from, to }),
      qcReportApi.pareto({ by: paretoBy.value, from, to })
    ])
    if (!d.ok) {
      errMsg.value = (d as unknown as { message?: string }).message || t('common.reqFail')
      data.value = null
      return
    }
    data.value = d
    paretoRows.value = p.ok ? p.rows || [] : []
    await nextTick()
    drawCharts()
  } catch (e) {
    errMsg.value = (e as Error)?.message || t('common.reqFail')
    data.value = null
  } finally {
    loading.value = false
  }
}

function drawCharts() {
  // 帕累托：柱（数量）+ 线（累计占比）
  if (paretoEl.value) {
    // init 只能调一次（重复 init 会泄漏 canvas），之后只 setOption
    if (!paretoReady.value) {
      paretoChart.init(paretoEl.value)
      paretoReady.value = true
    }
    paretoChart.setOption({
      grid: { left: 44, right: 48, top: 24, bottom: 58 },
      tooltip: { trigger: 'axis' },
      legend: { data: [t('qc.board.paretoQty'), t('qc.board.paretoCum')], bottom: 0 },
      xAxis: {
        type: 'category',
        data: paretoRows.value.map((r) => r.name),
        axisLabel: { rotate: 32, fontSize: 11 }
      },
      yAxis: [
        { type: 'value', name: t('qc.board.paretoQty') },
        { type: 'value', name: '%', max: 100, axisLabel: { formatter: '{value}%' } }
      ],
      series: [
        { name: t('qc.board.paretoQty'), type: 'bar', data: paretoRows.value.map((r) => r.qty), itemStyle: { borderRadius: [3, 3, 0, 0] } },
        { name: t('qc.board.paretoCum'), type: 'line', yAxisIndex: 1, data: paretoRows.value.map((r) => r.cumPct), smooth: false, symbolSize: 5 }
      ]
    })
  }
  // 逐日趋势：检验量 + 不良量
  if (trendEl.value) {
    if (!trendReady.value) {
      trendChart.init(trendEl.value)
      trendReady.value = true
    }
    trendChart.setOption({
      grid: { left: 44, right: 20, top: 24, bottom: 40 },
      tooltip: { trigger: 'axis' },
      legend: { data: [t('qc.board.trendInspected'), t('qc.board.trendDefect')], bottom: 0 },
      xAxis: { type: 'category', data: daily.value.map((d) => d.date), axisLabel: { fontSize: 11 } },
      yAxis: { type: 'value' },
      series: [
        { name: t('qc.board.trendInspected'), type: 'bar', data: daily.value.map((d) => d.inspected), itemStyle: { borderRadius: [3, 3, 0, 0] } },
        { name: t('qc.board.trendDefect'), type: 'line', data: daily.value.map((d) => d.defectQty), symbolSize: 5 }
      ]
    })
  }
}

watch(paretoBy, () => load())

onMounted(() => {
  onQuick()
})
</script>

<style scoped>
.caliber {
  margin-bottom: 12px;
}
.chart-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}
.chart-box {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--surface-2);
  padding: 10px 12px;
}
.chart-title {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--fg);
  margin-bottom: 6px;
}
.chart {
  height: 240px;
  width: 100%;
}
.chart-empty {
  margin: 8px 0 0;
  font-size: var(--text-xs);
  color: var(--muted);
}
.mt12 { margin-top: 12px; }
</style>
