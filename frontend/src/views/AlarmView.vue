<template>
  <div class="alarm">
    <el-alert v-if="warn" :title="warn" type="warning" show-icon :closable="false" class="warn" />

    <div class="kpis">
      <div class="ebox"><span>待处理</span>
        <b class="num al">{{ s?.open ?? 0 }}<i>条</i></b><em>未确认 + 已确认未恢复</em></div>
      <div class="ebox"><span>今日新增</span>
        <b class="num">{{ s?.todayNew ?? 0 }}<i>条</i></b></div>
      <div class="ebox"><span>今日恢复</span>
        <b class="num ok">{{ s?.todayRecovered ?? 0 }}<i>条</i></b></div>
      <div class="ebox"><span>启用规则</span>
        <b class="num">{{ s?.ruleEnabled ?? 0 }}<i>条</i></b><em>{{ notifySub }}</em></div>
    </div>

    <div class="panels">
      <div class="panel">
        <div class="p-head"><h3>近 7 天告警数</h3><span>条 / 天</span></div>
        <div ref="trendEl" class="trend"></div>
      </div>
      <div class="panel">
        <div class="p-head"><h3>今日告警最多的设备</h3><span>反复报的设备该去看看</span></div>
        <div class="mini-list">
          <div v-if="!top.length" class="empty">今日还没有告警</div>
          <div v-for="x in top" :key="x.code" class="mini-row">
            <span>{{ x.name }} <em>{{ x.code }}</em></span><b class="num">{{ x.count }} 次</b>
          </div>
        </div>
      </div>
    </div>

    <div class="panel rec">
      <div class="p-head">
        <h3>告警记录</h3>
        <div class="acts">
          <el-button size="small" @click="ruleOpen = true">规则设置</el-button>
          <el-button size="small" @click="notifyOpen = true">通知设置</el-button>
          <el-button size="small" @click="ackAll">全部确认</el-button>
        </div>
      </div>

      <div class="toolbar">
        <el-select v-model="f.state" style="width:120px" @change="reload">
          <el-option v-for="o in STATE_OPTS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-select v-model="f.level" style="width:112px" @change="reload">
          <el-option v-for="o in LEVEL_OPTS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-select v-model="f.range" style="width:112px" @change="reload">
          <el-option v-for="o in RANGE_OPTS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-input v-model="q" placeholder="搜索设备 / 告警内容" clearable style="width:200px" @input="onSearch" />
        <el-button @click="reload">刷新</el-button>
      </div>

      <div class="list">
        <div v-if="!rows.length" class="empty big-empty">
          没有符合条件的告警。<br>设备离线、停机超时、数值越限会自动记在这里，不用人盯着。
        </div>
        <div v-for="e in rows" :key="e.id" class="arow" :class="{ acked: e.state === 'ACKED' }">
          <div class="lvbar" :class="e.level.toLowerCase()"></div>
          <div class="body">
            <div class="msg">{{ e.message || e.ruleName }}</div>
            <div class="meta">
              <span class="tag" :class="'lv-' + e.level.toLowerCase()">{{ LV[e.level] || e.level }}</span>
              <span class="tag" :class="'st-' + e.state.toLowerCase()">{{ AST[e.state] || e.state }}</span>
              <span>{{ e.deviceName }} <em>{{ e.deviceCode }}</em></span>
              <span v-if="e.location">{{ e.location }}</span>
              <span>开始 {{ fmtTime(e.startedAt) }}</span>
              <span>持续 {{ fmtDur(e.durationMs) }}</span>
              <span v-if="e.clearedAt" class="ok">
                恢复 {{ fmtTime(e.clearedAt) }}{{ e.clearReason ? `（${e.clearReason}）` : '' }}
              </span>
              <span v-if="e.ackBy">{{ e.ackBy }} 已确认</span>
            </div>
          </div>
          <div class="act">
            <el-button v-if="e.state === 'ACTIVE'" size="small" @click="ack(e)">确认</el-button>
          </div>
        </div>
      </div>

      <div class="pager">
        <el-button size="small" :disabled="page <= 1" @click="go(page - 1)">上一页</el-button>
        <span>第 <b>{{ page }}</b> / {{ pages }} 页 · 共 <b>{{ total }}</b> 条</span>
        <el-button size="small" :disabled="page >= pages" @click="go(page + 1)">下一页</el-button>
      </div>
    </div>

    <RuleDialog v-model="ruleOpen" @changed="refresh" />
    <NotifyDialog v-model="notifyOpen" @changed="loadSummary" />
  </div>
