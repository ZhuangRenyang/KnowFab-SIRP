<template>
  <div class="digest">
    <el-alert v-if="warn" :title="warn" type="warning" show-icon :closable="false" class="warn" />

    <div class="toolbar">
      <el-button type="primary" :loading="generating" @click="gen">
        {{ generating ? t('digest.generating') : t('digest.generate') }}
      </el-button>
      <el-button @click="load">{{ t('digest.refresh') }}</el-button>
      <span class="spacer"></span>
      <span class="sched">{{ scheduleText }}</span>
      <el-button v-if="auth.isAdmin" @click="cfgOpen = true">{{ t('digest.settings') }}</el-button>
    </div>

    <div class="panel main">
      <div class="p-head">
        <h3>{{ current ? current.date + ' ' + t('digest.titleSuffix') : t('digest.titleSuffix') }}</h3>
        <span v-if="current" class="meta">{{ metaText }}</span>
      </div>
      <div v-if="current" class="body">{{ bodyText }}</div>
      <div v-else class="empty">
        {{ t('digest.empty', { time: hhmm }) }}
      </div>
      <div v-if="facts" class="facts">
        <div class="ebox"><span>{{ t('digest.online') }}</span><b class="num">{{ facts.online }}/{{ facts.deviceTotal }}</b></div>
        <div class="ebox"><span>{{ t('digest.output') }}</span><b class="num">{{ facts.output }}<i>{{ t('digest.unitPiece') }}</i></b>
          <em>{{ t('digest.avg7', { v: facts.outputAvg7 }) }}</em></div>
        <div class="ebox"><span>{{ t('digest.stop') }}</span><b class="num">{{ facts.stopHours }}<i>h</i></b></div>
        <div class="ebox"><span>{{ t('digest.cost') }}</span><b class="num">{{ facts.cost }}<i>{{ t('digest.unitYuan') }}</i></b></div>
        <div class="ebox"><span>{{ t('digest.alarmOpen') }}</span><b class="num">{{ facts.alarmOpen }}<i>{{ t('digest.unitItem') }}</i></b>
          <em>{{ t('digest.critical', { n: facts.alarmCriticalOpen }) }}</em></div>
        <div class="ebox"><span>{{ t('digest.demand') }}</span><b class="num">{{ facts.demandPeak }}<i>kW</i></b>
          <em>{{ t('digest.contract', { n: facts.contractDemand }) }}</em></div>
      </div>
    </div>

    <div class="panel">
      <div class="p-head"><h3>{{ t('digest.history') }}</h3><span>{{ t('digest.historyHint') }}</span></div>
      <div v-if="!history.length" class="empty">{{ t('digest.historyEmpty') }}</div>
      <table v-else class="etable">
        <thead><tr><th>{{ t('digest.thDate') }}</th><th>{{ t('digest.thSource') }}</th><th>{{ t('digest.thContent') }}</th><th style="text-align:right">{{ t('digest.thAt') }}</th></tr></thead>
        <tbody>
          <tr v-for="d in history" :key="d.date" class="hrow" @click="show(d)">
            <td>{{ d.date }}</td>
            <td>{{ d.source === 'AI' ? 'AI' : t('digest.srcStat') }}</td>
            <td class="clip">{{ (d.content || '').replace(/\n/g, ' ').slice(0, 80) }}</td>
            <td class="num ct">{{ d.createdText || '' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <AiConfigDialog v-model="cfgOpen" @saved="load" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { aiApi, type DigestEntry, type DigestFacts } from '@/api/ai'
import { usePolling } from '@/composables/usePolling'

const { t } = useI18n()

const auth = useAuthStore()
const data = ref<{ enabled: boolean; hour: number; minute: number; nextRun?: string } | null>(null)
const warnings = ref<string[]>([])
const current = ref<DigestEntry | null>(null)
const history = ref<DigestEntry[]>([])
const generating = ref(false)
const cfgOpen = ref(false)

const facts = computed<DigestFacts | null>(() => current.value?.facts || null)
const scheduleText = computed(() => {
  if (!data.value) return ''
  const base = data.value.enabled
    ? t('digest.everyDay', { time: String(data.value.hour).padStart(2, '0') + ':' + String(data.value.minute).padStart(2, '0') })
    : t('digest.aiOffLocal')
  return base + t('digest.nextRun', { time: data.value.nextRun || '--' })
})
const warn = computed(() => {
  if (warnings.value.length) return warnings.value.join('；')
  if (data.value && !data.value.enabled) {
    return t('digest.offTip')
      + (auth.isAdmin ? t('digest.offTipAdmin') : t('digest.offTipUser'))
  }
  return ''
})
const metaText = computed(() => {
  const d = current.value
  if (!d) return ''
  const src = d.source === 'AI' ? t('digest.srcAi', { model: d.model || '' }) : t('digest.srcLocal')
  return src + (d.tokens ? ` · ${d.tokens} tokens` : '') + ` · ${d.createdText || ''}`
})
const bodyText = computed(() => {
  const d = current.value
  if (!d) return ''
  return (d.content || '') + (d.source === 'AI' ? t('digest.aiFooter') : '')
})
const hhmm = computed(() =>
  data.value ? `${String(data.value.hour).padStart(2, '0')}:${String(data.value.minute).padStart(2, '0')}` : '--')

async function load() {
  try {
    const d = await aiApi.digest()
    data.value = d
    warnings.value = d.warnings || []
    current.value = d.digest || null
    history.value = d.history || []
  } catch (e: any) { console.error(t('digest.loadFailed'), e) }
}

function show(d: DigestEntry) {
  current.value = d
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function gen() {
  generating.value = true
  try {
    const r = await aiApi.generate()
    if (!r.ok) { ElMessage.error(r.message || t('digest.genFailed')); return }
    if (r.degraded) {
      ElMessageBox.alert(t('digest.degradedMsg') + (r.error || t('digest.unknownReason')),
        t('digest.degradedTitle'), { type: 'warning', confirmButtonText: t('digest.okBtn') }).catch(() => {})
    }
    await load()
  } catch (e: any) {
    ElMessage.error(t('digest.genFailed') + '：' + (e?.message || ''))
  } finally { generating.value = false }
}

/* 3 分钟轻轮询兜底（自动生成时段错过页面也能看到新早报） */
usePolling(load, 180000, { immediate: true })
</script>

<style scoped>
.warn { margin-bottom: var(--space-4); border-radius: var(--radius-md); }
.toolbar { display: flex; gap: var(--space-2); align-items: center; margin-bottom: var(--space-4); flex-wrap: wrap; }
.spacer { flex: 1; }
.sched { font-size: var(--text-xs); color: var(--muted); }
.panel {
  background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--elev-raised); padding: var(--space-4); margin-bottom: var(--space-4);
}
.p-head { display: flex; align-items: baseline; gap: var(--space-2); margin-bottom: var(--space-3); }
.p-head h3 { font-size: var(--text-md); font-weight: 600; }
.meta { font-size: var(--text-2xs); color: var(--muted); }
.body { font-size: var(--text-sm); line-height: 2; white-space: pre-wrap; }
.empty { color: var(--muted); font-size: var(--text-sm); padding: var(--space-4); text-align: center; line-height: 2; }
.facts {
  display: grid; grid-template-columns: repeat(6, 1fr); gap: var(--space-3);
  margin-top: var(--space-4); padding-top: var(--space-3); border-top: 1px solid var(--border);
}
.ebox { background: var(--surface-2); border-radius: var(--radius-md); padding: var(--space-3);
  display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.ebox span { font-size: var(--text-2xs); color: var(--muted); }
.ebox b { font-size: var(--text-lg); font-weight: 600; }
.ebox b i { font-style: normal; font-size: var(--text-2xs); color: var(--muted); margin-left: 3px; }
.ebox em { font-style: normal; font-size: var(--text-2xs); color: var(--meta); }
.etable { width: 100%; border-collapse: collapse; font-size: var(--text-sm); }
.etable th { text-align: left; font-size: var(--text-2xs); color: var(--meta); font-weight: 400; padding: 6px 8px; }
.etable td { padding: 8px; border-bottom: 1px solid var(--border-soft); }
.etable .hrow { cursor: pointer; }
.etable .hrow:hover td { background: var(--hover, rgba(46, 58, 62, .045)); }
.etable .clip { max-width: 520px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.etable .ct { font-size: var(--text-2xs); color: var(--muted); text-align: right; }
@media (max-width: 1100px) { .facts { grid-template-columns: repeat(3, 1fr); } }
</style>
