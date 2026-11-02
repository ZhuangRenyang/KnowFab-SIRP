<template>
  <div class="edit">
    <div class="row">
      <div class="field">
        <label>{{ t('alarmRule.name') }}</label>
        <el-input v-model="fm.name" :placeholder="t('alarmRule.namePh')" />
      </div>
      <div class="field">
        <label>{{ t('alarmRule.type') }}</label>
        <el-select v-model="fm.type">
          <el-option v-for="k in RULE_TYPE_KEYS" :key="k" :label="t('alarmDict.ruleType.' + k.toLowerCase())" :value="k" />
        </el-select>
      </div>
    </div>

    <div class="row">
      <div class="field">
        <label>{{ t('alarmRule.devices') }}</label>
        <el-input v-model="fm.deviceCodes" :placeholder="t('alarmRule.devicesPh')" />
        <div class="hint">{{ t('alarmRule.devicesHint') }}</div>
      </div>
      <!-- 分级限值的等级在下面逐档选，这里只管其他类型的规则 -->
      <div v-if="fm.type !== 'THRESHOLD'" class="field">
        <label>{{ t('alarmRule.level') }}</label>
        <el-select v-model="fm.level">
          <el-option v-for="lv in LEVEL_ORDER" :key="lv" :value="lv" :label="t('alarmDict.lv.' + lv.toLowerCase())" />
        </el-select>
        <div class="hint">{{ t('alarmRule.levelHint') }}</div>
      </div>
    </div>

    <div v-if="showPoint" class="row">
      <div class="field">
        <label>{{ t('alarmRule.point') }}</label>
        <el-input v-model="fm.pointKey" :placeholder="t('alarmRule.pointPh')" />
        <div class="hint">{{ pointHint }}</div>
      </div>
      <div v-if="showThreshold" class="field">
        <label>{{ fm.type === 'THRESHOLD' ? t('alarmRule.tiers') : t('alarmRule.threshold') }}</label>
        <div v-if="fm.type === 'THRESHOLD'" class="cond">
          <el-select v-model="fm.op" style="width:96px">
            <el-option v-for="o in OPS" :key="o.v" :label="o.l" :value="o.v" />
          </el-select>
          <span class="hint">{{ t('alarmRule.opHint') }}</span>
        </div>
        <TierEditor v-if="fm.type === 'THRESHOLD'" v-model="tiers" :unit="unit" />
        <div v-else class="cond">
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
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { fmtDur } from '@/api/alarms'
import type { AlarmRule } from '@/api/alarms'
import {
  nextTierRowId, sortTierRows, validateTierRows, ruleTiers, unitOfPoint, LEVEL_ORDER, LEVEL_RANK,
} from '@/utils/alarmTier'
import type { TierRow } from '@/utils/alarmTier'
import { useDevicesStore } from '@/stores/devices'
import TierEditor from '@/components/alarm/TierEditor.vue'

const { t } = useI18n()

const props = defineProps<{ rule: AlarmRule }>()
const emit = defineEmits<{ (e: 'save', rule: AlarmRule): void }>()

const RULE_TYPE_KEYS = ['OFFLINE', 'STOP', 'FLAG', 'THRESHOLD'] as const

const OPS = [
  { v: '>', l: '>' }, { v: '>=', l: '≥' }, { v: '<', l: '<' },
  { v: '<=', l: '≤' }, { v: '==', l: '=' }, { v: '!=', l: '≠' }
]

const devices = useDevicesStore()

const fm = reactive<AlarmRule>({ ...props.rule })
const daySel = ref<boolean[]>([false, false, false, false, false, false, false])
const thresholdText = ref('')
/** 表单里的档位（阈值是字符串，见 TierRow 的说明） */
const tiers = ref<TierRow[]>([])

const dayNames = computed(() => t('alarmRule.dayNames').split('|'))

const showPoint = computed(() => fm.type === 'THRESHOLD' || fm.type === 'FLAG')
const showThreshold = computed(() => fm.type === 'STOP' || fm.type === 'THRESHOLD')
const pointHint = computed(() =>
  fm.type === 'FLAG' ? t('alarmRule.pointHintFlag') : t('alarmRule.pointHintOther'))