</template>

<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useEcharts } from '@/composables/useEcharts'
import { usePolling } from '@/composables/usePolling'
import { useWsStore } from '@/stores/ws'
import {
  alarmsApi, AST, LEVEL_OPTS, LV, RANGE_OPTS, STATE_OPTS,
  fmtDur, fmtTime,
  type AlarmItem, type AlarmSummary
} from '@/api/alarms'
import RuleDialog from '@/components/alarm/RuleDialog.vue'
import NotifyDialog from '@/components/alarm/NotifyDialog.vue'

const ws = useWsStore()
const { init, setOption } = useEcharts()

const s = ref<AlarmSummary | null>(null)
const rows = ref<AlarmItem[]>([])
const page = ref(1), pages = ref(1), total = ref(0)
const f = reactive({ state: 'OPEN', level: 'ALL', range: '7d' })
const q = ref('')
const ruleOpen = ref(false), notifyOpen = ref(false)
const trendEl = ref<HTMLElement>()

const top = computed(() => s.value?.topDevices || [])
const notifySub = computed(() =>
  s.value?.notifyEnabled ? `通知已启用 · ${s.value.notifyChannels} 个渠道` : '通知未配置')
const warn = computed(() => {
  if (!s.value) return ''
  if (!s.value.ruleEnabled) {
    return '当前没有启用的告警规则，系统不会报警。点右上角「规则设置」打开需要监控的项目。'
  }
  if (!s.value.notifyEnabled) {
    return '通知还没配置，告警只会显示在这个网页上。没人在电脑前的时候不会有人知道。'
      + '点「通知设置」，加一个企业微信群机器人，五分钟能搞定。'
  }
  return ''
})

async function loadSummary() {
  try {
    s.value = await alarmsApi.summary()
  } catch (e: any) { console.error('告警统计加载失败', e) }
}

async function loadList() {
  try {
    const r = await alarmsApi.list({ ...f, q: q.value, page: page.value, size: 20 })
    rows.value = r.items || []
    page.value = r.page || 1
    pages.value = Math.max(1, r.pages || 1)
    total.value = r.total ?? 0
  } catch (e: any) { console.error('告警列表加载失败', e) }
}

function reload() { page.value = 1; loadList() }
function go(p: number) { page.value = Math.max(1, p); loadList() }
function refresh() { loadSummary(); loadList() }

/* 搜索 300ms 防抖（旧版 onAlarmFilter 同款） */
let searchTimer: number | null = null
function onSearch() {
  if (searchTimer) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => reload(), 300)
}

/* WS 告警事件：ws store 已做 1.5s 节流，直接刷新 */
const offDirty = ws.on('alarms:dirty', () => refresh())
onScopeDispose(() => offDirty())

/* 20s 兜底轮询（防 WS 断开时铃铛停旧值，旧版同款） */
usePolling(loadSummary, 20000, { immediate: true })
watch(f, reload)

