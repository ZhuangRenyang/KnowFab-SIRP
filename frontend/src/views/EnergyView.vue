<template>
  <div class="energy">
    <div class="toolbar">
      <span class="lbl">统计口径</span>
      <el-select v-model="range" style="width:104px" @change="loadRank">
        <el-option value="today" label="今日" />
        <el-option value="7d" label="近 7 天" />
        <el-option value="month" label="本月" />
      </el-select>
      <el-button @click="load">刷新</el-button>
      <span class="spacer"></span>
      <span class="plan">{{ ov?.tariffName || '' }}</span>
      <el-button v-if="canCfg" @click="tariffOpen = true">电价设置</el-button>
      <el-button v-if="canCfg" @click="demandOpen = true">需量设置</el-button>
    </div>

    <el-alert v-if="warn" :title="warn" type="warning" show-icon :closable="false" class="warn" />

    <div class="kpis">
      <div class="ebox"><span>今日用电</span><b class="num">{{ num(ov?.todayKwh, 1) }}<i>kWh</i></b></div>
      <div class="ebox"><span>今日电费</span><b class="num">{{ num(ov?.todayCost, 2) }}<i>元</i></b></div>
      <div class="ebox"><span>本月电费</span><b class="num">{{ num(ov?.monthCost, 2) }}<i>元</i></b>
        <em>本月共 {{ num(ov?.monthKwh, 1) }} kWh · 仅电度电费</em></div>
      <div class="ebox"><span>当前总负荷</span><b class="num">{{ num(ov?.power, 2) }}<i>kW</i></b>
        <em>全厂瞬时功率合计</em></div>
      <div class="ebox"><span>本月最大需量</span>
        <b class="num" :class="{ al: dm?.status?.level === 'OVER', wn: dm?.status?.level === 'WARN' }">
          {{ num(dm?.monthMax?.kw, 0) }}<i>kW</i></b>
        <em>合同 {{ num(dm?.config?.contractDemandKw, 0) }} kW · 峰在 {{ dm?.monthMax?.atText || '--' }}</em></div>
      <div class="ebox"><span>本月基本电费</span><b class="num">{{ num(dm?.billing?.cost, 0) }}<i>元</i></b>
        <em>{{ (dm?.billing?.modeText || '--') + ' · ' + (dm?.billing?.formula || '') }}</em></div>
    </div>

    <div class="panel demand">
      <div class="p-head">
        <h3>{{ demandTitle }}</h3>
        <span>{{ (todayFallback ? '峰值 ' : '今日峰值 ') + num(dm?.today?.peakKw, 0) }} kW
          <template v-if="dm?.today?.peakAt && dm.today.peakAt !== '--'"> @ {{ dm.today.peakAt }}</template>
        </span>
      </div>
      <div ref="demandEl" class="chart"></div>
      <div class="dinfo" v-html="demandInfo"></div>
    </div>

    <div class="panels">
      <div class="panel">
        <div class="p-head"><h3>今日峰谷分布</h3><span>{{ segTotalText }}</span></div>
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
            <div class="seg-name">{{ s.name }}<span> · ¥{{ num(s.price, 3) }}/度</span></div>
            <div class="num">{{ num(s.kwh, 1) }} kWh</div>
            <div class="num">¥{{ num(s.cost, 2) }}</div>
            <div class="pct">{{ s.ratio > 0 ? num(s.ratio, 0) + '%' : '—' }}</div>
          </div>
        </div>
      </div>
      <div class="panel">
        <div class="p-head"><h3>近 7 日电费</h3><span>元 / 天</span></div>
        <div ref="trendEl" class="chart"></div>
      </div>
    </div>

    <div class="panel rank">
      <div class="p-head"><h3>设备能耗排行 · {{ rangeLabel }}</h3><span>点一行看它今天几点最费电</span></div>
      <div v-if="!rank.length" class="empty" v-html="rankEmpty"></div>
      <table v-else class="etable">
        <thead><tr><th>设备</th><th>位置</th><th class="r">用电量</th><th class="r">电费</th><th style="width:110px">占比</th></tr></thead>
        <tbody>
          <tr v-for="x in rank" :key="x.code" class="pick" :class="{ sel: picked === x.code }" @click="pick(x.code)">
            <td>{{ x.name }}
              <div class="dev-code">{{ x.code }}{{ x.online ? '' : ' · 离线' }}{{ x.backfillKwh > 0 ? ' · 含补偿' : '' }}</div></td>
            <td class="loc">{{ x.location || '—' }}</td>
            <td class="r num">{{ num(x.kwh, 1) }} kWh</td>
            <td class="r num">¥{{ num(x.cost, 2) }}</td>
            <td><div class="minibar-track"><div class="minibar" :style="{ width: barW(x.kwh) }"></div></div></td>
          </tr>
        </tbody>
      </table>
      <div v-if="picked" class="detail">
        <div class="p-head"><h3>{{ detailTitle }}</h3><span>每小时电量</span></div>
        <div ref="hourlyEl" class="chart-s"></div>
      </div>
    </div>

    <TariffDialog v-model="tariffOpen" @saved="load" />
    <DemandDialog v-model="demandOpen" @saved="load" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { energyApi, SEG_COLOR, type EnergyOverview, type EnergyRankItem, type DemandData, type TariffSegment } from '@/api/energy'
