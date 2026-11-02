<template>
  <el-dialog :model-value="modelValue" :title="t('alarmRule.title')" width="800px"
             :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)"
             @open="open">
    <!-- 列表视图 -->
    <div v-if="view === 'list'">
      <RuleList :rules="rules" @toggle="toggle" @edit="edit" @remove="remove" />
    </div>

    <!-- 编辑视图：表单独立组件，RuleDialog 只管弹窗壳和保存 -->
    <RuleForm v-else ref="formRef" :rule="editing" @save="save" />

    <template #footer>
      <template v-if="view === 'list'">
        <el-button @click="emit('update:modelValue', false)">{{ t('alarmRule.close') }}</el-button>
        <el-button type="primary" @click="create">{{ t('alarmRule.create') }}</el-button>
      </template>
      <template v-else>
        <el-button @click="emit('update:modelValue', false)">{{ t('alarmRule.close') }}</el-button>
        <el-button @click="view = 'list'">{{ t('alarmRule.back') }}</el-button>
        <el-button type="primary" @click="formRef?.submit()">{{ t('alarmRule.save') }}</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { alarmsApi, newRule } from '@/api/alarms'
import type { AlarmRule } from '@/api/alarms'
import TierForm from '@/components/alarm/RuleForm.vue'
import RuleList from '@/components/alarm/RuleList.vue'

const { t } = useI18n()

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'changed'): void }>()

const view = ref<'list' | 'edit'>('list')
const rules = ref<{ rule: AlarmRule; error?: string }[]>([])
/** 正在编辑的规则（新建时是空模板） */
const editing = ref<AlarmRule>(newRule())
const formRef = ref<InstanceType<typeof TierForm> | null>(null)

watch(() => props.modelValue, (v) => { if (v) open() })

async function open() {
  view.value = 'list'
  try {
    const r = await alarmsApi.rules()
    rules.value = r.items || []
  } catch (e: any) { ElMessage.error(e?.message || t('alarmRule.loadFail')) }
}

function create() {
  editing.value = newRule()
  view.value = 'edit'
}

function edit(r: AlarmRule) {
  // 深拷贝：表单在改这份数据，别把列表里的原对象改了
  editing.value = JSON.parse(JSON.stringify(r))
  view.value = 'edit'
}

async function save(rule: AlarmRule) {
  try {
    const res = await alarmsApi.saveRule(rule)
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
