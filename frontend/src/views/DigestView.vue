<template>
  <div class="digest">
    <el-alert v-if="warn" :title="warn" type="warning" show-icon :closable="false" class="warn" />

    <div class="toolbar">
      <el-button type="primary" :loading="generating" @click="gen">
        {{ generating ? '生成中…（调模型可能要十几秒）' : '立即生成' }}
      </el-button>
      <el-button @click="load">刷新</el-button>
      <span class="spacer"></span>
      <span class="sched">{{ scheduleText }}</span>
      <el-button v-if="auth.isAdmin" @click="cfgOpen = true">AI 设置</el-button>
    </div>

    <div class="panel main">
      <div class="p-head">
        <h3>{{ current ? current.date + ' 设备早报' : '设备早报' }}</h3>
        <span v-if="current" class="meta">{{ metaText }}</span>
      </div>
      <div v-if="current" class="body">{{ bodyText }}</div>
      <div v-else class="empty">
        还没有生成过早报。点「立即生成」出一份昨天的，或者等明天 {{ hhmm }} 自动生成。
      </div>
      <div v-if="facts" class="facts">
        <div class="ebox"><span>设备在线</span><b class="num">{{ facts.online }}/{{ facts.deviceTotal }}</b></div>
        <div class="ebox"><span>昨日产量</span><b class="num">{{ facts.output }}<i>件</i></b>
          <em>前 7 日均 {{ facts.outputAvg7 }}</em></div>
        <div class="ebox"><span>停机</span><b class="num">{{ facts.stopHours }}<i>h</i></b></div>
        <div class="ebox"><span>昨日电费</span><b class="num">{{ facts.cost }}<i>元</i></b></div>
        <div class="ebox"><span>未处理报警</span><b class="num">{{ facts.alarmOpen }}<i>条</i></b>
          <em>严重 {{ facts.alarmCriticalOpen }}</em></div>
        <div class="ebox"><span>本月需量</span><b class="num">{{ facts.demandPeak }}<i>kW</i></b>
          <em>合同 {{ facts.contractDemand }}</em></div>
      </div>
    </div>

    <div class="panel">
      <div class="p-head"><h3>历史早报</h3><span>点一行查看</span></div>
      <div v-if="!history.length" class="empty">还没有历史早报</div>
      <table v-else class="etable">
        <thead><tr><th>日期</th><th>来源</th><th>内容</th><th style="text-align:right">生成时间</th></tr></thead>
        <tbody>
          <tr v-for="d in history" :key="d.date" class="hrow" @click="show(d)">
            <td>{{ d.date }}</td>
            <td>{{ d.source === 'AI' ? 'AI' : '统计' }}</td>
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
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { aiApi, type DigestEntry, type DigestFacts } from '@/api/ai'
import { usePolling } from '@/composables/usePolling'

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
    ? `每天 ${String(data.value.hour).padStart(2, '0')}:${String(data.value.minute).padStart(2, '0')} 自动生成`
    : 'AI 未启用（走本地模板）'
  return `${base} · 下次 ${data.value.nextRun || '--'}`
})
const warn = computed(() => {
  if (warnings.value.length) return warnings.value.join('；')
  if (data.value && !data.value.enabled) {
    return 'AI 未启用，早报用系统统计生成（数字准，但没有分析和建议）。'
      + (auth.isAdmin ? '点右上角「AI 设置」填一个 API Key 就能开。' : '让管理员在「AI 设置」里开启。')
  }
  return ''
})
const metaText = computed(() => {
  const d = current.value
  if (!d) return ''
  const src = d.source === 'AI' ? `AI 生成 · ${d.model || ''}` : '系统统计（未调用 AI）'
  return src + (d.tokens ? ` · ${d.tokens} tokens` : '') + ` · ${d.createdText || ''}`
})
const bodyText = computed(() => {
  const d = current.value
  if (!d) return ''
  return (d.content || '') + (d.source === 'AI' ? '\n\n（以上由 AI 归纳，数字以下方统计为准）' : '')
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
  } catch (e: any) { console.error('早报加载失败', e) }
}

function show(d: DigestEntry) {
  current.value = d
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function gen() {
  generating.value = true
  try {
    const r = await aiApi.generate()
    if (!r.ok) { ElMessage.error(r.message || '生成失败'); return }
    if (r.degraded) {
      ElMessageBox.alert('AI 调用失败，已退回本地统计：' + (r.error || '未知原因'),
        '已降级', { type: 'warning', confirmButtonText: '知道了' }).catch(() => {})
    }
    await load()
  } catch (e: any) {
    ElMessage.error('生成失败：' + (e?.message || ''))
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
