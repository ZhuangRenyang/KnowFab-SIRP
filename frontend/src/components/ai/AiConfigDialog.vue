<template>
  <el-dialog :model-value="modelValue" title="AI 早报设置" width="640px"
             :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)"
             @open="open">
    <div class="hint lead">
      用 OpenAI 兼容接口（DeepSeek / 通义千问 / 智谱 / Moonshot / 本地 Ollama 都行）。
      <b>没配也能用</b>：早报会退成本地统计模板，只是没有分析和建议。
    </div>

    <el-checkbox v-model="fm.enabled">启用 AI 润色</el-checkbox>

    <div class="field">
      <label>接口地址（到 /v1 为止）</label>
      <el-input v-model="fm.baseUrl" placeholder="https://api.deepseek.com/v1" />
    </div>
    <div class="field">
      <label>API Key</label>
      <el-input v-model="fm.apiKey" placeholder="sk-..." show-password />
    </div>
    <div class="row c2">
      <div class="field"><label>模型</label>
        <el-input v-model="fm.model" placeholder="deepseek-chat" /></div>
      <div class="field"><label>最大输出 tokens</label>
        <el-input v-model.number="fm.maxTokens" type="number" /></div>
    </div>
    <div class="row c3">
      <div class="field"><label>每天几点</label>
        <el-input v-model.number="fm.hour" type="number" min="0" max="23" /></div>
      <div class="field"><label>几分</label>
        <el-input v-model.number="fm.minute" type="number" min="0" max="59" /></div>
      <div class="field"><label>温度</label>
        <el-input v-model.number="fm.temperature" type="number" step="0.1" /></div>
    </div>
    <el-checkbox v-model="fm.pushToWechat">生成后推到企业微信/钉钉（复用报警通知渠道）</el-checkbox>

    <div class="field">
      <label>提示词（留空用内置的）</label>
      <el-input v-model="fm.systemPrompt" type="textarea" :rows="5" />
    </div>

    <div class="msg" :class="{ ok: msgOk }">{{ msg }}</div>

    <template #footer>
      <el-button :loading="testing" @click="test">测试连接</el-button>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { aiApi, type AiConfig } from '@/api/ai'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'saved'): void }>()

const fm = reactive<AiConfig>({
  enabled: false, baseUrl: '', apiKey: '', model: '', maxTokens: 700,
  hour: 7, minute: 0, temperature: 0.3, pushToWechat: true, systemPrompt: ''
})
const msg = ref('')
const msgOk = ref(false)
const testing = ref(false)
const saving = ref(false)

watch(() => props.modelValue, (v) => { if (v) open() })

function readForm(): AiConfig {
  return {
    enabled: fm.enabled,
    baseUrl: fm.baseUrl.trim(),
    apiKey: fm.apiKey.trim(),
    model: fm.model.trim(),
    maxTokens: Number(fm.maxTokens || 700),
    temperature: Number(fm.temperature || 0.3),
    hour: Number(fm.hour || 7),
    minute: Number(fm.minute || 0),
    pushToWechat: fm.pushToWechat,
    systemPrompt: fm.systemPrompt
  }
}

async function open() {
  msg.value = ''
  try {
    const j = await aiApi.config()
    const c = j.config || {}
    Object.assign(fm, {
      enabled: !!c.enabled,
      baseUrl: c.baseUrl || '',
      apiKey: c.apiKey || '',
      model: c.model || '',
      maxTokens: c.maxTokens || 700,
      hour: c.hour == null ? 7 : c.hour,
      minute: c.minute == null ? 0 : c.minute,
      temperature: c.temperature == null ? 0.3 : c.temperature,
      pushToWechat: c.pushToWechat !== false,
      systemPrompt: c.systemPrompt || ''
    })
    msg.value = (j.warnings || []).join('；')
    msgOk.value = false
  } catch (e: any) { ElMessage.error(e?.message || 'AI 配置加载失败') }
}

async function save() {
  saving.value = true
  try {
    const r = await aiApi.saveConfig(readForm())
    msgOk.value = !(r && r.ok === false)
    msg.value = (r.message || '') + ((r.warnings || []).length ? '；' + r.warnings.join('；') : '')
    if (!r || r.ok !== false) {
      emit('saved')
      window.setTimeout(() => emit('update:modelValue', false), 800)
    }
  } catch (e: any) {
    msg.value = '保存失败：' + (e?.message || ''); msgOk.value = false
  } finally { saving.value = false }
}

async function test() {
  testing.value = true
  msg.value = '正在调用模型…'; msgOk.value = false
  try {
    /* 先保存再测，否则测的是旧配置，客户会以为新 key 不行（旧版语义逐字保留） */
    await aiApi.saveConfig(readForm())
    const r = await aiApi.test()
    msgOk.value = !!r.ok
    msg.value = (r.message || '') + (r.tokens ? `（${r.tokens} tokens）` : '')
  } catch (e: any) {
    msg.value = '调用失败：' + (e?.message || ''); msgOk.value = false
  } finally { testing.value = false }
}
</script>

<style scoped>
.hint { font-size: var(--text-2xs); color: var(--meta); line-height: 1.8; }
.hint.lead { margin-bottom: var(--space-3); }
.field { margin: var(--space-3) 0; }
.field label { display: block; font-size: var(--text-xs); color: var(--muted); margin-bottom: 4px; }
.row { display: grid; gap: var(--space-3); }
.row.c2 { grid-template-columns: 1fr 1fr; }
.row.c3 { grid-template-columns: 1fr 1fr 1fr; }
.checks { margin: var(--space-2) 0 var(--space-3); }
.msg { font-size: var(--text-xs); line-height: 1.8; min-height: 18px; color: var(--muted); }
.msg.ok { color: var(--run); }
</style>
