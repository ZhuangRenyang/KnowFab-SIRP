<template>
  <el-dialog :model-value="modelValue" :title="t('aiCfg.title')" width="640px"
             :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)"
             @open="open">
    <div class="hint lead">
      {{ t('aiCfg.lead') }}<b>{{ t('aiCfg.leadBold') }}</b>{{ t('aiCfg.leadTail') }}
    </div>

    <el-checkbox v-model="fm.enabled">{{ t('aiCfg.enable') }}</el-checkbox>

    <div class="field">
      <label>{{ t('aiCfg.baseUrl') }}</label>
      <el-input v-model="fm.baseUrl" placeholder="https://api.deepseek.com/v1" />
    </div>
    <div class="field">
      <label>{{ t('aiCfg.apiKey') }}</label>
      <el-input v-model="fm.apiKey" placeholder="sk-..." show-password />
    </div>
    <div class="row c2">
      <div class="field"><label>{{ t('aiCfg.model') }}</label>
        <el-input v-model="fm.model" placeholder="deepseek-chat" /></div>
      <div class="field"><label>{{ t('aiCfg.maxTokens') }}</label>
        <el-input v-model.number="fm.maxTokens" type="number" /></div>
    </div>
    <div class="row c3">
      <div class="field"><label>{{ t('aiCfg.hour') }}</label>
        <el-input v-model.number="fm.hour" type="number" min="0" max="23" /></div>
      <div class="field"><label>{{ t('aiCfg.minute') }}</label>
        <el-input v-model.number="fm.minute" type="number" min="0" max="59" /></div>
      <div class="field"><label>{{ t('aiCfg.temp') }}</label>
        <el-input v-model.number="fm.temperature" type="number" step="0.1" /></div>
    </div>
    <el-checkbox v-model="fm.pushToWechat">{{ t('aiCfg.push') }}</el-checkbox>

    <div class="field">
      <label>{{ t('aiCfg.prompt') }}</label>
      <el-input v-model="fm.systemPrompt" type="textarea" :rows="5" />
    </div>

    <div class="msg" :class="{ ok: msgOk }">{{ msg }}</div>

    <template #footer>
      <el-button :loading="testing" @click="test">{{ t('aiCfg.test') }}</el-button>
      <el-button @click="emit('update:modelValue', false)">{{ t('aiCfg.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ t('aiCfg.save') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { aiApi, type AiConfig } from '@/api/ai'

const { t } = useI18n()

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'saved'): void }>()

/*
 * 这里用 ref 而不是 reactive，是被 vue-tsc 3.3.14 逼的 —— 不是风格偏好。
 *
 * 换 vue-tsc 3.3.13 → 3.3.14 后，这个文件冒出 9 个
 * "Property 'value' does not exist on type '{enabled: boolean, ...}'"：
 * 模板里的 v-model="fm.baseUrl" 被它误判成需要 .value 的 ref。
 * 同一套其他依赖（vue 3.5.44 / element-plus 2.14.8）下，只换 vue-tsc 就 0 → 9，
 * 运行时代码本身是对的，属于类型检查的误报。
 *
 * 两个选择：退回 3.3.13（等于为了工具 bug 停住升级），或者改写法让它能推断对。
 * 选后者：ref 在模板里同样自动解包，模板一行不用改，只是脚本里要写 .value —— 
 * 比在模板里到处塞 as 断言干净。
 */
const fm = ref<AiConfig>({
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
    enabled: fm.value.enabled,
    baseUrl: fm.value.baseUrl.trim(),
    apiKey: fm.value.apiKey.trim(),
    model: fm.value.model.trim(),
    maxTokens: Number(fm.value.maxTokens || 700),
    temperature: Number(fm.value.temperature || 0.3),
    hour: Number(fm.value.hour || 7),
    minute: Number(fm.value.minute || 0),
    pushToWechat: fm.value.pushToWechat,
    systemPrompt: fm.value.systemPrompt
  }
}

async function open() {
  msg.value = ''
  try {
    const j = await aiApi.config()
    const c = j.config || {}
    Object.assign(fm.value, {
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
    msg.value = (j.warnings || []).join(' · ')
    msgOk.value = false
  } catch (e: any) { ElMessage.error(e?.message || t('aiCfg.loadFail')) }
}

async function save() {
  saving.value = true
  try {
    const r = await aiApi.saveConfig(readForm())
    msgOk.value = !(r && r.ok === false)
    const ws = r.warnings || []
    msg.value = (r.message || '') + (ws.length ? ' · ' + ws.join(' · ') : '')
    if (!r || r.ok !== false) {
      emit('saved')
      window.setTimeout(() => emit('update:modelValue', false), 800)
    }
  } catch (e: any) {
    msg.value = t('aiCfg.saveFail') + (e?.message || ''); msgOk.value = false
  } finally { saving.value = false }
}

async function test() {
  testing.value = true
  msg.value = t('aiCfg.calling'); msgOk.value = false
  try {
    /* 先保存再测，否则测的是旧配置，客户会以为新 key 不行（旧版语义逐字保留） */
    await aiApi.saveConfig(readForm())
    const r = await aiApi.test()
    msgOk.value = !!r.ok
    msg.value = (r.message || '') + (r.tokens ? ` (${r.tokens} tokens)` : '')
  } catch (e: any) {
    msg.value = t('aiCfg.callFail') + (e?.message || ''); msgOk.value = false
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