const thresholdHint = computed(() => {
  if (fm.type === 'STOP') return t('alarmRule.stopHint')
  if (fm.type === 'THRESHOLD') return t('alarmRule.tiersHint')
  return t('alarmRule.thresholdHint')
})
/** 阈值单位：从设备点表里查同名点位的单位，只读展示 */
const unit = computed(() => unitOfPoint(devices.list, fm.deviceCodes, fm.pointKey))

/* 回填：老规则（没配 tiers）展开成一行，界面上新老规则是同一套「档位」概念 */
{
  const r = props.rule
  const set = new Set(String(r.days || '').split(',').map((s) => s.trim()))
  daySel.value = dayNames.value.map((_, i) => set.has(String(i + 1)))
  thresholdText.value = r.threshold === null || r.threshold === undefined ? '' : String(r.threshold)
  const ts = ruleTiers(r)
  // 新建规则还没编号，档位从空白开始让人填——newRule() 里那个 threshold=0
  // 是给「设备停机」规则用的默认值，展开成「数值越限」的档位就成了「阈值 0」的假配置
  const rows: TierRow[] = r.id
    ? (ts.length
        ? sortTierRows(ts.map((x) => ({ id: nextTierRowId(), level: x.level, threshold: String(x.threshold) })))
        : [{ id: nextTierRowId(), level: r.level || 'MAJOR', threshold: '' }])
    : [{ id: nextTierRowId(), level: 'MINOR', threshold: '' }]
  tiers.value = rows
}

function readForm(): AlarmRule {
  const r: AlarmRule = { ...fm, id: fm.id ?? null }
  r.name = fm.name.trim()
  r.deviceCodes = fm.deviceCodes.trim()
  r.pointKey = showPoint.value ? fm.pointKey.trim() : ''
  r.schedule = fm.schedule.trim()
  r.message = fm.message.trim()
  r.days = daySel.value.map((on, i) => (on ? String(i + 1) : '')).filter(Boolean).join(',')
  const th = thresholdText.value.trim()
  if (fm.type === 'STOP') {
    r.threshold = th === '' ? 0 : Number(th)
    r.tiers = undefined
  } else if (fm.type === 'THRESHOLD') {
    // 分级规则不填单值 threshold：档位要是哪天丢了，规则是「失效」而不是悄悄降级成最低档
    const list = tiers.value.map((x) => ({ level: x.level, threshold: Number(x.threshold.trim()) }))
    r.tiers = list
    r.threshold = null
    // 规则级等级留个不矛盾的值（老字段，界面上不再单独暴露）
    r.level = list.reduce((a, b) => (LEVEL_RANK[b.level] > LEVEL_RANK[a.level] ? b : a)).level
  } else {
    r.threshold = null
    r.tiers = undefined
  }
  for (const k of ['forSeconds', 'recoverSeconds', 'cooldownSeconds'] as const) {
    const v = Number(fm[k])
    r[k] = Number.isFinite(v) && v >= 0 ? Math.round(v) : 0
  }
  return r
}

/** 提交：先在客户端把明显配错的挡下来（后端还会再校验一遍） */
function submit() {
  const r = readForm()
  if (!r.name) { ElMessage.warning(t('alarmRule.nameRequired')); return }
  if (fm.type === 'THRESHOLD') {
    const bad = validateTierRows(tiers.value, fm.op, t)
    if (bad) { ElMessage.warning(bad); return }
  } else if (r.threshold === null || !Number.isFinite(r.threshold)) {
    ElMessage.warning(t('alarmRule.thresholdRequired')); return
  }
  emit('save', r)
}

defineExpose({ submit })
</script>

<style scoped>
.hint { font-size: var(--text-2xs); color: var(--meta); line-height: 1.8; }
.edit .row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); }
.edit .field { margin-bottom: var(--space-3); min-width: 0; }
.edit .field.wide { grid-column: 1 / -1; }
.edit label { display: block; font-size: var(--text-xs); color: var(--muted); margin-bottom: 4px; }
.cond { display: flex; gap: var(--space-2); align-items: center; }
.days { display: flex; gap: var(--space-2); flex-wrap: wrap; }
.checks { display: flex; gap: var(--space-5); margin-top: var(--space-2); }
</style>