import { num } from '@/api/reports'
import { useAuthStore } from '@/stores/auth'
import { useEcharts } from '@/composables/useEcharts'
import { usePolling } from '@/composables/usePolling'
import TariffDialog from '@/components/energy/TariffDialog.vue'
import DemandDialog from '@/components/energy/DemandDialog.vue'

const auth = useAuthStore()
const { init: initDemand, setOption: setDemand } = useEcharts()
const { init: initTrend, setOption: setTrend } = useEcharts()
const { init: initHourly, setOption: setHourly } = useEcharts()

const range = ref('today')
const ov = ref<EnergyOverview | null>(null)
const rank = ref<EnergyRankItem[]>([])
const dm = ref<DemandData | null>(null)
const picked = ref('')
const detail = ref<any>(null)
const tariffOpen = ref(false), demandOpen = ref(false)
const demandEl = ref<HTMLElement>(), trendEl = ref<HTMLElement>(), hourlyEl = ref<HTMLElement>()

const canCfg = computed(() => ['ADMIN', 'MANAGER'].includes(auth.user?.role || ''))
const warn = computed(() => {
  if (!ov.value) return ''
  if (!ov.value.hasEnergyPoint) {
    return '还没有设备配电量点，所以算不出电费。到「设备监控」编辑设备，在点表里加一项 role 为 energy 的累计电量点（电表的电量寄存器），系统就会自动按时段算钱。'
  }
  if (ov.value.unpricedKwh > 0) {
    return `有 ${num(ov.value.unpricedKwh, 1)} kWh 电量没匹配到电价时段，这部分只记度数、不算钱。点右上角「电价设置」检查时段是不是没有覆盖满 24 小时。`
  }
  return ''
})
const shownSegs = computed(() => (ov.value?.segments || []).filter((s) => s.kwh > 0))
const segTotalText = computed(() => {
  const total = (ov.value?.segments || []).reduce((a, s) => a + s.kwh, 0)
  return total > 0 ? `共 ${num(total, 1)} kWh` : '暂无数据'
})
const segPct = (s: TariffSegment) => {
  const total = (ov.value?.segments || []).reduce((a, x) => a + x.kwh, 0)
  return (s.kwh / total * 100).toFixed(2) + '%'
}
const rangeLabel = computed(() => ({ today: '今日', '7d': '近 7 天', month: '本月' }[range.value] || '今日'))
const rankEmpty = computed(() => ov.value?.hasEnergyPoint
  ? '这段时间还没有电量数据。<br>电表读数在累积中，通常几分钟后就会出现第一笔。'
  : '还没有设备配电量点，先去设备配置里加一项 role 为 energy 的点。')
const barW = (kwh: number) => {
  const max = Math.max(...rank.value.map((x) => x.kwh), 0.001)
  return Math.max(2, kwh / max * 100).toFixed(1) + '%'
}
const demandTitle = computed(() => {
  const t = dm.value?.today || {}
  return t.fallback ? `15 分钟需量（${t.date || '昨天'}，今天还没有数据）` : '今日 15 分钟需量'
})
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
  return `<b>基本电费核算</b><br>`
    + `按需量：${num(mm.kw, 0)} kW × ${num(cfg.demandPrice, 2)} 元 = ${num(bill.demandCost, 2)} 元/月；`
    + `按容量：${num(cfg.transformerKva, 0)} kVA × ${num(cfg.capacityPrice, 2)} 元 = ${num(bill.capacityCost, 2)} 元/月`
    + (Math.abs(gap) > 0.5
      ? `；<b>${bill.mode === 'CAPACITY' ? '按容量' : '按需量'}更划算，每月差 ${num(Math.abs(gap), 0)} 元</b>` : '')
    + `<br>当前 15 分钟窗口已累计 ${num(cur.kw, 0)} kW（窗口走了 ${Math.round((cur.elapsedSec || 0) / 60)} 分钟），本月剩余余量 ${num(st.headroomKw, 0)} kW`
    + `<br><b>本月电费合计</b>：电度 ${num(degreeCost, 2)} 元 + 基本 ${num(bill.cost, 2)} 元 = `
    + `<b>${num(degreeCost + (bill.cost || 0), 2)} 元</b>（力调电费未计）`
    + `<div style="margin-top:8px;color:${color}">${st.message || ''}</div>`
})

const css = (n: string) => getComputedStyle(document.documentElement).getPropertyValue(n).trim()

/* 需量图：合同红线 + 超合同红/超 90% 黄/正常蓝 */
watch([demandEl, () => dm.value?.today?.series], () => {
  const s = dm.value?.today?.series || []
  const contract = dm.value?.config?.contractDemandKw || 0
  if (!s.length || !demandEl.value) return
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
        label: { formatter: `合同 ${Math.round(contract)} kW`, color: css('--alarm'), fontSize: 10 },
        data: [{ yAxis: contract }]
      } : undefined
    }]
  })
})

/* 近 7 日电费：末柱实色 accent，其余淡 accent，柱顶金额 */
watch([trendEl, () => ov.value?.trend], () => {
  const t = ov.value?.trend || []
  if (!t.length || !trendEl.value) return
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
      energyApi.demand().catch(() => null)
    ])
    ov.value = o
    if (d && d.ok) dm.value = d
    if (picked.value) loadDetail(picked.value)
  } catch (e: any) { console.error('能耗数据加载失败', e) }
}

async function loadRank() {
  try {
    const rk = await energyApi.rank(range.value)
    rank.value = Array.isArray(rk) ? rk : []
  } catch (e: any) { console.error('设备排行加载失败', e) }
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
</style>
