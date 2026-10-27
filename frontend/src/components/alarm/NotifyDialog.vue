<template>
  <el-dialog :model-value="modelValue" title="告警通知设置" width="720px"
             :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)"
             @open="open">
    <el-alert type="warning" :closable="false" class="edu">
      <template #title>
        <div class="edu-body">
          <b>先看这段，能省你半天。</b><br>
          个人微信没有官方推送接口，任何号称「直接推个人微信」的都是第三方。两个靠谱办法：<br>
          1、<b>企业微信群机器人</b>（推荐）：微信里建个群 → 群设置 → 群机器人 → 添加 → 复制地址粘到下面。
          免费、不限条数、延迟 1 秒内，工厂都用这个。<br>
          2、<b>Server酱 / PushPlus</b>：想推到自己的个人微信就用这个，注册后拿一个地址填进来，有免费额度。
        </div>
      </template>
    </el-alert>

    <el-checkbox v-model="cfg.enabled" class="en-check">
      启用通知（关掉后告警照常记录，只是不发到手机）
    </el-checkbox>

    <div class="row">
      <div class="field">
        <label>免打扰时段</label>
        <el-input v-model="cfg.quietHours" placeholder="留空 = 不启用，如 22:00-07:00" />
        <div class="hint">这段时间只有「严重」级别会推出去</div>
      </div>
      <div class="field">
        <label>重复提醒间隔（分钟）</label>
        <el-input v-model.number="cfg.repeatMinutes" type="number" min="0" placeholder="0" />
        <div class="hint">0 = 只提醒一次。设备一直没修好建议填 120</div>
      </div>
    </div>

    <div class="sect">推送渠道</div>
    <div v-if="!channels.length" class="empty">还没有渠道。点下面「添加渠道」。</div>
    <div v-for="(c, i) in channels" :key="i" class="ch-card">
      <div class="body">
        <div class="grid1">
          <el-select v-model="c.type" style="width:190px">
            <el-option v-for="(m, k) in CH_TYPES" :key="k" :label="m[0]" :value="k" />
          </el-select>
          <el-input v-if="c.type !== 'LOG'" v-model="c.url" :placeholder="CH_TYPES[c.type]?.[1] || '地址'" />
          <span v-else class="nolog">不发送，只写进日志文件</span>
        </div>
        <div class="grid2">
          <el-input v-if="c.type === 'PUSHPLUS'" v-model="c.token" placeholder="PushPlus token" />
          <span v-else></span>
          <el-select v-model="c.minLevel" style="width:150px">
            <el-option v-for="o in MIN_LEVEL_OPTS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </div>
        <div v-if="c.error" class="err">{{ c.error }}</div>
      </div>
      <el-button size="small" :loading="testing === i" @click="test(i)">测试</el-button>
      <el-button size="small" type="danger" plain @click="channels.splice(i, 1)">删除</el-button>
    </div>
    <el-button style="margin-top:4px" @click="addChannel">+ 添加渠道</el-button>
    <div class="hint msg" :class="{ ok: msgOk }">{{ msg }}</div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  alarmsApi, CH_TYPES, MIN_LEVEL_OPTS,
  type NotifyChannel
} from '@/api/alarms'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'changed'): void }>()

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
  } catch (e: any) { ElMessage.error(e?.message || '通知配置加载失败') }
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
  msg.value = '正在发送…'; msgOk.value = false
  try {
    const r = await alarmsApi.testChannel(c)
    msg.value = (r.ok ? '成功：' : '失败：') + (r.message || '')
    msgOk.value = !!r.ok
  } catch (err: any) {
    msg.value = '请求失败：' + (err?.message || ''); msgOk.value = false
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
      msg.value = '保存失败：' + (r.message || '未知原因'); msgOk.value = false
      return
    }
    msg.value = '已保存：' + (r.message || '生效'); msgOk.value = true
    emit('changed')
    window.setTimeout(() => emit('update:modelValue', false), 900)
  } catch (e: any) {
    msg.value = '保存失败：' + (e?.message || '未知原因'); msgOk.value = false
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
