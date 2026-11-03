<template>
  <div class="aq">
    <!-- ① 查表输入：批量 + 水平 + 严格度 + AQL -->
    <div v-if="editable" class="aq-input">
      <div class="frow">
        <div>
          <label class="aq-lb">{{ t('qc.aql.lotQty') }}</label>
          <el-input-number
            :model-value="lotQty"
            :min="1"
            :precision="0"
            controls-position="right"
            class="w-full"
            @update:model-value="onLotQty"
          />
        </div>
        <div>
          <label class="aq-lb">{{ t('qc.aql.level') }}</label>
          <el-select :model-value="level" class="w-full" @update:model-value="onLevel">
            <el-option v-for="l in QC_LEVELS" :key="l" :value="l" :label="t('qc.level.' + l)" />
          </el-select>
        </div>
      </div>
      <div class="frow">
        <div>
          <label class="aq-lb">{{ t('qc.aql.strict') }}</label>
          <el-select :model-value="strictLevel" class="w-full" @update:model-value="onStrict">
            <el-option v-for="s in STRICT_LEVELS" :key="s" :value="s" :label="t('qc.strictLevel.' + s)" />
          </el-select>
        </div>
        <div>
          <label class="aq-lb">{{ t('qc.aql.value') }}</label>
          <el-select :model-value="aql" class="w-full" @update:model-value="onAql">
            <el-option v-for="a in AQL_VALUES" :key="a" :value="a" :label="a + '%'" />
          </el-select>
        </div>
      </div>
    </div>

    <!-- ② 计算链：4 个盒子 -->
    <div class="aq-chain">
      <div class="aq-box">
        <span class="aq-lb">{{ t('qc.aql.boxLot') }}</span>
        <b class="aq-val num">{{ qty(lotQty, 0) }}</b>
      </div>
      <el-icon class="aq-arrow"><Right /></el-icon>
      <div class="aq-box">
        <span class="aq-lb">{{ t('qc.aql.boxLetter') }}</span>
        <b class="aq-val num">{{ result?.codeLetter || '--' }}</b>
        <el-tooltip placement="top" :disabled="!trace.length">
          <template #content>
            <!-- 查表依据：逐条展示，让人能自己核对 -->
            <div class="aq-trace">
              <div v-for="(s, i) in trace" :key="i" class="aq-trace-row">
                {{ t('qc.trace.' + s.code, s.params) }}
              </div>
            </div>
          </template>
          <el-icon class="aq-info"><InfoFilled /></el-icon>
        </el-tooltip>
      </div>
      <el-icon class="aq-arrow"><Right /></el-icon>
      <div class="aq-box">
        <span class="aq-lb">{{ t('qc.aql.boxSample') }}</span>
        <b class="aq-val num">{{ result?.found ? qty(result.sampleSize, 0) : '--' }}</b>
      </div>
      <el-icon class="aq-arrow"><Right /></el-icon>
      <div class="aq-box" :class="{ danger: result?.zeroTolerance }">
        <span class="aq-lb">{{ t('qc.aql.boxAc') }}</span>
        <!-- ac = -1 是零容忍，显示「0 件（零容忍）」，不能归一化成 0 -->
        <b class="aq-val num">{{ acText }}</b>
      </div>
    </div>

    <!-- ③ 查不到方案：两个出路，不自己兜底算 -->
    <el-alert
      v-if="!result?.found"
      type="warning"
      :closable="false"
      show-icon
      class="aq-alert"
      :title="t('qc.aql.noPlanTitle')"
    >
      <div class="aq-outlets">
        <button type="button" class="aq-outlet" @click="emit('outlet', 'changeLot')">
          <el-icon><Edit /></el-icon>
          <span>{{ t('qc.aql.outletLot') }}</span>
        </button>
        <button type="button" class="aq-outlet" @click="emit('outlet', 'addPlan')">
          <el-icon><Plus /></el-icon>
          <span>{{ t('qc.aql.outletPlan') }}</span>
        </button>
      </div>
      <p class="aq-outlet-hint">{{ t('qc.aql.outletHint') }}</p>
    </el-alert>

    <!-- ④ 主数据缺失时的兜底：临时全检 + 零允收 -->
    <div v-if="manualMode" class="aq-manual">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        :title="t('qc.aql.manualTitle')"
        :description="t('qc.aql.manualDesc')"
      />
    </div>

    <p v-if="result?.note" class="aq-note">{{ result.note }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Edit, InfoFilled, Plus, Right } from '@element-plus/icons-vue'
import { QC_LEVELS, STRICT_LEVELS, type QcLevel, type QcStrictLevel } from '@/api/qc'
import { resolveSampling, type SamplingResult, type TraceStep } from '@/utils/aql'
import { AQL_VALUES } from '@/utils/aqlTable'
import { qty } from '@/utils/format'

