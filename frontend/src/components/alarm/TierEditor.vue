<template>
  <div class="tiers">
    <div v-for="row in rows" :key="row.id" class="tier">
      <el-select :model-value="row.level" style="width:124px" @update:model-value="setLevel(row.id, $event)">
        <el-option v-for="lv in LEVEL_ORDER" :key="lv" :value="lv" :label="t('alarmDict.lv.' + lv.toLowerCase())" />
      </el-select>
      <el-input
        :model-value="row.threshold"
        :placeholder="t('alarmRule.tierThresholdPh')"
        class="th"
        @update:model-value="setThreshold(row.id, $event)"
      />
      <!-- 单位从点位定义查，只读展示：同一个数值在温度点是 ℃、在压力点是 MPa -->
      <span class="unit">{{ unit }}</span>
      <el-button :icon="Delete" text :disabled="rows.length <= 1" :title="t('alarmRule.tierDel')" @click="removeTier(row.id)" />
    </div>
    <el-button :icon="Plus" size="small" @click="addTier">{{ t('alarmRule.addTier') }}</el-button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Delete, Plus } from '@element-plus/icons-vue'
import { nextTierRowId, sortTierRows, LEVEL_ORDER } from '@/utils/alarmTier'
import type { TierRow } from '@/utils/alarmTier'

const { t } = useI18n()

const props = defineProps<{ modelValue: TierRow[]; unit?: string }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: TierRow[]): void }>()

/** 行按等级从低到高排（预警→告警→停机），一眼看得出递进关系 */
const rows = computed(() => sortTierRows(props.modelValue))

const unit = computed(() => props.unit || '')

/** 换档位后重排——行要留在它该在的位置 */
function setLevel(id: number, level: TierRow['level']) {
  const next = props.modelValue.map((r) => (r.id === id ? { ...r, level } : r))
  emit('update:modelValue', sortTierRows(next))
}

function setThreshold(id: number, threshold: string) {
  emit('update:modelValue', props.modelValue.map((r) => (r.id === id ? { ...r, threshold } : r)))
}

/** 加一档：默认挑还没用过的最低等级 */
function addTier() {
  const used = new Set(props.modelValue.map((x) => x.level))
  const next = LEVEL_ORDER.find((lv) => !used.has(lv)) || 'MINOR'
  emit('update:modelValue', sortTierRows([...props.modelValue, { id: nextTierRowId(), level: next, threshold: '' }]))
}

function removeTier(id: number) {
  if (props.modelValue.length <= 1) return
  emit('update:modelValue', props.modelValue.filter((r) => r.id !== id))
}
</script>

<style scoped>
.tiers { display: flex; flex-direction: column; gap: var(--space-2); margin-top: var(--space-2); align-items: flex-start; }
.tier { display: flex; gap: var(--space-2); align-items: center; width: 100%; }
.th { flex: 1; min-width: 0; }
/* 单位跟后端审计文案一样贴在数值后面，不抢输入框的视线 */
.unit { flex: none; min-width: 26px; font-size: var(--text-2xs); color: var(--meta); }
</style>
