<template>
  <div class="ai-chat">
    <div class="head">
      <div>
        <h2>{{ t('ai.title') }}</h2>
        <span class="dim sub">{{ statusLine }}</span>
      </div>
      <div class="head-right">
        <el-tag v-if="info?.enabled" size="small" type="success" effect="plain">
          {{ info?.model || t('ai.enabled') }}
        </el-tag>
        <el-tag v-else size="small" type="info" effect="plain">{{ t('ai.notEnabled') }}</el-tag>
        <el-button v-if="messages.length" size="small" text @click="clearConv">{{ t('ai.clearConv') }}</el-button>
        <el-button v-if="auth.isAdmin" size="small" text @click="cfgOpen = true">{{ t('ai.settings') }}</el-button>
      </div>
    </div>

    <!-- 未启用 / 未配置：给引导，不给死页面 -->
    <el-card v-if="info && (!info.enabled || !info.configured)" shadow="never" class="guide">
      <div class="guide-body">
        <div class="g-title">{{ info.enabled ? t('ai.needKey') : t('ai.notOn') }}</div>
        <div class="g-desc">
          {{ t('ai.cfgHint1') }}
          <template v-if="!auth.isAdmin">{{ t('ai.askAdmin') }}</template>
        </div>
        <el-button v-if="auth.isAdmin" type="primary" @click="cfgOpen = true">{{ t('ai.goEnable') }}</el-button>
      </div>
    </el-card>

    <!-- 对话区 -->
    <el-card v-else shadow="never" class="conv">
      <div ref="listEl" class="msgs">
        <div v-if="!messages.length" class="empty">
          <div class="e-title">{{ t('ai.emptyTitle') }}</div>
          <div class="e-sub">{{ t('ai.emptySub') }}</div>
          <div class="caps">
            <div v-for="tool in info?.tools || []" :key="tool.name" class="cap">
              <b>{{ toolLabel(tool.name) }}</b>
              <span>{{ toolDesc(tool.name) }}</span>
            </div>
          </div>
        </div>
        <template v-for="(m, i) in messages" :key="i">
          <div class="row me" v-if="m.role === 'user'">
            <div class="bubble user">{{ m.content }}</div>
          </div>
          <div class="row ai" v-else>
            <div class="who">{{ t('ai.who') }}</div>
            <div class="bubble ai-b">
              <div class="mtext">{{ m.content }}</div>
            </div>
          </div>
        </template>
        <div v-if="pending" class="row ai">
          <div class="who">{{ t('ai.who') }}</div>
          <div class="bubble ai-b thinking">
            <span class="dots">{{ t('ai.querying') }}</span>
          </div>
        </div>
      </div>

      <div class="quick" v-if="!messages.length">
        <el-button v-for="q in quicks" :key="q" size="small" round @click="ask(q)">{{ q }}</el-button>
      </div>

      <div class="input">
        <el-input v-model="draft" type="textarea" :rows="2" resize="none"
          :placeholder="t('ai.inputPh')"
          @keydown.enter.exact.prevent="send" :disabled="pending" />
        <el-button type="primary" :loading="pending" @click="send">{{ t('ai.send') }}</el-button>
      </div>
      <div class="foot dim">{{ t('ai.foot') }}</div>
    </el-card>

    <AiConfigDialog v-model="cfgOpen" @saved="loadTools" />
  </div>
</template>

<script setup lang="ts">