const props = withDefaults(defineProps<{
  lotQty: number
  level?: QcLevel | null
  strictLevel?: QcStrictLevel | null
  aql?: number | null
  /** 服务端算好的结果。有它就以服务端为准（真值在服务端） */
  resolved?: SamplingResult | null
  /** 主数据缺失时的兜底模式：临时全检 + 零允收 */
  manualMode?: boolean
  editable?: boolean
}>(), {
  level: 'GENERAL_II',
  strictLevel: 'NORMAL',
  aql: 1.0,
  resolved: null,
  manualMode: false,
  editable: true
})

const emit = defineEmits<{
  (e: 'change', v: { lotQty: number; level: QcLevel; strictLevel: QcStrictLevel; aql: number }): void
  (e: 'outlet', v: 'changeLot' | 'addPlan'): void
}>()

const { t } = useI18n()

/** 本地预览（服务端结果到达之前用）；有服务端结果就以服务端为准 */
const result = computed<SamplingResult | null>(() => {
  if (props.resolved) return props.resolved
  return resolveSampling({
    lotQty: props.lotQty,
    level: props.level || 'GENERAL_II',
    strictLevel: props.strictLevel || 'NORMAL',
    aql: props.aql ?? 1.0
  })
})

const trace = computed<TraceStep[]>(() => result.value?.trace || [])

/**
 * Ac 的显示。
 * -1 = 零容忍（CR 致命缺陷），显示「0 件（零容忍）」——
 * 不能归一化成 0，否则判定岗看不出这批是"发现一件就整批退"。
 */
const acText = computed(() => {
  const r = result.value
  if (!r || !r.found) return '--'
  if (r.zeroTolerance) return t('qc.aql.acZeroTol')
  if (r.ac === 0) return t('qc.aql.acNotSet')
  return t('qc.aql.acValue', { ac: r.ac, re: r.re })
})

function onLotQty(v: number | undefined) {
  emit('change', { lotQty: v || 1, level: props.level || 'GENERAL_II', strictLevel: props.strictLevel || 'NORMAL', aql: props.aql ?? 1.0 })
}
function onLevel(v: QcLevel) {
  emit('change', { lotQty: props.lotQty, level: v, strictLevel: props.strictLevel || 'NORMAL', aql: props.aql ?? 1.0 })
}
function onStrict(v: QcStrictLevel) {
  emit('change', { lotQty: props.lotQty, level: props.level || 'GENERAL_II', strictLevel: v, aql: props.aql ?? 1.0 })
}
function onAql(v: number) {
  emit('change', { lotQty: props.lotQty, level: props.level || 'GENERAL_II', strictLevel: props.strictLevel || 'NORMAL', aql: v })
}
</script>

<style scoped>
.aq {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--surface-2);
  padding: 12px 14px;
}
.aq-lb {
  display: block;
  font-size: var(--text-xs);
  color: var(--muted);
  margin-bottom: 4px;
}
.aq-input .frow {
  margin-bottom: 10px;
}
.aq-chain {
  display: flex;
  align-items: stretch;
  gap: 6px;
  margin-top: 4px;
}
.aq-box {
  position: relative;
  flex: 1;
  min-width: 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface);
  padding: 7px 10px;
}
.aq-box.danger {
  border-color: var(--alarm);
  background: var(--alarm-soft);
}
.aq-box.danger .aq-val {
  color: var(--alarm);
}
.aq-val {
  display: block;
  font-size: 17px;
  font-weight: 600;
  color: var(--fg);
  line-height: 1.3;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.aq-arrow {
  align-self: center;
  color: var(--meta);
  flex: none;
}
.aq-info {
  position: absolute;
  top: 5px;
  right: 5px;
  color: var(--meta);
  cursor: help;
}
.aq-info:hover {
  color: var(--accent);
}
.aq-trace {
  max-width: 320px;
  line-height: 1.7;
}
.aq-trace-row {
  font-size: var(--text-xs);
}
.aq-alert {
  margin-top: 10px;
}
.aq-outlets {
  display: flex;
  gap: 8px;
  margin-top: 8px;
  flex-wrap: wrap;
}
.aq-outlet {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--fg-2);
  font-size: var(--text-xs);
  padding: 6px 10px;
  cursor: pointer;
  transition: border-color var(--motion-fast) var(--ease-standard),
              color var(--motion-fast) var(--ease-standard);
}
.aq-outlet:hover {
  border-color: var(--accent);
  color: var(--accent);
}
.aq-outlet-hint {
  margin: 8px 0 0;
  font-size: var(--text-2xs);
  color: var(--muted);
}
.aq-manual {
  margin-top: 10px;
}
.aq-note {
  margin: 8px 0 0;
  font-size: var(--text-xs);
  color: var(--muted);
}
</style>