/* 7 天趋势：红色系柱状，末柱实色（与旧 canvas 版同语义） */
const css = (n: string) => getComputedStyle(document.documentElement).getPropertyValue(n).trim()
/* 趋势图容器常驻，但 init 必须拿到 el 后调一次——之前从未调用，图一直是空的 */
let trendInited = false
watch(() => s.value?.trend, (trend) => {
  const t = trend || []
  if (!trendEl.value) return
  if (!trendInited) { init(trendEl.value); trendInited = true }
  setOption({
    grid: { left: 6, right: 10, top: 22, bottom: 4, containLabel: true },
    xAxis: { type: 'category', data: t.map((x) => String(x.date).slice(5)) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [{
      type: 'bar', barMaxWidth: 26,
      data: t.map((x, i) => ({
        value: x.count,
        itemStyle: {
          color: i === t.length - 1 ? css('--alarm') : 'rgba(224,68,59,.4)',
          borderRadius: [3, 3, 0, 0]
        }
      })),
      label: { show: true, position: 'top', fontSize: 10, color: css('--muted') }
    }]
  })
}, { immediate: true, deep: true })

async function ack(e: AlarmItem) {
  try {
    const r = await alarmsApi.ack(e.id)
    if (r && r.ok === false) { ElMessage.error(r.message || '操作失败'); return }
    refresh()
  } catch (err: any) { ElMessage.error(err?.message || '操作失败') }
}

async function ackAll() {
  try {
    await ElMessageBox.confirm(
      '确认当前所有未处理的告警？确认只是表示「人已经知道了」，设备该修还得修。',
      '全部确认', { type: 'warning', confirmButtonText: '全部确认', cancelButtonText: '取消' }
    )
  } catch { return }
  try {
    await alarmsApi.ackAll()
    refresh()
  } catch (err: any) { ElMessage.error(err?.message || '操作失败') }
}
</script>

<style scoped>
.warn { margin-bottom: var(--space-4); border-radius: var(--radius-md); }
.kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--space-3); margin-bottom: var(--space-4); }
.ebox {
  background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--elev-raised); padding: var(--space-4);
  display: flex; flex-direction: column; gap: 2px;
}
.ebox span { font-size: var(--text-xs); color: var(--muted); letter-spacing: .06em; }
.ebox b { font-size: var(--text-xl); font-weight: 600; }
.ebox b.al { color: var(--alarm); }
.ebox b.ok { color: var(--run); }
.ebox b i { font-style: normal; font-size: var(--text-xs); color: var(--muted); margin-left: 4px; }
.ebox em { font-style: normal; font-size: var(--text-2xs); color: var(--meta); }
.panels { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); margin-bottom: var(--space-4); }
.panel {
  background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--elev-raised); padding: var(--space-4); min-width: 0;
}
.p-head { display: flex; align-items: baseline; gap: var(--space-2); margin-bottom: var(--space-3); }
.p-head h3 { font-size: var(--text-md); font-weight: 600; }
.p-head > span { font-size: var(--text-2xs); color: var(--meta); }
.p-head .acts { margin-left: auto; display: flex; gap: var(--space-2); }
.trend { height: 170px; }
.mini-list { display: flex; flex-direction: column; gap: var(--space-2); min-height: 170px; }
.mini-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--space-2) var(--space-3); border-radius: var(--radius-sm);
  background: var(--surface-2); font-size: var(--text-sm);
}
.mini-row em { font-style: normal; color: var(--muted); font-size: var(--text-2xs); margin-left: 4px; }
.mini-row b { font-weight: 600; }
.rec { margin-bottom: var(--space-4); }
.toolbar { display: flex; gap: var(--space-2); flex-wrap: wrap; margin-bottom: var(--space-3); }
.list { display: flex; flex-direction: column; gap: var(--space-2); min-height: 120px; }
.arow {
  display: flex; gap: var(--space-3); align-items: center;
  background: var(--surface-2); border-radius: var(--radius-sm); padding: var(--space-2) var(--space-3);
}
.arow.acked { opacity: .72; }
.lvbar { flex: none; width: 4px; height: 34px; border-radius: 2px; background: var(--muted); }
.lvbar.critical { background: var(--alarm); }
.lvbar.major { background: var(--idle); }
.lvbar.minor { background: var(--stop); }
.body { flex: 1; min-width: 0; }
.msg { font-size: var(--text-sm); font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.meta { display: flex; gap: var(--space-3); flex-wrap: wrap; margin-top: 2px; font-size: var(--text-2xs); color: var(--muted); }
.meta em { font-style: normal; color: var(--meta); margin-left: 2px; }
.meta .ok { color: var(--run); }
.tag { padding: 1px 7px; border-radius: var(--radius-pill); font-size: var(--text-2xs); }
.lv-critical { color: var(--alarm); background: var(--alarm-soft, rgba(255, 90, 80, .11)); }
.lv-major { color: var(--idle); background: var(--idle-soft, rgba(234, 179, 8, .13)); }
.lv-minor { color: var(--stop); background: var(--stop-soft, rgba(100, 116, 139, .14)); }
.st-active { color: var(--alarm); background: var(--alarm-soft, rgba(255, 90, 80, .11)); }
.st-acked { color: var(--info); background: var(--accent-soft, rgba(35, 109, 233, .1)); }
.st-cleared { color: var(--run); background: var(--run-soft, rgba(130, 221, 45, .14)); }
.empty { color: var(--muted); font-size: var(--text-sm); padding: var(--space-4); text-align: center; }
.big-empty { line-height: 2; }
.pager { display: flex; align-items: center; gap: var(--space-3); margin-top: var(--space-3); font-size: var(--text-xs); color: var(--muted); }
@media (max-width: 1100px) { .panels { grid-template-columns: 1fr; } .kpis { grid-template-columns: repeat(2, 1fr); } }
</style>
