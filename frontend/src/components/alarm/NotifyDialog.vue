<template>
  <el-dialog :model-value="modelValue" :title="t('alarmNotify.title')" width="720px"
             :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)"
             @open="open">
    <el-alert type="warning" :closable="false" class="edu">
      <template #title>
        <div class="edu-body">
          <b>{{ t('alarmNotify.eduTitle') }}</b><br>
          {{ t('alarmNotify.edu1') }}<br>
          1、<b>{{ t('alarmNotify.edu2a') }}</b>{{ t('alarmNotify.edu2b') }}<br>
          2、<b>{{ t('alarmNotify.edu3a') }}</b>{{ t('alarmNotify.edu3b') }}
        </div>
      </template>
    </el-alert>

    <el-checkbox v-model="cfg.enabled" class="en-check">
      {{ t('alarmNotify.enable') }}
    </el-checkbox>

    <div class="row">
      <div class="field">
        <label>{{ t('alarmNotify.quiet') }}</label>
        <el-input v-model="cfg.quietHours" :placeholder="t('alarmNotify.quietPh')" />
        <div class="hint">{{ t('alarmNotify.quietHint') }}</div>
      </div>
      <div class="field">
        <label>{{ t('alarmNotify.repeat') }}</label>
        <el-input v-model.number="cfg.repeatMinutes" type="number" min="0" placeholder="0" />
        <div class="hint">{{ t('alarmNotify.repeatHint') }}</div>
      </div>
    </div>

    <div class="sect">{{ t('alarmNotify.channels') }}</div>
    <div v-if="!channels.length" class="empty">{{ t('alarmNotify.empty') }}</div>
    <div v-for="(c, i) in channels" :key="i" class="ch-card">
      <div class="body">
        <div class="grid1">
          <el-select v-model="c.type" style="width:190px">
            <el-option v-for="k in CH_KEYS" :key="k" :label="t('alarmDict.chType.' + k)" :value="k" />
          </el-select>
          <el-input v-if="c.type !== 'LOG'" v-model="c.url" :placeholder="CH_TYPES[c.type]?.[1] || t('alarmDict.chUrlPh')" />
          <span v-else class="nolog">{{ t('alarmNotify.nolog') }}</span>
        </div>
        <div class="grid2">
          <el-input v-if="c.type === 'PUSHPLUS'" v-model="c.token" :placeholder="t('alarmNotify.tokenPh')" />
          <span v-else></span>
          <el-select v-model="c.minLevel" style="width:150px">
            <el-option v-for="o in MIN_LEVEL_KEYS" :key="o" :label="t('alarmDict.minLevel.' + o)" :value="o" />
          </el-select>
        </div>
        <div v-if="c.error" class="err">{{ c.error }}</div>
      </div>
      <el-button size="small" :loading="testing === i" @click="test(i)">{{ t('alarmNotify.test') }}</el-button>
      <el-button size="small" type="danger" plain @click="channels.splice(i, 1)">{{ t('alarmNotify.del') }}</el-button>
    </div>
    <el-button style="margin-top:4px" @click="addChannel">{{ t('alarmNotify.add') }}</el-button>
    <div class="hint msg" :class="{ ok: msgOk }">{{ msg }}</div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t('alarmNotify.cancel') }}</el-button>
      <el-button type="primary" @click="save">{{ t('alarmNotify.save') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  alarmsApi, CH_TYPES,
  type NotifyChannel
} from '@/api/alarms'

const { t } = useI18n()

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'changed'): void }>()

const CH_KEYS = ['WECOM_BOT', 'SERVERCHAN', 'PUSHPLUS', 'DINGTALK', 'FEISHU', 'WEBHOOK', 'LOG'] as const
const MIN_LEVEL_KEYS = ['CRITICAL', 'MAJOR', 'MINOR'] as const

const cfg = reactive({ enabled: false, quietHours: '', repeatMinutes: 0 })
const channels = ref<NotifyChannel[]>([])
const msg = ref('')
const msgOk = ref(false)
const testing = ref(-1)

watch(() => props.modelValue, (v) => { if (v) open() })

async function open() {
  msg.value = ''
  try {
    const r = await alarmsApi.notify()
    const c = r.config || {}
    cfg.enabled = !!c.enabled
    cfg.quietHours = c.quietHours || ''
    cfg.repeatMinutes = c.repeatMinutes || 0
    channels.value = (c.channels || []).map((x) => ({ ...x, error: null }))
  } catch (e: any) { ElMessage.error(e?.message || t('alarmNotify.loadFail')) }
}

function addChannel() {
  channels.value.push({
    id: null, name: '', type: 'WECOM_BOT', url: '', token: '',
    enabled: true, minLevel: 'MINOR'
  })
}

async function test(i: number) {
  const c = channels.value[i]
  if (!c) return
  testing.value = i
  msg.value = t('alarmNotify.sending'); msgOk.value = false
  try {
    const r = await alarmsApi.testChannel(c)
    msg.value = (r.ok ? t('alarmNotify.ok') : t('alarmNotify.fail')) + (r.message || '')
    msgOk.value = !!r.ok
  } catch (err: any) {
    msg.value = t('alarmNotify.reqFail') + (err?.message || ''); msgOk.value = false
  } finally { testing.value = -1 }
}

async function save() {
  try {
    const r = await alarmsApi.saveNotify({
      enabled: cfg.enabled,
      quietHours: cfg.quietHours.trim(),
      repeatMinutes: Number(cfg.repeatMinutes || 0),
      channels: channels.value
    })
    if (r && r.ok === false) {
      msg.value = t('alarmNotify.saveFail') + (r.message || t('alarmNotify.unknown')); msgOk.value = false
      return
    }
    msg.value = t('alarmNotify.saved') + (r.message || t('alarmNotify.savedOk')); msgOk.value = true
    emit('changed')
    window.setTimeout(() => emit('update:modelValue', false), 900)
  } catch (e: any) {
    msg.value = t('alarmNotify.saveFail') + (e?.message || t('alarmNotify.unknown')); msgOk.value = false
  }
}
</script>

<style scoped>
.edu { border-radius: var(--radius-md); margin-bottom: var(--space-4); }
.edu-body { font-size: var(--text-xs); line-height: 1.9; }
.en-check { margin-bottom: var(--space-4); }
.row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); margin-bottom: var(--space-4); }
.field { min-width: 0; }
.field label { display: block; font-size: var(--text-xs); color: var(--muted); margin-bottom: 4px; }
.hint { font-size: var(--text-2xs); color: var(--meta); line-height: 1.8; }
.sect { font-size: var(--text-xs); color: var(--muted); letter-spacing: .1em; margin-bottom: var(--space-2); }
.empty { color: var(--muted); font-size: var(--text-sm); padding: var(--space-4); text-align: center; }
.ch-card {
  display: flex; gap: var(--space-2); align-items: flex-start;
  background: var(--surface-2); border-radius: var(--radius-sm);
  padding: var(--space-3); margin-bottom: var(--space-2);
}
.ch-card .body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: var(--space-2); }
.grid1 { display: grid; grid-template-columns: 190px 1fr; gap: var(--space-2); align-items: center; }
.grid2 { display: grid; grid-template-columns: 1fr 150px; gap: var(--space-2); align-items: center; }
.nolog { font-size: var(--text-2xs); color: var(--muted); }
.err { font-size: var(--text-2xs); color: var(--alarm); }
.msg { margin-top: var(--space-3); }
.msg.ok { color: var(--run); }
</style>
