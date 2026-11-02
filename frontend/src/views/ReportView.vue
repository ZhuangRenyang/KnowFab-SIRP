<template>
  <div class="report">
    <el-alert v-if="warn" :title="warn" type="warning" show-icon :closable="false" class="warn" />
    <el-alert v-if="(totals?.manualOutput || 0) > 0" :closable="false" type="info" show-icon class="warn"
              :title="t('rpt.manualDayHint', { n: num(totals?.manualOutput, 0) })" />

    <div class="toolbar">
      <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" style="width:150px" @change="load" />
      <el-button @click="shiftDay(-1)">{{ t('rpt.prevDay') }}</el-button>
      <el-button @click="shiftDay(1)">{{ t('rpt.nextDay') }}</el-button>
      <el-button type="primary" :loading="exporting" @click="exportXlsx">{{ t('rpt.exportXlsx') }}</el-button>
      <span class="msep"></span>
      <el-date-picker v-model="month" type="month" value-format="YYYY-MM" style="width:130px" />
      <el-button :loading="monthExporting" @click="exportMonthXlsx">{{ t('rpt.exportMonth') }}</el-button>
      <el-button @click="shiftOpen = true">{{ t('rpt.shiftSettings') }}</el-button>
      <el-button v-if="canCfg" @click="manualOpen = true">{{ t('rpt.manualBtn') }}</el-button>
      <span v-if="planName" class="plan">{{ t('rpt.planLabel', { name: planName }) }}</span>
    </div>

    <div class="kpis">
      <div class="ebox"><span>{{ t('rpt.kpiOutput') }}</span>
        <b class="num">{{ num(totals?.output, 0) }}<i>{{ t('rpt.unitPiece') }}</i></b><em>{{ t('rpt.devicesTotal', { n: totals?.deviceCount || 0 }) }}</em></div>
      <div class="ebox"><span>{{ t('rpt.kpiAvail') }}</span>
        <b class="num">{{ availText }}<i>%</i></b><em>{{ t('rpt.recorded', { v: num((totals?.countedSec || 0) / 3600, 1) }) }}</em></div>
      <div class="ebox"><span>{{ t('rpt.stopDur') }}</span>
        <b class="num st">{{ num((totals?.stopSec || 0) / 3600, 1) }}<i>h</i></b><em>{{ t('rpt.allDevices') }}</em></div>
      <div class="ebox"><span>{{ t('rpt.stopCount') }}</span>
        <b class="num al">{{ stopCount }}<i>{{ t('rpt.unitTimes') }}</i></b>
        <em>{{ stopCount ? t('rpt.stopAccum', { n: num(stopMin, 0) }) : t('rpt.noStopAlarm') }}</em></div>
    </div>

    <div class="panel">
      <div class="p-head"><h3>{{ t('oee.title') }}</h3><span>{{ t('oee.panelHint') }}</span></div>
      <OeePanel :oee="oee" />
    </div>

    <div class="panel"><OeeTrendPanel /></div>

    <div class="panels">
      <div class="panel">
        <div class="p-head"><h3>{{ t('rpt.hourly') }}</h3><span>{{ t('rpt.hourlySub', { peak }) }}</span></div>
        <div ref="hoursEl" class="hours"></div>
      </div>
      <div class="panel">
        <div class="p-head"><h3>{{ t('rpt.stopReasons') }}</h3><span>{{ t('rpt.stopReasonsSub') }}</span></div>
        <div class="mini-list">
          <div v-if="!stopReasons.length" class="empty">
            {{ t('rpt.stopEmpty') }}<br>{{ t('rpt.stopEmptyHint') }}
          </div>
          <div v-for="r in stopReasons" :key="r.reason" class="rs">
            <div class="rs-top">
              <span class="rs-name">{{ r.reason }}</span>
              <span class="rs-val">{{ t('rpt.timesMin', { n: r.count, m: num(r.totalMin, 0) }) }}</span>
            </div>
            <div class="rs-track"><div class="rs-fill" :style="{ width: pct(r.totalMin) }"></div></div>
          </div>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="p-head"><h3>{{ t('rpt.shiftSummary') }}</h3></div>
      <div v-if="!shifts.length" class="empty">{{ t('rpt.shiftEmpty') }}</div>
      <table v-else class="etable">
        <thead><tr><th>{{ t('rpt.thShift') }}</th><th class="r">{{ t('rpt.thOutput') }}</th><th class="r">{{ t('rpt.thRun') }}</th><th class="r">{{ t('rpt.thIdle') }}</th>
          <th class="r">{{ t('rpt.thStop') }}</th><th class="r">{{ t('rpt.thAvail') }}</th></tr></thead>
        <tbody>
          <tr v-for="s in shifts" :key="s.id">
            <td>{{ s.name }} <span class="dim">{{ s.start }}-{{ s.end }}</span></td>
            <td class="r num">{{ num(s.output, 0) }}</td>
            <td class="r num">{{ num(s.runSec / 3600, 1) }}</td>
            <td class="r num">{{ num(s.idleSec / 3600, 1) }}</td>
            <td class="r num">{{ num(s.stopSec / 3600, 1) }}</td>
            <td class="r num">{{ s.availability < 0 ? '--' : num(s.availability, 1) + '%' }}</td>
          </tr>
          <tr class="total">
            <td><b>{{ t('rpt.total') }}</b></td>
            <td class="r num"><b>{{ num(totals?.output, 0) }}</b></td>
            <td class="r num"><b>{{ num((totals?.runSec || 0) / 3600, 1) }}</b></td>
            <td class="r num"><b>{{ num((totals?.idleSec || 0) / 3600, 1) }}</b></td>
            <td class="r num"><b>{{ num((totals?.stopSec || 0) / 3600, 1) }}</b></td>
            <td class="r num"><b>{{ availText === '--' ? '--' : availText + '%' }}</b></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="panel">
      <div class="p-head"><h3>{{ t('rpt.devDetail') }}</h3><span>{{ t('rpt.devDetailSub') }}</span></div>
      <div v-if="!devices.length" class="empty">{{ t('rpt.devEmpty') }}</div>
      <table v-else class="etable">
        <thead><tr><th>{{ t('rpt.thDevice') }}</th><th class="r">{{ t('rpt.thOutput') }}</th>
          <th class="r">{{ t('rpt.thManual') }}</th>
          <th v-for="s in shifts" :key="s.id" class="r">{{ s.name }}</th>
          <th class="r">{{ t('rpt.thRun') }}</th><th class="r">{{ t('rpt.thStop') }}</th><th class="r">{{ t('rpt.thAvail') }}</th>
          <th class="r">{{ t('oee.colTitle') }}</th></tr></thead>
        <tbody>
          <tr v-for="d in devices" :key="d.code">
            <td>{{ d.name }}<div class="dim">{{ d.code }} · {{ d.location || '' }}</div></td>
            <td class="r num">{{ num(d.output, 0) }}</td>
            <td class="r num">{{ d.manualOutput ? num(d.manualOutput, 0) : '—' }}</td>
            <td v-for="s in shifts" :key="s.id" class="r num">{{ num(d.shiftOutput?.[s.id] || 0, 0) }}</td>
            <td class="r num">{{ num(d.runSec / 3600, 1) }}</td>
            <td class="r num">{{ num(d.stopSec / 3600, 1) }}</td>
            <td class="r num">{{ d.availability < 0 ? '--' : num(d.availability, 1) + '%' }}</td>
            <td class="r num">{{ d.oee ? (d.oee.oee === null ? '--' : num(d.oee.oee, 1) + '%') : '--' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <ShiftsDialog v-model="shiftOpen" @saved="load" />
    <ManualDialog v-model="manualOpen" @changed="load" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { useEcharts } from '@/composables/useEcharts'
import {
  reportsApi, exportDaily, exportMonth, num, todayStr,
  type ShiftRow, type DeviceRow, type StopReason, type ReportTotals
} from '@/api/reports'
import { useAuthStore } from '@/stores/auth'
import ShiftsDialog from '@/components/report/ShiftsDialog.vue'
import ManualDialog from '@/components/report/ManualDialog.vue'
import OeePanel from '@/components/report/OeePanel.vue'
import OeeTrendPanel from '@/components/report/OeeTrendPanel.vue'

const { t } = useI18n()
const auth = useAuthStore()
const canCfg = computed(() => ['ADMIN', 'MANAGER'].includes(auth.user?.role || ''))

const { init, setOption } = useEcharts()

const date = ref(todayStr(0))
const data = ref<any>(null)
const warn = ref('')
const exporting = ref(false)
const monthExporting = ref(false)
const manualOpen = ref(false)
const month = ref(todayStr(0).slice(0, 7))
const shiftOpen = ref(false)
const hoursEl = ref<HTMLElement>()
let reportInited = false

const totals = computed<ReportTotals | null>(() => data.value?.totals || null)
const shifts = computed<ShiftRow[]>(() => data.value?.shifts || [])
const devices = computed<DeviceRow[]>(() => data.value?.devices || [])
/** DH-15：合计 OEE（算不出来的时候是 null，界面上显示 --） */
const oee = computed(() => data.value?.oee)
const stopReasons = computed<StopReason[]>(() => data.value?.stopReasons || [])
const planName = computed(() => data.value?.planName || '')
const availText = computed(() => {
  const av = totals.value?.availability
  return av == null || av < 0 ? '--' : num(av, 1)
})
const stopCount = computed(() => stopReasons.value.reduce((a, r) => a + (r.count || 0), 0))
const stopMin = computed(() => stopReasons.value.reduce((a, r) => a + (r.totalMin || 0), 0))
const peak = computed(() => {
  const h = data.value?.hours || []
  return h.length ? Math.round(Math.max(...h.map((x: any) => x.output || 0), 1)) : 0
})

const css = (n: string) => getComputedStyle(document.documentElement).getPropertyValue(n).trim()
function hexRgba(hex: string, a: number): string {
  const m = hex.replace('#', '')
  const v = m.length === 3 ? m.split('').map((c) => c + c).join('') : m
  const int = parseInt(v, 16)
  return `rgba(${(int >> 16) & 255},${(int >> 8) & 255},${int & 255},${a})`
}
const maxHour = computed(() => {
  const h = data.value?.hours || []
  return Math.max(...h.map((x: any) => x.output || 0), 1)
})

function pct(min: number): string {
  const max = Math.max(...stopReasons.value.map((r) => r.totalMin || 0), 1)
  return Math.round((min || 0) / max * 100) + '%'
}

watch(hoursEl, (el) => { if (el) init(el) })
watch(() => data.value?.hours, (hours) => {
  const h = hours || []
  if (!h.length || !hoursEl.value) return
  setOption({
    grid: { left: 6, right: 10, top: 18, bottom: 4, containLabel: true },
    xAxis: {
      type: 'category', data: h.map((x: any) => String(x.hour).padStart(2, '0')),
      axisLabel: { interval: (i: number) => i % 3 === 0 }
    },
    yAxis: { type: 'value' },
    series: [{
      type: 'bar', barMaxWidth: 26,
      data: h.map((x: any) => ({
        value: x.output || 0,
        /* 夜班（0-7 点）产量低是常态，用暗一点的色，一眼看出哪几个小时没干活 */
        itemStyle: { color: hexRgba(css('--accent'), x.hour < 8 ? .35 : .85), borderRadius: [2, 2, 0, 0] }
      }))
    }]
  })
}, { deep: true })

function shiftDay(d: number) {
  const dt = new Date(date.value)
  dt.setDate(dt.getDate() + d)
  const p = (n: number) => String(n).padStart(2, '0')
  date.value = `${dt.getFullYear()}-${p(dt.getMonth() + 1)}-${p(dt.getDate())}`
  load()
}

async function load() {
  try {
    const j = await reportsApi.daily(date.value)
    if (!j || !j.ok) throw new Error(t('rpt.badResponse'))
    warn.value = ''
    /* 早晚班交接刚过、或刚装好的系统，今天就是没数据。
       第一次进来先落到最近有数据的那天，并说明白（旧版语义） */
    if (!reportInited && j.date === todayStr(0) && !((j.totals || {}).countedSec > 0)) {
      reportInited = true
      const y = await reportsApi.daily(todayStr(-1))
      if (y && y.ok && (y.totals || {}).countedSec > 0) {
        date.value = y.date
        data.value = y
        warn.value = t('rpt.warnFallback', { date: y.date })
        return
      }
    }
    reportInited = true
    data.value = j
    const warns = (j.warnings && j.warnings.length) ? j.warnings.join('；') : ''
    if (warns) warn.value = warns
    else if ((j.totals || {}).countedSec < 3600) {
      warn.value = t('rpt.warnSparse')
    }
  } catch (e: any) {
    console.error(t('rpt.loadFailed'), e)
    warn.value = t('rpt.loadFailed')
  }
}

async function exportXlsx() {
  exporting.value = true
  try {
    await exportDaily(date.value || todayStr(0))
  } catch (e: any) { ElMessage.error(e?.message || t('rpt.exportFailed')) }
  finally { exporting.value = false }
}

async function exportMonthXlsx() {
  monthExporting.value = true
  try {
    await exportMonth(month.value || todayStr(0).slice(0, 7))
  } catch (e: any) { ElMessage.error(e?.message || t('rpt.exportFailed')) }
  finally { monthExporting.value = false }
}

load()
</script>

<style scoped>
.warn { margin-bottom: var(--space-4); border-radius: var(--radius-md); }
.toolbar { display: flex; gap: var(--space-2); align-items: center; margin-bottom: var(--space-4); flex-wrap: wrap; }
.plan { font-size: var(--text-xs); color: var(--muted); }
/* 日导出 / 月导出之间的竖线，避免两排按钮糊成一片 */
.msep { width: 1px; height: 20px; background: var(--border); margin: 0 4px; }
.kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--space-3); margin-bottom: var(--space-4); }
.ebox { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--elev-raised); padding: var(--space-4); display: flex; flex-direction: column; gap: 2px; }
.ebox span { font-size: var(--text-xs); color: var(--muted); letter-spacing: .06em; }
.ebox b { font-size: var(--text-xl); font-weight: 600; }
.ebox b.st { color: var(--stop); }
.ebox b.al { color: var(--alarm); }
.ebox b i { font-style: normal; font-size: var(--text-xs); color: var(--muted); margin-left: 4px; }
.ebox em { font-style: normal; font-size: var(--text-2xs); color: var(--meta); }
.panels { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); margin-bottom: var(--space-4); }
.panel { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--elev-raised); padding: var(--space-4); min-width: 0; margin-bottom: var(--space-4); }
.p-head { display: flex; align-items: baseline; gap: var(--space-2); margin-bottom: var(--space-3); }
.p-head h3 { font-size: var(--text-md); font-weight: 600; }
.p-head span { font-size: var(--text-2xs); color: var(--meta); }
.hours { height: 180px; }
.mini-list { display: flex; flex-direction: column; gap: var(--space-3); min-height: 180px; }
.rs-top { display: flex; justify-content: space-between; font-size: var(--text-xs); gap: var(--space-2); }
.rs-name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rs-val { color: var(--muted); white-space: nowrap; }
.rs-track { height: 5px; background: var(--surface-2); border-radius: 3px; margin-top: 4px; overflow: hidden; }
.rs-fill { height: 5px; background: var(--alarm); border-radius: 3px; }
.empty { color: var(--muted); font-size: var(--text-sm); padding: var(--space-4); text-align: center; line-height: 2; }
.etable { width: 100%; border-collapse: collapse; font-size: var(--text-sm); }
.etable th { text-align: left; font-size: var(--text-2xs); color: var(--meta); font-weight: 400; padding: 6px 8px; }
.etable td { padding: 8px; border-bottom: 1px solid var(--border-soft); }
.etable .r { text-align: right; }
.etable .dim { font-size: var(--text-2xs); color: var(--muted); }
.etable tr.total td { border-top: 1px solid var(--border-strong); }
@media (max-width: 1100px) { .panels { grid-template-columns: 1fr; } .kpis { grid-template-columns: repeat(2, 1fr); } }
</style>
