<template>
  <el-dialog :model-value="modelValue" :title="t('alarmRule.title')" width="800px"
             :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)"
             @open="open">
    <!-- 列表视图 -->
    <div v-if="view === 'list'">
      <div class="hint lead">{{ t('alarmRule.lead') }}</div>
      <div v-if="!rules.length" class="empty">{{ t('alarmRule.empty') }}</div>
      <div v-for="x in rules" :key="x.rule.id" class="rule-card" :class="{ off: !x.rule.enabled }">
        <div class="body">
          <div class="rn">{{ x.rule.name }}
            <span class="tag" :class="'lv-' + x.rule.level.toLowerCase()">{{ t('alarmDict.lv.' + x.rule.level.toLowerCase()) }}</span>
          </div>
          <div class="rd">{{ ruleDesc(x.rule, t) }}</div>
          <div v-if="x.error" class="err">{{ t('alarmRule.cfgErr', { err: x.error }) }}</div>
        </div>
        <el-button size="small" @click="toggle(x.rule, !x.rule.enabled)">{{ x.rule.enabled ? t('alarmRule.disable') : t('alarmRule.enable') }}</el-button>
        <el-button size="small" @click="edit(x.rule)">{{ t('alarmRule.edit') }}</el-button>
        <el-button size="small" type="danger" plain @click="remove(x.rule)">{{ t('alarmRule.del') }}</el-button>
      </div>
    </div>

    <!-- 编辑视图 -->
    <div v-else class="edit">
      <div class="row">
        <div class="field">
          <label>{{ t('alarmRule.name') }}</label>
          <el-input v-model="fm.name" :placeholder="t('alarmRule.namePh')" />
        </div>
        <div class="field">
          <label>{{ t('alarmRule.type') }}</label>
          <el-select v-model="fm.type">
            <el-option v-for="k in RULE_TYPE_KEYS" :key="k" :label="t('alarmDict.ruleType.' + k)" :value="k" />
          </el-select>
        </div>
      </div>

      <div class="row">
        <div class="field">
          <label>{{ t('alarmRule.devices') }}</label>
          <el-input v-model="fm.deviceCodes" :placeholder="t('alarmRule.devicesPh')" />
          <div class="hint">{{ t('alarmRule.devicesHint') }}</div>
        </div>
        <div class="field">
          <label>{{ t('alarmRule.level') }}</label>
          <el-select v-model="fm.level">
            <el-option value="CRITICAL" :label="t('alarmRule.lvCritical')" />
            <el-option value="MAJOR" :label="t('alarmRule.lvMajor')" />
            <el-option value="MINOR" :label="t('alarmRule.lvMinor')" />
          </el-select>
        </div>
      </div>

      <div v-if="showPoint" class="row">
        <div class="field">
          <label>{{ t('alarmRule.point') }}</label>
          <el-input v-model="fm.pointKey" :placeholder="t('alarmRule.pointPh')" />
          <div class="hint">{{ pointHint }}</div>
        </div>
        <div v-if="showThreshold" class="field">
          <label>{{ t('alarmRule.threshold') }}</label>
          <div class="cond">
            <el-select v-if="fm.type === 'THRESHOLD'" v-model="fm.op" style="width:86px">
              <el-option v-for="o in OPS" :key="o.v" :label="o.l" :value="o.v" />
            </el-select>
            <el-input v-model="thresholdText" :placeholder="fm.type === 'STOP' ? t('alarmRule.stopPh') : t('alarmRule.thresholdPh')" style="flex:1" />
          </div>
          <div class="hint">{{ thresholdHint }}</div>
        </div>
      </div>

      <div class="row">
        <div class="field">
          <label>{{ t('alarmRule.forSec') }}</label>
          <el-input v-model.number="fm.forSeconds" type="number" min="0" />
          <div class="hint">{{ t('alarmRule.forHint', { dur: fmtDur((fm.forSeconds || 0) * 1000, t) }) }}</div>
        </div>
        <div class="field">
          <label>{{ t('alarmRule.recoverSec') }}</label>
          <el-input v-model.number="fm.recoverSeconds" type="number" min="0" />
          <div class="hint">{{ t('alarmRule.recoverHint') }}</div>
        </div>
      </div>

      <div class="row">
        <div class="field">
          <label>{{ t('alarmRule.cooldown') }}</label>
          <el-input v-model.number="fm.cooldownSeconds" type="number" min="0" />
          <div class="hint">{{ t('alarmRule.cooldownHint') }}</div>
        </div>
        <div class="field">
          <label>{{ t('alarmRule.schedule') }}</label>
          <el-input v-model="fm.schedule" :placeholder="t('alarmRule.schedulePh')" />
          <div class="hint">{{ t('alarmRule.scheduleHint') }}</div>
        </div>
      </div>

      <div class="field wide">
        <label>{{ t('alarmRule.days') }}</label>
        <div class="days">
          <el-checkbox v-for="(n, i) in dayNames" :key="n" v-model="daySel[i]" :label="n" :value="String(i + 1)" border size="small" />
        </div>
        <div class="hint">{{ t('alarmRule.daysHint') }}<b>{{ t('alarmRule.daysHintMust') }}</b></div>
      </div>

      <div class="field wide">
        <label>{{ t('alarmRule.msgTpl') }}</label>
        <el-input v-model="fm.message" :placeholder="t('alarmRule.msgPh')" />
        <div class="hint">{{ t('alarmRule.msgHint') }}</div>
      </div>

      <div class="checks">
        <el-checkbox v-model="fm.notify">{{ t('alarmRule.notify') }}</el-checkbox>
        <el-checkbox v-model="fm.enabled">{{ t('alarmRule.enableRule') }}</el-checkbox>
      </div>
    </div>

    <template #footer>
      <template v-if="view === 'list'">
        <el-button @click="emit('update:modelValue', false)">{{ t('alarmRule.close') }}</el-button>
        <el-button type="primary" @click="create">{{ t('alarmRule.create') }}</el-button>
      </template>
      <template v-else>
        <el-button @click="emit('update:modelValue', false)">{{ t('alarmRule.close') }}</el-button>
        <el-button @click="view = 'list'">{{ t('alarmRule.back') }}</el-button>
        <el-button type="primary" @click="save">{{ t('alarmRule.save') }}</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  alarmsApi, newRule, ruleDesc, fmtDur,
  type AlarmRule
} from '@/api/alarms'

