<template>
  <div class="energy">
    <div class="toolbar">
      <span class="lbl">{{ t('energy.rangeLabel') }}</span>
      <el-select v-model="range" style="width:104px" @change="onRange">
        <el-option value="today" :label="t('energy.today')" />
        <el-option value="7d" :label="t('energy.last7d')" />
        <el-option value="month" :label="t('energy.thisMonth')" />
      </el-select>
      <el-button @click="load">{{ t('common.refresh') }}</el-button>
      <span class="spacer"></span>
      <span class="plan">{{ ov?.tariffName || '' }}</span>
      <el-button v-if="canCfg" @click="tariffOpen = true">{{ t('energy.tariff') }}</el-button>
      <el-button v-if="canCfg" @click="demandOpen = true">{{ t('energy.demand') }}</el-button>
      <el-button v-if="canCfg" @click="bmOpen = true">{{ t('energy.bmManage') }}</el-button>
    </div>

    <el-alert v-if="warn" :title="warn" type="warning" show-icon :closable="false" class="warn" />

    <!-- 口径提示：没标总表时全厂电量把总表和机床一起加了，数字会偏高 -->
    <div v-if="ov?.meterMode === 'LEGACY'" class="meter-warn">
      {{ t('energy.meterLegacyWarn') }}
    </div>

    <div class="kpis">
      <div class="ebox"><span>{{ t('energy.kpiTodayKwh') }}</span><b class="num">{{ num(ov?.todayKwh, 1) }}<i>kWh</i></b></div>
      <div class="ebox"><span>{{ t('energy.kpiTodayCost') }}</span><b class="num">{{ num(ov?.todayCost, 2) }}<i>{{ t('energy.currency') }}</i></b></div>
      <div class="ebox"><span>{{ t('energy.kpiMonthCost') }}</span><b class="num">{{ num(ov?.monthCost, 2) }}<i>{{ t('energy.currency') }}</i></b>
        <em>{{ t('energy.kpiMonthHint', { kwh: num(ov?.monthKwh, 1) }) }}</em></div>
      <div class="ebox"><span>{{ t('energy.kpiPower') }}</span><b class="num">{{ num(ov?.power, 2) }}<i>kW</i></b>
        <em>{{ t('energy.kpiPowerHint') }}</em></div>
      <div class="ebox"><span>{{ t('energy.kpiDemand') }}</span>
        <b class="num" :class="{ al: dm?.status?.level === 'OVER', wn: dm?.status?.level === 'WARN' }">
          {{ num(dm?.monthMax?.kw, 0) }}<i>kW</i></b>
        <em>{{ t('energy.kpiDemandHint', { kw: num(dm?.config?.contractDemandKw, 0), at: dm?.monthMax?.atText || '--' }) }}</em></div>
      <div class="ebox"><span>{{ t('energy.kpiBasic') }}</span><b class="num">{{ num(dm?.billing?.cost, 0) }}<i>{{ t('energy.currency') }}</i></b>
        <em>{{ (dm?.billing?.mode === 'CAPACITY' ? t('energy.modeCapacity') : dm?.billing?.mode === 'DEMAND' ? t('energy.modeDemand') : (dm?.billing?.modeText || '--')) + ' · ' + (dm?.billing?.formula || '') }}</em></div>
    </div>

    <div class="panel demand">
      <div class="p-head">
        <h3>{{ demandTitle }}</h3>
        <span>{{ todayFallback ? t('energy.peakRange', { kw: num(dm?.today?.peakKw, 0) }) : t('energy.peakToday', { kw: num(dm?.today?.peakKw, 0) }) }}
          <template v-if="dm?.today?.peakAt && dm.today.peakAt !== '--'"> @ {{ dm.today.peakAt }}</template>
        </span>
        <!-- 进线选择：多路进线分别跟供电局签合同，需量与基本电费各算各的 -->
        <div class="acts">
          <el-select v-if="(dm?.meters || []).length" v-model="meterSel" size="small"
                     style="width:190px" @change="reloadDemand">
            <el-option value="" :label="t('energy.meterAll')" />
            <el-option v-for="m in dm?.meters || []" :key="m.code"
                       :value="m.code" :label="m.name || m.code" />
          </el-select>
        </div>
      </div>
      <!-- 口径提示：没标总表时全厂含所有设备，机床的电与总表重复计算 -->
      <div v-if="dm?.meterMode === 'LEGACY'" class="meter-warn">
        {{ t('energy.meterLegacyWarn') }}
      </div>
      <div ref="demandEl" class="chart"></div>
      <div class="dinfo" v-html="demandInfo"></div>
      <!--
        计算参数条：合同需量/两个单价/变压器容量/计费方式都能在**当前页**直接改，
        改完立刻按真实峰值重算基本电费。不保存就不影响任何人（试算不落库、不写审计）。
        放在这里而不是弹窗里，是因为现场最常问的是"报 400 还是 500""按需量还是按容量划算"。
      -->
      <DemandCalcBar :config="dm?.config || null" :can-edit="canCfg" @saved="load" />
    </div>

    <div class="panels">
      <div class="panel">
        <div class="p-head"><h3>{{ t('energy.segTitle') }}</h3><span>{{ segTotalText }}</span></div>
        <div class="stack">
          <template v-if="shownSegs.length">
            <div v-for="s in shownSegs" :key="s.type" class="stack-seg"
                 :style="{ width: segPct(s), background: SEG_COLOR[s.type] }"
                 :title="`${s.name} ${num(s.kwh, 1)} kWh`"></div>
          </template>
          <div v-else class="stack-empty"></div>
        </div>
        <div class="segs">
          <div v-for="s in ov?.segments || []" :key="s.type" class="segline">
            <div class="sw" :style="{ background: SEG_COLOR[s.type] }"></div>
            <div class="seg-name">{{ s.name }}<span>{{ t('energy.segPrice', { price: num(s.price, 3) }) }}</span></div>
            <div class="num">{{ num(s.kwh, 1) }} kWh</div>
            <div class="num">¥{{ num(s.cost, 2) }}</div>
            <div class="pct">{{ s.ratio > 0 ? num(s.ratio, 0) + '%' : '—' }}</div>
          </div>
        </div>
      </div>
      <div class="panel">
        <div class="p-head"><h3>{{ t('energy.trendTitle') }}</h3><span>{{ t('energy.trendUnit') }}</span></div>
        <div ref="trendEl" class="chart"></div>
      </div>
    </div>

    <!-- 用能单元（DH-11）：全厂 → 车间 → 产线，合计与全厂对账 -->
    <div class="panel">
      <div class="p-head">
        <h3>{{ t('energy.nodeTitle', { range: rangeLabel }) }}</h3>
        <span>
          {{ t('energy.nodeHint') }}
          <b v-if="node && !node.check.matchesTotal" class="bad">
            · {{ t('energy.nodeGap', { kwh: num(node.check.gap, 1) }) }}
          </b>
        </span>
      </div>
      <div v-if="!node || (!node.workshops.length && !node.unassigned.deviceCount)" class="empty">
        {{ t('energy.nodeEmpty') }}
      </div>
      <table v-else class="etable">
        <thead>
          <tr>
            <th>{{ t('energy.nodeCol') }}</th>
            <th class="r">{{ t('energy.colKwh') }}</th>
            <th class="r">{{ t('energy.colCost') }}</th>
            <th style="width:110px">{{ t('energy.colRatio') }}</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="w in node.workshops" :key="w.workshop">
            <tr class="ws-row">
              <td>
                <b>{{ w.workshop }}</b>
                <div class="dev-code">{{ t('energy.nodeLineCount', { lines: w.lineCount, devices: w.deviceCount }) }}</div>
              </td>
              <td class="r num">{{ num(w.kwh, 1) }} kWh</td>
              <td class="r num">¥{{ num(w.cost, 2) }}</td>
              <td><div class="minibar-track"><div class="minibar" :style="{ width: shareW(w.share) }"></div></div></td>
            </tr>
            <tr v-for="l in w.lines" :key="w.workshop + '-' + l.lineId" class="line-row">
              <td class="indent">
                {{ l.lineName }}
                <div class="dev-code">{{ t('energy.nodeDeviceCount', { n: l.deviceCount }) }}</div>
              </td>
              <td class="r num">{{ num(l.kwh, 1) }} kWh</td>
              <td class="r num">¥{{ num(l.cost, 2) }}</td>
              <td><div class="minibar-track"><div class="minibar" :style="{ width: shareW(l.share) }"></div></div></td>
            </tr>
          </template>
          <tr v-if="node.unassigned.deviceCount" class="un-row">
            <td>
              {{ t('energy.nodeUnassigned') }}
              <div class="dev-code">{{ t('energy.nodeDeviceCount', { n: node.unassigned.deviceCount }) }}</div>
            </td>
            <td class="r num">{{ num(node.unassigned.kwh, 1) }} kWh</td>
            <td class="r num">¥{{ num(node.unassigned.cost, 2) }}</td>
            <td><div class="minibar-track"><div class="minibar ghost" :style="{ width: shareW(node.total.kwh > 0 ? node.unassigned.kwh / node.total.kwh * 100 : 0) }"></div></div></td>
          </tr>
          <tr class="total-row">
            <td><b>{{ t('energy.nodeTotal') }}</b></td>
            <td class="r num"><b>{{ num(node.total.kwh, 1) }} kWh</b></td>
            <td class="r num"><b>¥{{ num(node.total.cost, 2) }}</b></td>
            <td></td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 能效对标（DH-12）：实测单位产品能耗 vs 国标限额值 -->
    <div class="panel bm">
      <div class="p-head">
        <h3>{{ t('energy.bmTitle') }}</h3>
        <span>
          {{ t('energy.bmHint', { range: rangeLabel }) }}
          <template v-if="bm && bm.hasBenchmark"> · {{ bmSummary }}</template>
        </span>
      </div>

      <div v-if="!bm || !bm.hasBenchmark" class="empty">{{ t('energy.bmEmpty') }}</div>
      <template v-else>
        <div class="bm-plant">
          <span>{{ t('energy.bmPlant') }}</span>
          <b class="num">{{ bm.plant.unitKwh == null ? '--' : num(bm.plant.unitKwh, 3) }} {{ bm.plant.unit }}</b>
          <em>{{ t('energy.bmPlantSub', { kwh: num(bm.plant.kwh, 1), out: num(bm.plant.output, 0) }) }}</em>
        </div>

        <table class="etable bm-table">
          <thead>
            <tr>
              <th>{{ t('energy.bmColObject') }}</th>
              <th class="r">{{ t('energy.bmColActual') }}</th>
              <th>{{ t('energy.bmColBench') }}</th>
              <th class="r">{{ t('energy.bmColDelta') }}</th>
              <th>{{ t('energy.bmColStatus') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in bm.rows" :key="r.id">
              <td>
                <b>{{ r.name }}</b>
                <div class="dev-code">{{ r.standardNo || '—' }} · {{ r.scopeLabel || t('energy.bmScopePlant') }}</div>
              </td>
              <td class="r num">
                <template v-if="r.unitKwh != null">
                  <b>{{ num(r.unitKwh, 3) }}</b> {{ r.unit }}
                  <div class="dev-code">{{ num(r.kwh, 1) }} kWh ÷ {{ num(r.output, 0) }}</div>
                </template>
                <span v-else class="muted">--</span>
              </td>
              <td>
                <div class="bm-vals">
                  <span v-if="r.advancedValue != null" class="bv adv">{{ t('energy.bmAdvanced') }} {{ r.advancedValue }}</span>
                  <span v-if="r.accessValue != null" class="bv acc">{{ t('energy.bmAccess') }} {{ r.accessValue }}</span>
                  <span v-if="r.limitValue != null" class="bv lim">{{ t('energy.bmLimit') }} {{ r.limitValue }}</span>
                </div>
              </td>
              <td class="r num" :class="bmDeltaClass(r)">{{ bmDelta(r) }}</td>
              <td><span class="bm-badge" :class="BM_CLASS[r.status]">{{ t(BM_KEY[r.status]) }}</span></td>
            </tr>
          </tbody>
        </table>

        <div v-if="bm.warnings.length" class="bm-warn">{{ t('energy.bmWarn', { n: bm.warnings.length }) }}</div>
      </template>
    </div>

    <div class="panel rank">
      <div class="p-head"><h3>{{ t('energy.rankTitle', { range: rangeLabel }) }}</h3><span>{{ t('energy.rankHint') }}</span></div>
      <div v-if="!rank.length" class="empty" v-html="rankEmpty"></div>
      <table v-else class="etable">
        <thead><tr><th>{{ t('energy.colDevice') }}</th><th>{{ t('energy.colLocation') }}</th><th class="r">{{ t('energy.colKwh') }}</th><th class="r">{{ t('energy.colCost') }}</th><th style="width:110px">{{ t('energy.colRatio') }}</th></tr></thead>
        <tbody>
          <tr v-for="x in rank" :key="x.code" class="pick" :class="{ sel: picked === x.code }" @click="pick(x.code)">
            <td>{{ x.name }}
              <div class="dev-code">{{ x.code }}{{ x.online ? '' : t('energy.devOffline') }}{{ x.backfillKwh > 0 ? t('energy.devBackfill') : '' }}</div></td>
            <td class="loc">{{ x.location || '—' }}</td>
            <td class="r num">{{ num(x.kwh, 1) }} kWh</td>
            <td class="r num">¥{{ num(x.cost, 2) }}</td>
            <td><div class="minibar-track"><div class="minibar" :style="{ width: barW(x.kwh) }"></div></div></td>
          </tr>
        </tbody>
      </table>
      <div v-if="picked" class="detail">
        <div class="p-head"><h3>{{ detailTitle }}</h3><span>{{ t('energy.hourly') }}</span></div>
        <div ref="hourlyEl" class="chart-s"></div>
      </div>
    </div>

    <TariffDialog v-model="tariffOpen" @saved="load" />
    <DemandDialog v-model="demandOpen" @saved="load" />
    <BenchmarkDialog v-model="bmOpen" @saved="loadBenchmark" />
  </div>
</template>

<script setup lang="ts">
const { t } = useI18n()
import { computed, ref, watch, reactive} from 'vue'
import { useI18n } from 'vue-i18n'
import { energyApi, SEG_COLOR, type EnergyOverview, type EnergyRankItem, type EnergyByNode, type DemandData, type TariffSegment,
  type BenchmarkReport, type BenchmarkRow, type BenchmarkStatus } from '@/api/energy'
import { num } from '@/api/reports'
import { useAuthStore } from '@/stores/auth'
import { useEcharts } from '@/composables/useEcharts'
import { usePolling } from '@/composables/usePolling'
import TariffDialog from '@/components/energy/TariffDialog.vue'
import DemandDialog from '@/components/energy/DemandDialog.vue'
import DemandCalcBar from '@/components/energy/DemandCalcBar.vue'
import BenchmarkDialog from '@/components/energy/BenchmarkDialog.vue'

const auth = useAuthStore()
const { init: initDemand, setOption: setDemand } = useEcharts()
const { init: initTrend, setOption: setTrend } = useEcharts()
const { init: initHourly, setOption: setHourly } = useEcharts()
/* 图表容器是常驻 DOM，但 init 必须在拿到 el 之后调一次。
   之前三个 init 从未被调用 → setOption 全部静默丢弃，页面上三张图都是空的。
   setOption 会缓存 option 并在 init 时重放，所以"数据先到、init 后到"也没问题。 */
const chartInited = reactive({ demand: false, trend: false, hourly: false })

const range = ref('today')
const ov = ref<EnergyOverview | null>(null)
const rank = ref<EnergyRankItem[]>([])
const node = ref<EnergyByNode | null>(null)
const dm = ref<DemandData | null>(null)
/** 选中的总表（进线）；空 = 全厂 */
const meterSel = ref('')
const picked = ref('')
const detail = ref<any>(null)
const tariffOpen = ref(false), demandOpen = ref(false), bmOpen = ref(false)
const bm = ref<BenchmarkReport | null>(null)
const demandEl = ref<HTMLElement>(), trendEl = ref<HTMLElement>(), hourlyEl = ref<HTMLElement>()

const canCfg = computed(() => ['ADMIN', 'MANAGER'].includes(auth.user?.role || ''))
const warn = computed(() => {
  if (!ov.value) return ''
  if (!ov.value.hasEnergyPoint) {
    return t('energy.warnNoPoint')
  }
  if (ov.value.unpricedKwh > 0) {
    return t('energy.warnUnpriced', { kwh: num(ov.value.unpricedKwh, 1) })
  }
  return ''
})
const shownSegs = computed(() => (ov.value?.segments || []).filter((s) => s.kwh > 0))
const segTotalText = computed(() => {
  const total = (ov.value?.segments || []).reduce((a, s) => a + s.kwh, 0)
  return total > 0 ? t('energy.segTotal', { kwh: num(total, 1) }) : t('energy.noData')
})
const segPct = (s: TariffSegment) => {
  const total = (ov.value?.segments || []).reduce((a, x) => a + x.kwh, 0)
  return (s.kwh / total * 100).toFixed(2) + '%'
}
const rangeLabel = computed(() => ({ today: t('energy.today'), '7d': t('energy.last7d'), month: t('energy.thisMonth') }[range.value] || t('energy.today')))
const rankEmpty = computed(() => ov.value?.hasEnergyPoint
  ? t('energy.emptyNoData')
  : t('energy.emptyNoPoint'))
const barW = (kwh: number) => {
  const max = Math.max(...rank.value.map((x) => x.kwh), 0.001)
  return Math.max(2, kwh / max * 100).toFixed(1) + '%'
}
const demandTitle = computed(() => {
  /* 变量名不能叫 t：会遮蔽 i18n 的 t() */
  const td: any = dm.value?.today || {}
  return td.fallback
    ? t('energy.demandFallback', { date: td.date || '--' })
    : t('energy.demandTitle')
})
/* 今日需量数据缺省时（旧数据无 today 字段）标题退回区间文案——模板 37 行用 */
const todayFallback = computed(() => !!((dm.value?.today || {}) as any).fallback)
const detailTitle = computed(() => detail.value
  ? `${detail.value.name || picked.value} · ${detail.value.date} · ${num(detail.value.kwh, 1)} kWh · ¥${num(detail.value.cost, 2)}` : '')

/* 基本电费核算富文本（旧版 renderDemand 文案逐字平移） */
const demandInfo = computed(() => {
  const d = dm.value
  if (!d) return ''
  const cfg = d.config || {}, st = d.status || {}, bill = d.billing || {}, mm = d.monthMax || {}, cur = d.current || {}
  const gap = (bill.demandCost || 0) - (bill.capacityCost || 0)
  const degreeCost = ov.value?.monthCost || 0
  const color = st.level === 'OVER' ? 'var(--alarm)' : st.level === 'WARN' ? 'var(--idle)' : 'var(--muted)'
  return t('energy.billTitle') + '<br>'
    + t('energy.billByDemand', { kw: num(mm.kw, 0), price: num(cfg.demandPrice, 2), cost: num(bill.demandCost, 2) })
    + t('energy.billByCapacity', { kva: num(cfg.transformerKva, 0), price: num(cfg.capacityPrice, 2), cost: num(bill.capacityCost, 2) })
    + (Math.abs(gap) > 0.5
      ? t('energy.billCheaper', {
          mode: bill.mode === 'CAPACITY' ? t('energy.modeCapacity') : t('energy.modeDemand'),
          gap: num(Math.abs(gap), 0)
        })
      : '')
    + t('energy.billCurrent', {
        kw: num(cur.kw, 0),
        min: Math.round((cur.elapsedSec || 0) / 60),
        head: num(st.headroomKw, 0)
      })
    + t('energy.billTotalHead', { degree: num(degreeCost, 2), basic: num(bill.cost, 2) })
    + `<b>¥${num(degreeCost + (bill.cost || 0), 2)}</b>`
    + t('energy.billTotalTail')
    + `<div style="margin-top:8px;color:${color}">${st.message || ''}</div>`
})

const css = (n: string) => getComputedStyle(document.documentElement).getPropertyValue(n).trim()

/* 需量图：合同红线 + 超合同红/超 90% 黄/正常蓝 */
watch([demandEl, () => dm.value?.today?.series], () => {
  const s = dm.value?.today?.series || []
  const contract = dm.value?.config?.contractDemandKw || 0
  if (!s.length || !demandEl.value) return
  if (!chartInited.demand) { initDemand(demandEl.value); chartInited.demand = true }
  setDemand({
    grid: { left: 6, right: 10, top: 20, bottom: 4, containLabel: true },
    xAxis: { type: 'category', data: s.map((x) => x.t), axisLabel: { interval: (i: number) => i % 12 === 0 } },
    yAxis: { type: 'value' },
    series: [{
      type: 'bar', barMaxWidth: 8, barCategoryGap: 0,
      data: s.map((x) => ({
        value: x.kw || 0,
        itemStyle: {
          color: contract > 0 && x.kw > contract ? css('--alarm')
            : contract > 0 && x.kw > contract * 0.9 ? 'rgba(226,179,76,.8)' : 'rgba(76,141,255,.7)',
          borderRadius: [1, 1, 0, 0]
        }
      })),
      markLine: contract > 0 ? {
        symbol: 'none', silent: true,
        lineStyle: { color: 'rgba(255,122,113,.8)', type: 'dashed', width: 1 },
        label: { formatter: t('energy.contractLine', { kw: Math.round(contract) }), color: css('--alarm'), fontSize: 10 },
        data: [{ yAxis: contract }]
      } : undefined
    }]
  })
})

/* 近 7 日电费：末柱实色 accent，其余淡 accent，柱顶金额 */
watch([trendEl, () => ov.value?.trend], () => {
  const t = ov.value?.trend || []
  if (!t.length || !trendEl.value) return
  if (!chartInited.trend) { initTrend(trendEl.value); chartInited.trend = true }
  setTrend({
    grid: { left: 6, right: 10, top: 20, bottom: 4, containLabel: true },
    xAxis: { type: 'category', data: t.map((x) => String(x.date).slice(5)) },
    yAxis: { type: 'value' },
    series: [{
      type: 'bar', barMaxWidth: 26,
      data: t.map((x, i) => ({
        value: x.cost,
        itemStyle: { color: i === t.length - 1 ? css('--accent') : 'rgba(76,141,255,.42)', borderRadius: [3, 3, 0, 0] }
      })),
      label: {
        show: true, position: 'top', fontSize: 10, color: css('--muted'),
        formatter: (p: any) => (p.value > 0 ? Math.round(p.value) : '')
      }
    }]
  })
})

/* 单设备 24 小时：按峰谷时段配色 */
watch([hourlyEl, () => detail.value?.hourly], () => {
  const h = detail.value?.hourly || []
  if (!h.length || !hourlyEl.value) return
  if (!chartInited.hourly) { initHourly(hourlyEl.value); chartInited.hourly = true }
  setHourly({
    grid: { left: 6, right: 10, top: 14, bottom: 4, containLabel: true },
    xAxis: { type: 'category', data: h.map((x: any) => x.label), axisLabel: { interval: (i: number) => i % 3 === 0 } },
    yAxis: { type: 'value' },
    series: [{
      type: 'bar', barCategoryGap: '15%',
      data: h.map((x: any) => ({
        value: x.kwh,
        itemStyle: { color: x.kwh > 0 ? (SEG_COLOR[x.segType] || css('--chart-tariff-unpriced')) : 'rgba(99,112,116,.35)' }
      }))
    }]
  })
})

async function load() {
  try {
    const [o, , d] = await Promise.all([
      energyApi.overview(),
      loadRank(),
      energyApi.demand(meterSel.value || undefined).catch(() => null)
    ])
    loadNode()
    loadBenchmark()
    ov.value = o
    if (d && d.ok) dm.value = d
    if (picked.value) loadDetail(picked.value)
  } catch (e: any) { console.error('能耗数据加载失败', e) }
}

/** 切换进线（总表）后只重载需量，不必重拉整页 */
async function reloadDemand() {
  try {
    const d = await energyApi.demand(meterSel.value || undefined)
    if (d && d.ok) dm.value = d
  } catch (e: any) { console.error('需量加载失败', e) }
}

async function loadRank() {
  try {
    const rk = await energyApi.rank(range.value)
    rank.value = Array.isArray(rk) ? rk : []
  } catch (e: any) { console.error('设备排行加载失败', e) }
}

async function loadNode() {
  try {
    node.value = await energyApi.byNode(range.value)
  } catch (e: any) { console.error('分项计量加载失败', e) }
}

async function loadBenchmark() {
  try {
    bm.value = await energyApi.benchmarkReport(range.value)
  } catch (e: any) { console.error('能效对标加载失败', e) }
}

/** 范围切换：排行、分项、对标一起刷，别只刷一半让人看到两个口径 */
function onRange() {
  loadRank()
  loadNode()
  loadBenchmark()
}

const BM_CLASS: Record<BenchmarkStatus, string> = {
  ADVANCED: 'bm-adv', ACCESS: 'bm-acc', LIMIT: 'bm-lim', EXCEED: 'bm-exc',
  NO_OUTPUT: 'bm-na', SCOPE_EMPTY: 'bm-na'
}
const BM_KEY: Record<BenchmarkStatus, string> = {
  ADVANCED: 'energy.bmStAdvanced', ACCESS: 'energy.bmStAccess', LIMIT: 'energy.bmStLimit',
  EXCEED: 'energy.bmStExceed', NO_OUTPUT: 'energy.bmNoOutput', SCOPE_EMPTY: 'energy.bmScopeEmpty'
}

const bmSummary = computed(() => {
  const s = (bm.value as BenchmarkReport).summary
  return s.exceed > 0
    ? t('energy.bmSummaryBad', { exceed: s.exceed, total: s.total })
    : t('energy.bmSummaryOk', { total: s.total })
})

function bmDelta(r: BenchmarkRow) {
  const v = r.vsLimit ?? r.vsAccess ?? r.vsAdvanced
  if (v == null) return '--'
  return (v > 0 ? '+' : '') + num(v, 1) + '%'
}
function bmDeltaClass(r: BenchmarkRow) {
  const v = r.vsLimit ?? r.vsAccess ?? r.vsAdvanced
  return v == null ? '' : v > 0 ? 'over' : 'under'
}

/** 占比条宽度：0 也给 1px，否则看不出"有 0.0x%" */
function shareW(share: number) {
  return Math.min(100, Math.max(1, Number(share) || 0)) + '%'
}

async function loadDetail(code: string) {
  try {
    const j = await energyApi.device(code)
    if (!j || !j.hourly) return
    detail.value = j
  } catch (e: any) { console.error('设备能耗明细加载失败', e) }
}

function pick(code: string) {
  picked.value = picked.value === code ? '' : code
  if (picked.value) loadDetail(picked.value)
  else detail.value = null
}

usePolling(load, 15000, { immediate: true })
</script>

<style scoped>
.toolbar { display: flex; gap: var(--space-2); align-items: center; margin-bottom: var(--space-4); flex-wrap: wrap; }
.lbl { font-size: var(--text-xs); color: var(--muted); }
.spacer { flex: 1; }
.plan { font-size: var(--text-xs); color: var(--muted); }
.warn { margin-bottom: var(--space-4); border-radius: var(--radius-md); }
.kpis { display: grid; grid-template-columns: repeat(6, 1fr); gap: var(--space-3); margin-bottom: var(--space-4); }
.ebox { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--elev-raised); padding: var(--space-3); display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.ebox span { font-size: var(--text-2xs); color: var(--muted); letter-spacing: .05em; }
.ebox b { font-size: var(--text-lg); font-weight: 600; }
.ebox b.al { color: var(--alarm); }
.ebox b.wn { color: var(--idle); }
.ebox b i { font-style: normal; font-size: var(--text-2xs); color: var(--muted); margin-left: 3px; }
.ebox em { font-style: normal; font-size: var(--text-2xs); color: var(--meta); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.panel { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--elev-raised); padding: var(--space-4); margin-bottom: var(--space-4); min-width: 0; }
.p-head { display: flex; align-items: baseline; gap: var(--space-2); margin-bottom: var(--space-3); }
.p-head h3 { font-size: var(--text-md); font-weight: 600; }
.p-head span { font-size: var(--text-2xs); color: var(--meta); }
.chart { height: 180px; }
.chart-s { height: 150px; }
.dinfo { margin-top: var(--space-3); font-size: var(--text-xs); color: var(--muted); line-height: 1.8; }
.dinfo :deep(b) { color: var(--fg); }
.panels { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); margin-bottom: var(--space-4); }
.stack { display: flex; height: 16px; border-radius: var(--radius-pill); overflow: hidden; background: var(--surface-2); margin-bottom: var(--space-3); }
.stack-empty { width: 100%; }
.segs { display: flex; flex-direction: column; gap: var(--space-2); }
.segline { display: grid; grid-template-columns: 14px 1fr 90px 80px 48px; gap: var(--space-2); align-items: center; font-size: var(--text-xs); }
.segline .sw { width: 12px; height: 12px; border-radius: 3px; }
.seg-name span { color: var(--muted); }
.segline .num, .segline .pct { text-align: right; }
.segline .pct { color: var(--muted); }
.empty { color: var(--muted); font-size: var(--text-sm); padding: var(--space-4); text-align: center; line-height: 2; }
.etable { width: 100%; border-collapse: collapse; font-size: var(--text-sm); }
.etable th { text-align: left; font-size: var(--text-2xs); color: var(--meta); font-weight: 400; padding: 6px 8px; }
.etable td { padding: 8px; border-bottom: 1px solid var(--border-soft); }
.etable .r { text-align: right; }
.etable tr.pick { cursor: pointer; }
.etable tr.pick:hover td { background: var(--hover, rgba(46, 58, 62, .045)); }
.etable tr.sel td { background: var(--accent-soft, rgba(35, 109, 233, .1)); }
.dev-code { font-size: var(--text-2xs); color: var(--meta); }
.loc { color: var(--muted); }
.minibar-track { height: 5px; background: var(--surface-2); border-radius: 3px; overflow: hidden; }
.minibar { height: 5px; background: var(--accent); border-radius: 3px; }
.detail { margin-top: var(--space-3); border-top: 1px solid var(--border); padding-top: var(--space-3); }
@media (max-width: 1200px) { .kpis { grid-template-columns: repeat(3, 1fr); } .panels { grid-template-columns: 1fr; } }

/* 能效对标 */
.bm-plant { display: flex; align-items: baseline; gap: 10px; padding: 8px 0 12px; color: var(--muted); font-size: 12px; }
.bm-plant b { font-size: var(--text-lg); color: var(--fg); }
.bm-plant em { font-style: normal; }
.bm-table td { vertical-align: top; }
/* 固定布局 + 列宽：auto 布局下 th 的 width 只是建议值，「对象」和「实测」之间会被拉开一大段空白 */
.bm-table { table-layout: fixed; width: 100%; }
.bm-table th:nth-child(1) { width: 29%; }
.bm-table th:nth-child(2) { width: 19%; }
.bm-table th:nth-child(3) { width: 26%; }
.bm-table th:nth-child(4) { width: 12%; }
.bm-table th:nth-child(5) { width: 14%; }
.bm-vals { display: flex; gap: 6px; flex-wrap: wrap; }
.bv { font-size: 12px; padding: 1px 7px; border-radius: 10px; border: 1px solid var(--border); white-space: nowrap; }
.bv.adv { color: #2e8b57; border-color: #2e8b5755; }
.bv.acc { color: #236de9; border-color: #236de955; }
.bv.lim { color: #c77700; border-color: #c7770055; }
.bm-badge { font-size: 12px; padding: 2px 9px; border-radius: 10px; white-space: normal; text-align: center; }
.bm-adv { color: #2e8b57; background: #2e8b5718; }
.bm-acc { color: #236de9; background: #236de918; }
.bm-lim { color: #c77700; background: #c7770018; }
.bm-exc { color: #fff; background: var(--alarm); }
.bm-na { color: var(--muted); background: var(--border); }
.num.over { color: var(--alarm); }
.num.under { color: #2e8b57; }
.bm-warn { margin-top: 10px; color: var(--idle); font-size: 12px; }
.muted { color: var(--muted); }

.ws-row td { padding-top: 10px; }
.ws-row.bottom, .ws-row { border-top: none; }
.line-row td.indent { padding-left: 26px; color: var(--fg-2); }
.un-row td { color: var(--muted); font-style: italic; }
.total-row td { border-top: 1px solid var(--border); padding-top: 10px; }
.minibar.ghost { background: var(--border); }
.bad { color: var(--alarm); }
/* 口径提示：没标总表时全厂含所有设备，数字可能虚高，必须说清 */
.meter-warn {
  margin: var(--space-2) 0;
  padding: var(--space-2) var(--space-3);
  font-size: var(--text-2xs);
  line-height: 1.7;
  color: var(--warn, var(--alarm));
  background: var(--surface-2);
  border-left: 2px solid var(--warn, var(--alarm));
  border-radius: var(--radius-sm);
}

</style>
