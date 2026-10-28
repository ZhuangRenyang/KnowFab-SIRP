<template>
  <div class="ai-chat">
    <div class="head">
      <div>
        <h2>AI 助手</h2>
        <span class="dim sub">{{ statusLine }}</span>
      </div>
      <div class="head-right">
        <el-tag v-if="info?.enabled" size="small" type="success" effect="plain">
          {{ info?.model || '已启用' }}
        </el-tag>
        <el-tag v-else size="small" type="info" effect="plain">AI 未启用</el-tag>
        <el-button v-if="messages.length" size="small" text @click="clearConv">清空对话</el-button>
        <el-button v-if="auth.isAdmin" size="small" text @click="cfgOpen = true">AI 设置</el-button>
      </div>
    </div>

    <!-- 未启用 / 未配置：给引导，不给死页面 -->
    <el-card v-if="info && (!info.enabled || !info.configured)" shadow="never" class="guide">
      <div class="guide-body">
        <div class="g-title">{{ info.enabled ? '差一步：还没填模型服务的 API Key' : 'AI 助手还没开启' }}</div>
        <div class="g-desc">
          AI 助手和「AI 早报」共用一套配置：支持 DeepSeek / 通义千问 / 智谱 / Moonshot 等
          OpenAI 兼容接口，也支持本地 Ollama（地址填 http://127.0.0.1:11434/v1，Key 随便填一位）。
          <template v-if="!auth.isAdmin">请联系管理员在「AI 设置」里开启。</template>
        </div>
        <el-button v-if="auth.isAdmin" type="primary" @click="cfgOpen = true">去开启 AI</el-button>
      </div>
    </el-card>

    <!-- 对话区 -->
    <el-card v-else shadow="never" class="conv">
      <div ref="listEl" class="msgs">
        <div v-if="!messages.length" class="empty">
          <div class="e-title">问我任何系统里的事</div>
          <div class="e-sub">我会调用系统里现成的查询拿真实数据，不编数字。你的角色能用：</div>
          <div class="caps">
            <div v-for="t in info?.tools || []" :key="t.name" class="cap">
              <b>{{ toolLabel(t.name) }}</b>
              <span>{{ toolDesc(t.name) }}</span>
            </div>
          </div>
        </div>
        <template v-for="(m, i) in messages" :key="i">
          <div class="row me" v-if="m.role === 'user'">
            <div class="bubble user">{{ m.content }}</div>
          </div>
          <div class="row ai" v-else>
            <div class="who">AI 助手</div>
            <div class="bubble ai-b">
              <div class="mtext">{{ m.content }}</div>
            </div>
          </div>
        </template>
        <div v-if="pending" class="row ai">
          <div class="who">AI 助手</div>
          <div class="bubble ai-b thinking">
            <span class="dots">正在查询系统数据，可能要几秒…</span>
          </div>
        </div>
      </div>

      <div class="quick" v-if="!messages.length">
        <el-button v-for="q in quicks" :key="q" size="small" round @click="ask(q)">{{ q }}</el-button>
      </div>

      <div class="input">
        <el-input v-model="draft" type="textarea" :rows="2" resize="none"
          placeholder="例如：现在还有多少可承诺的法兰盘？今天产线产量怎么样？"
          @keydown.enter.exact.prevent="send" :disabled="pending" />
        <el-button type="primary" :loading="pending" @click="send">发送</el-button>
      </div>
      <div class="foot dim">AI 回答的数字全部来自系统实时查询；涉密数据按你的角色自动过滤。</div>
    </el-card>

    <AiConfigDialog v-model="cfgOpen" @saved="loadTools" />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { chatApi, type ChatMessage, type ChatToolsResult } from '@/api/ai'
import { useAuthStore } from '@/stores/auth'
import AiConfigDialog from '@/components/ai/AiConfigDialog.vue'

const auth = useAuthStore()
const info = ref<ChatToolsResult | null>(null)
const cfgOpen = ref(false)
const messages = ref<ChatMessage[]>([])
const draft = ref('')
const pending = ref(false)
const listEl = ref<HTMLElement | null>(null)

const statusLine = computed(() => {
  if (!info.value) return '加载中…'
  if (!info.value.enabled) return '未启用'
  if (!info.value.configured) return '已启用 · 未配置 Key'
  return `模型 ${info.value.model} · 你可以查 ${info.value.tools.length} 类数据`
})

const TOOL_LABELS: Record<string, string> = {
  query_stock: '库存与可承诺',
  query_lines: '产线产量',
  query_devices: '设备状态',
  query_alarms: '报警汇总',
  query_purchase_orders: '采购订单',
  query_sales_orders: '销售订单',
  query_finance: '应收应付'
}
const TOOL_DESC: Record<string, string> = {
  query_stock: '现存量、订单占用、可承诺量、所在仓库',
  query_lines: '各产线今日/本月产量、在线设备数',
  query_devices: '设备清单与在线状态',
  query_alarms: '未处理报警按级别汇总',
  query_purchase_orders: '本月采购额、待收货、逾期',
  query_sales_orders: '本月销售额、待发货、逾期',
  query_finance: '应收未收、应付未付、本月收付款、账龄'
}
const toolLabel = (n: string) => TOOL_LABELS[n] || n
const toolDesc = (n: string) => TOOL_DESC[n] || ''

const quicks = computed(() => {
  const names = new Set((info.value?.tools || []).map((t) => t.name))
  const q: string[] = []
  if (names.has('query_stock')) q.push('现在哪些商品可承诺量低于安全库存？')
  if (names.has('query_lines')) q.push('今天各产线产量怎么样？')
  if (names.has('query_devices')) q.push('现在有几台设备离线？')
  if (names.has('query_alarms')) q.push('现在有多少未处理报警？')
  if (names.has('query_purchase_orders')) q.push('本月采购花了多少钱？有几单逾期未到？')
  if (names.has('query_sales_orders')) q.push('本月销售额多少？有几单还没发完？')
  if (names.has('query_finance')) q.push('应收还有多少没收回来？')
  return q.slice(0, 4)
})

const stepsFor: Record<string, string> = {
  query_stock: '查库存…', query_lines: '查产线产量…', query_devices: '查设备…',
  query_alarms: '查报警…', query_purchase_orders: '查采购…', query_sales_orders: '查销售…',
  query_finance: '查财务…'
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
      ElMessage.success(r.message || '已清空')
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '清空失败')
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
      const suffix = used.length ? `\n\n（数据来自：${used.join('、')}）` : ''
      messages.value.push({ role: 'assistant', content: r.content + suffix })
    } else {
      messages.value.push({ role: 'assistant', content: r.message || 'AI 暂时答不上来，稍后再试' })
    }
  } catch (e: any) {
    ElMessage.error(e?.message || 'AI 调用失败')
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
