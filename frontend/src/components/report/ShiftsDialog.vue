<template>
  <el-dialog :model-value="modelValue" title="班次设置" width="620px"
             :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)"
             @open="open">
    <div class="hint lead">
      按客户车间的实际交接班时间填。可以跨零点（如 20:00 — 08:00）。
      报表按「开班那天」记账：26 日夜班 20:00 到 27 日 08:00，产量算 26 日。
      <b>历史报表会按新班次重算</b>，因为明细是按小时存的。
    </div>

    <div class="field">
      <label>方案名称</label>
      <el-input v-model="planName" placeholder="如 三班倒 / 两班倒 / 常白班" />
    </div>

    <div class="sect">班次</div>
    <div class="thead"><div>代号</div><div>名称</div><div>开始</div><div>结束</div><div></div></div>
    <div v-for="(s, i) in rows" :key="i" class="trow">
      <el-input v-model="s.id" :placeholder="String.fromCharCode(65 + i)" />
      <el-input v-model="s.name" :placeholder="'班次' + (i + 1)" />
      <el-input v-model="s.start" placeholder="08:00" />
      <el-input v-model="s.end" placeholder="16:00" />
      <el-button text type="danger" @click="rows.splice(i, 1)">×</el-button>
    </div>
    <el-button style="margin-top:var(--space-2)" @click="addRow">+ 添加班次</el-button>

    <el-alert v-if="warn" :title="warn" type="warning" :closable="false" class="warn" />

    <div class="hint tail">
      班次之间不能重叠，重叠会让同一小时被算进两个班，产量凭空翻倍。
      代号用来配对历史数据，改名字可以，代号尽量别改。
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存并生效</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { reportsApi, type ShiftDef, type ShiftPlan } from '@/api/reports'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'saved'): void }>()

const planName = ref('')
const rows = reactive<ShiftDef[]>([])
const warn = ref('')
const saving = ref(false)

watch(() => props.modelValue, (v) => { if (v) open() })

function addRow(s?: ShiftDef) {
  rows.push({
    id: s?.id || '', name: s?.name || '', start: s?.start || '08:00', end: s?.end || '16:00'
  })
}

async function open() {
  warn.value = ''
  rows.splice(0)
  try {
    const j = await reportsApi.shifts()
    const plan = j.plan || { name: '三班倒', shifts: [] }
    planName.value = plan.name || ''
    ;(plan.shifts || []).forEach((s) => addRow(s))
    if (!(plan.shifts || []).length) addRow()
  } catch {
    planName.value = ''
    addRow()
  }
}

async function save() {
  const shifts = rows.map((r, i) => ({
    id: (r.id || String.fromCharCode(65 + i)).trim(),
    name: r.name.trim() || `班次${i + 1}`,
    start: r.start.trim(),
    end: r.end.trim()
  }))
  if (!shifts.length) { warn.value = '至少要有一个班次。'; return }
  saving.value = true
  try {
    const j = await reportsApi.saveShifts({
      name: planName.value.trim() || '班次方案', shifts
    })
    if (j && j.ok === false) { warn.value = j.message || '保存失败'; return }
    emit('saved')
    emit('update:modelValue', false)
  } catch (e: any) {
    warn.value = '保存失败：' + (e?.message || '')
    ElMessage.error(warn.value)
  } finally { saving.value = false }
}
</script>

<style scoped>
.hint { font-size: var(--text-2xs); color: var(--meta); line-height: 1.7; }
.hint.lead { margin-bottom: var(--space-3); }
.hint.tail { margin-top: var(--space-3); }
.field { margin-bottom: var(--space-3); }
.field label { display: block; font-size: var(--text-xs); color: var(--muted); margin-bottom: 4px; }
.sect { font-size: var(--text-xs); color: var(--muted); letter-spacing: .1em; margin-bottom: var(--space-2); }
.thead, .trow { display: grid; grid-template-columns: 1.2fr 1fr .9fr .9fr 32px; gap: var(--space-2); }
.thead { font-size: var(--text-2xs); color: var(--meta); margin-bottom: 6px; }
.trow { margin-bottom: var(--space-2); align-items: center; }
.warn { margin-top: var(--space-3); border-radius: var(--radius-sm); }
</style>
