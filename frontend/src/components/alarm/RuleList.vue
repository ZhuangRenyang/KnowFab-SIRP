<template>
  <div>
    <div class="hint lead">{{ t('alarmRule.lead') }}</div>
    <div v-if="!rules.length" class="empty">{{ t('alarmRule.empty') }}</div>
    <div v-for="x in rules" :key="x.rule.id ?? ''" class="rule-card" :class="{ off: !x.rule.enabled }">
      <div class="body">
        <div class="rn">{{ x.rule.name }}
          <span class="tag" :class="'lv-' + x.rule.level.toLowerCase()">{{ t('alarmDict.lv.' + x.rule.level.toLowerCase()) }}</span>
        </div>
        <div class="rd">{{ ruleDesc(x.rule, t, x.unit) }}</div>
        <div v-if="x.error" class="err">{{ t('alarmRule.cfgErr', { err: x.error }) }}</div>
      </div>
      <el-button size="small" @click="emit('toggle', x.rule, !x.rule.enabled)">{{ x.rule.enabled ? t('alarmRule.disable') : t('alarmRule.enable') }}</el-button>
      <el-button size="small" @click="emit('edit', x.rule)">{{ t('alarmRule.edit') }}</el-button>
      <el-button size="small" type="danger" plain @click="emit('remove', x.rule)">{{ t('alarmRule.del') }}</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { ruleDesc, type AlarmRule } from '@/api/alarms'

const { t } = useI18n()

defineProps<{ rules: { rule: AlarmRule; error?: string; unit?: string }[] }>()
const emit = defineEmits<{
  (e: 'toggle', rule: AlarmRule, enabled: boolean): void
  (e: 'edit', rule: AlarmRule): void
  (e: 'remove', rule: AlarmRule): void
}>()
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
</style>