const { t } = useI18n()

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'changed'): void }>()

const view = ref<'list' | 'edit'>('list')
const rules = ref<{ rule: AlarmRule; error?: string }[]>([])
const fm = reactive<AlarmRule>(newRule())
const daySel = ref<boolean[]>([false, false, false, false, false, false, false])
const editingId = ref<string | null>(null)
const thresholdText = ref('')

const RULE_TYPE_KEYS = ['OFFLINE', 'STOP', 'FLAG', 'THRESHOLD'] as const

const OPS = [
  { v: '>', l: '>' }, { v: '>=', l: '≥' }, { v: '<', l: '<' },
  { v: '<=', l: '≤' }, { v: '==', l: '=' }, { v: '!=', l: '≠' }
]

const dayNames = computed(() => t('alarmRule.dayNames').split('|'))

const showPoint = computed(() => fm.type === 'THRESHOLD' || fm.type === 'FLAG')
const showThreshold = computed(() => fm.type === 'STOP' || fm.type === 'THRESHOLD')
const pointHint = computed(() =>
  fm.type === 'FLAG' ? t('alarmRule.pointHintFlag') : t('alarmRule.pointHintOther'))
const thresholdHint = computed(() =>
  fm.type === 'STOP' ? t('alarmRule.stopHint') : t('alarmRule.thresholdHint'))

watch(() => props.modelValue, (v) => { if (v) open() })

async function open() {
  view.value = 'list'
  try {
    const r = await alarmsApi.rules()
    rules.value = r.items || []
  } catch (e: any) { ElMessage.error(e?.message || t('alarmRule.loadFail')) }
}

function fill(r: AlarmRule) {
  Object.assign(fm, JSON.parse(JSON.stringify(r)))
  editingId.value = r.id ?? null
  const set = new Set(String(r.days || '').split(',').map((s) => s.trim()))
  daySel.value = dayNames.value.map((_, i) => set.has(String(i + 1)))
  thresholdText.value = r.threshold === null || r.threshold === undefined ? '' : String(r.threshold)
}

function create() {
  fill(newRule())
  view.value = 'edit'
}

function edit(r: AlarmRule) {
  fill(r)
  view.value = 'edit'
}