import { computed, nextTick, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { chatApi, type ChatMessage, type ChatToolsResult } from '@/api/ai'
import { useAuthStore } from '@/stores/auth'
import AiConfigDialog from '@/components/ai/AiConfigDialog.vue'

const { t } = useI18n()

const auth = useAuthStore()
const info = ref<ChatToolsResult | null>(null)
const cfgOpen = ref(false)
const messages = ref<ChatMessage[]>([])
const draft = ref('')
const pending = ref(false)
const listEl = ref<HTMLElement | null>(null)

const statusLine = computed(() => {
  if (!info.value) return t('ai.stLoading')
  if (!info.value.enabled) return t('ai.stDisabled')
  if (!info.value.configured) return t('ai.stNoKey')
  return t('ai.stReady', { model: info.value.model, n: info.value.tools.length })
})

const TOOL_LABELS: Record<string, string> = {
  query_stock: 'ai.toolStock',
  query_lines: 'ai.toolLines',
  query_devices: 'ai.toolDevices',
  query_alarms: 'ai.toolAlarms',
  query_purchase_orders: 'ai.toolPurchase',
  query_sales_orders: 'ai.toolSales',
  query_finance: 'ai.toolFinance'
}
const TOOL_DESC: Record<string, string> = {
  query_stock: 'ai.toolDescStock',
  query_lines: 'ai.toolDescLines',
  query_devices: 'ai.toolDescDevices',
  query_alarms: 'ai.toolDescAlarms',
  query_purchase_orders: 'ai.toolDescPurchase',
  query_sales_orders: 'ai.toolDescSales',
  query_finance: 'ai.toolDescFinance'
}
/* 存 key、渲染时再 t()：语言切换要即时生效，不能在模块加载时就把中文定死 */
const toolLabel = (n: string) => (TOOL_LABELS[n] ? t(TOOL_LABELS[n]) : n)
const toolDesc = (n: string) => (TOOL_DESC[n] ? t(TOOL_DESC[n]) : '')

const quicks = computed(() => {
  const names = new Set((info.value?.tools || []).map((t) => t.name))
  const q: string[] = []
  if (names.has('query_stock')) q.push(t('ai.sugStock'))
  if (names.has('query_lines')) q.push(t('ai.sugLines'))
  if (names.has('query_devices')) q.push(t('ai.sugDevices'))
  if (names.has('query_alarms')) q.push(t('ai.sugAlarms'))
  if (names.has('query_purchase_orders')) q.push(t('ai.sugPurchase'))
  if (names.has('query_sales_orders')) q.push(t('ai.sugSales'))
  if (names.has('query_finance')) q.push(t('ai.sugFinance'))
  return q.slice(0, 4)
})

const stepsFor: Record<string, string> = {
  query_stock: 'ai.progStock', query_lines: 'ai.progLines', query_devices: 'ai.progDevices',
  query_alarms: 'ai.progAlarms', query_purchase_orders: 'ai.progPurchase', query_sales_orders: 'ai.progSales',
  query_finance: 'ai.progFinance'
}

async function loadTools() {
  try {
    info.value = await chatApi.tools()
  } catch {
    info.value = null
  }
}

/** 启动时接续本人最近的对话（存库，刷新/换设备不丢） */
async function loadHistory() {
  try {
    const r = await chatApi.history(30)
    if (r.ok && r.messages?.length && !messages.value.length) {
      messages.value = r.messages
      scrollBottom()
    }
  } catch {
    /* 历史拿不到就当新会话 */
  }
}

async function clearConv() {
  try {
    const r = await chatApi.clearHistory()
    if (r.ok) {
      messages.value = []
      ElMessage.success(r.message || t('ai.cleared'))
    }
  } catch (e: any) {
    ElMessage.error(e?.message || t('ai.clearFailed'))
  }
}

function scrollBottom() {
  nextTick(() => {
    if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
  })
}

function ask(q: string) {
  draft.value = q
  send()
}

async function send() {
  const text = draft.value.trim()
  if (!text || pending.value) return
  draft.value = ''
  messages.value.push({ role: 'user', content: text })
  scrollBottom()
  pending.value = true
  try {
    const r = await chatApi.send(messages.value.map((m) => ({ role: m.role, content: m.content })))
    if (r.ok && r.content) {
      const used = (r.tools || []).map((t) => TOOL_LABELS[t.name] || t.name)
      const suffix = used.length ? t('ai.dataFrom', { list: used.join(t('ai.sep')) }) : ''
      messages.value.push({ role: 'assistant', content: r.content + suffix })
    } else {
      messages.value.push({ role: 'assistant', content: r.message || t('ai.noAnswer') })
    }
  } catch (e: any) {
    ElMessage.error(e?.message || t('ai.callFailed'))
  } finally {
    pending.value = false
    scrollBottom()
  }
}

onMounted(() => {
  loadTools()
  loadHistory()
})
</script>

<style scoped>
.ai-chat { display: flex; flex-direction: column; gap: var(--space-4); height: 100%; }
.head { display: flex; align-items: center; justify-content: space-between; }
.head h2 { margin: 0; font-size: var(--text-lg); }
.head .sub { font-size: var(--text-xs); margin-left: 10px; }
.head-right { display: flex; align-items: center; gap: 10px; }
.guide :deep(.el-card__body) { padding: var(--space-6); }
.guide-body { text-align: center; display: flex; flex-direction: column; gap: var(--space-3); align-items: center; }
.g-title { font-size: var(--text-md); font-weight: 600; }
.g-desc { font-size: var(--text-xs); color: var(--muted); max-width: 560px; line-height: 1.8; }
.conv { display: flex; flex-direction: column; flex: 1; min-height: 0; }
.conv :deep(.el-card__body) { display: flex; flex-direction: column; gap: var(--space-3); flex: 1; min-height: 0; }
.msgs { flex: 1; min-height: 320px; overflow-y: auto; display: flex; flex-direction: column; gap: var(--space-3); padding-right: 4px; }
.empty { text-align: center; padding: var(--space-6) 0 var(--space-3); }
.e-title { font-size: var(--text-md); font-weight: 600; margin-bottom: 4px; }
.e-sub { font-size: var(--text-xs); color: var(--muted); margin-bottom: var(--space-3); }
.caps { display: flex; flex-wrap: wrap; gap: var(--space-2); justify-content: center; }
.cap { background: var(--surface-2); border: 1px solid var(--border-soft); border-radius: var(--radius-md);
  padding: 6px 12px; text-align: left; max-width: 250px; }
.cap b { display: block; font-size: var(--text-xs); }
.cap span { font-size: var(--text-2xs); color: var(--meta); }
.row { display: flex; flex-direction: column; }
.row.me { align-items: flex-end; }
.row.ai { align-items: flex-start; }
.who { font-size: var(--text-2xs); color: var(--meta); margin-bottom: 2px; }
.bubble { max-width: 76%; padding: 8px 12px; border-radius: 10px; font-size: var(--text-xs); line-height: 1.7; }
.bubble.user { background: var(--accent); color: #fff; border-bottom-right-radius: 2px; white-space: pre-wrap; }
.bubble.ai-b { background: var(--surface-2); border: 1px solid var(--border-soft); border-bottom-left-radius: 2px; white-space: pre-wrap; }
.thinking { display: flex; flex-direction: column; gap: 3px; }
.step { font-size: var(--text-2xs); color: var(--meta); }
.dots { font-size: var(--text-2xs); color: var(--muted); }
.quick { display: flex; gap: 8px; flex-wrap: wrap; }
.input { display: flex; gap: 10px; align-items: flex-end; }
.foot { font-size: var(--text-2xs); }
.dim { color: var(--muted); }
</style>
