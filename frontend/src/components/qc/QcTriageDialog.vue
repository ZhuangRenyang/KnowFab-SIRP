<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('qc.triage.title')"
    width="520px"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <p class="tg-hint">{{ t('qc.triage.hint', { n: defectNos.length }) }}</p>

    <el-form label-width="96px">
      <el-form-item :label="t('qc.triage.fDefect')" required>
        <el-select v-model="codeId" filterable clearable class="w-full" :placeholder="t('qc.triage.pickDefect')">
          <el-option v-for="c in codes" :key="c.id" :value="c.id" :label="`${c.code} ${c.name}`">
            <span class="opt-code">{{ c.code }}</span>
            <el-tag size="small" effect="plain" :type="gradeType(c.severity)">
              {{ t('qc.severity.' + c.severity) }}
            </el-tag>
          </el-option>
        </el-select>
      </el-form-item>
      <el-form-item :label="t('qc.triage.fSeverity')">
        <el-radio-group v-model="severity">
          <el-radio-button v-for="s in SEVERITIES" :key="s" :value="s">
            {{ t('qc.severity.' + s) }}
          </el-radio-button>
        </el-radio-group>
        <div class="field-hint">{{ t('qc.triage.severityHint') }}</div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t('base.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ t('qc.triage.submit') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  gradeType, qcDefectApi, qcDefectCodeApi, SEVERITIES,
  type QcDefectCode, type QcSeverity
} from '@/api/qc'

const props = defineProps<{
  modelValue: boolean
  defectNos: string[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved'): void
}>()

const { t } = useI18n()

const saving = ref(false)
const codes = ref<QcDefectCode[]>([])
const codeId = ref<number | undefined>()
const severity = ref<QcSeverity>('MA')

watch(() => props.modelValue, async (v) => {
  if (!v) return
  codeId.value = undefined
  severity.value = 'MA'
  try {
    const r = await qcDefectCodeApi.list({ enabled: true })
    codes.value = r.rows || []
  } catch {
    codes.value = []
  }
})

async function submit() {
  if (!codeId.value) {
    ElMessage.warning(t('qc.triage.needCode'))
    return
  }
  saving.value = true
  // 逐条分诊：后端只有单条端点，批量在前端循环。
  // 分诊不会失败一半——失败的只会是"这条已处置"，所以要逐条记下来，
  // 不能因为一条失败就整批放弃（那样剩下的记录还是分不掉）。
  const failed: string[] = []
  let done = 0
  for (const no of props.defectNos) {
    try {
      const r = await qcDefectApi.triage(no, {
        defectCodeId: codeId.value,
        severity: severity.value
      })
      if (!r.ok) {
        failed.push(no)
      } else {
        done++
      }
    } catch {
      failed.push(no)
    }
  }
  saving.value = false
  if (failed.length) {
    // 全部失败 → 报错误（多半是记录都已处置）；部分失败 → 说清有几条没成
    if (!done) {
      ElMessage.error(t('common.reqFail'))
    } else {
      ElMessage.warning(t('qc.triage.partialDone', { n: failed.length }))
    }
  } else {
    ElMessage.success(t('qc.triage.done'))
  }
  emit('update:modelValue', false)
  emit('saved')
}
</script>

<style scoped>
.tg-hint {
  margin: 0 0 14px;
  font-size: var(--text-sm);
  color: var(--fg-2);
}
.opt-code {
  display: inline-block;
  min-width: 64px;
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  color: var(--muted);
}
</style>