function readForm(): AlarmRule {
  const r: AlarmRule = { ...fm, id: editingId.value }
  r.name = fm.name.trim()
  r.deviceCodes = fm.deviceCodes.trim()
  r.pointKey = showPoint.value ? fm.pointKey.trim() : ''
  r.schedule = fm.schedule.trim()
  r.message = fm.message.trim()
  r.days = daySel.value.map((on, i) => (on ? String(i + 1) : '')).filter(Boolean).join(',')
  const th = thresholdText.value.trim()
  if (fm.type === 'STOP') r.threshold = th === '' ? 0 : Number(th)
  else if (fm.type === 'THRESHOLD') r.threshold = th === '' ? null : Number(th)
  else r.threshold = null
  for (const k of ['forSeconds', 'recoverSeconds', 'cooldownSeconds'] as const) {
    const v = Number(fm[k])
    r[k] = Number.isFinite(v) && v >= 0 ? Math.round(v) : 0
  }
  return r
}

async function save() {
  const r = readForm()
  if (!r.name) { ElMessage.warning(t('alarmRule.nameRequired')); return }
  if (r.type === 'THRESHOLD' && (r.threshold === null || !Number.isFinite(r.threshold))) {
    ElMessage.warning(t('alarmRule.thresholdRequired')); return
  }
  try {
    const res = await alarmsApi.saveRule(r)
    if (res && res.ok === false) { ElMessage.error(res.message || t('alarmRule.saveFail')); return }
    view.value = 'list'
    await open()
    emit('changed')
  } catch (e: any) { ElMessage.error(e?.message || t('alarmRule.saveFail')) }
}

async function toggle(rule: AlarmRule, enabled: boolean) {
  try {
    const copy: AlarmRule = JSON.parse(JSON.stringify(rule))
    copy.enabled = enabled
    const res = await alarmsApi.saveRule(copy)
    if (res && res.ok === false) { ElMessage.error(res.message || t('alarmRule.opFail')); return }
    await open()
    emit('changed')
  } catch (e: any) { ElMessage.error(e?.message || t('alarmRule.opFail')) }
}

async function remove(rule: AlarmRule) {
  try {
    await ElMessageBox.confirm(t('alarmRule.delConfirm', { name: rule.name }),
      t('alarmRule.delTitle'), { type: 'warning', confirmButtonText: t('alarmRule.del'), cancelButtonText: t('alarmRule.cancel') })
  } catch { return }
  try {
    await alarmsApi.deleteRule(String(rule.id))
    await open()
    emit('changed')
  } catch (e: any) { ElMessage.error(e?.message || t('alarmRule.delFail')) }
}
</script>

<style scoped>
.hint { font-size: var(--text-2xs); color: var(--meta); line-height: 1.8; }
.hint.lead { margin-bottom: var(--space-3); }
.empty { color: var(--muted); font-size: var(--text-sm); padding: var(--space-5); text-align: center; }
.rule-card {
  display: flex; gap: var(--space-2); align-items: center;
  background: var(--surface-2); border-radius: var(--radius-sm);
  padding: var(--space-3); margin-bottom: var(--space-2);
}
.rule-card.off { opacity: .5; }
.rule-card .body { flex: 1; min-width: 0; }
.rn { font-size: var(--text-sm); font-weight: 600; }
.rd { font-size: var(--text-xs); color: var(--muted); margin-top: 2px; }
.err { font-size: var(--text-2xs); color: var(--alarm); margin-top: 2px; }
.tag { padding: 1px 7px; border-radius: var(--radius-pill); font-size: var(--text-2xs); margin-left: 6px; }
.lv-critical { color: var(--alarm); background: var(--alarm-soft, rgba(255, 90, 80, .11)); }
.lv-major { color: var(--idle); background: var(--idle-soft, rgba(234, 179, 8, .13)); }
.lv-minor { color: var(--stop); background: var(--stop-soft, rgba(100, 116, 139, .14)); }
.edit .row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); }
.edit .field { margin-bottom: var(--space-3); min-width: 0; }
.edit .field.wide { grid-column: 1 / -1; }
.edit label { display: block; font-size: var(--text-xs); color: var(--muted); margin-bottom: 4px; }
.cond { display: flex; gap: var(--space-2); }
.days { display: flex; gap: var(--space-2); flex-wrap: wrap; }
.checks { display: flex; gap: var(--space-5); margin-top: var(--space-2); }
</style>
