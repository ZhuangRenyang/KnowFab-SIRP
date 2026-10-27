<template>
  <el-dialog :model-value="modelValue" title="分时电价方案" width="720px"
             :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)"
             @open="open">
    <div class="hint lead">
      按客户当地供电局的《分时电价执行细则》填。时段可以跨零点（如 23:00 — 07:00）。
      保存后立即生效，<b>历史账单用的是当时的电价，不会跟着变</b>。
    </div>

    <div class="field">
      <label>方案名称</label>
      <el-input v-model="planName" placeholder="如 2026 年大工业峰谷电价" />
    </div>

    <div class="sect">时段与单价</div>
    <div class="thead"><div>名称</div><div>类型</div><div>开始</div><div>结束</div><div>单价 元/度</div><div></div></div>
    <div v-for="(s, i) in rows" :key="i" class="trow">
      <el-input v-model="s.name" placeholder="平段" />
      <el-select v-model="s.type">
        <el-option v-for="[v, n] in SEG_TYPES" :key="v" :label="n" :value="v" />
      </el-select>
      <el-input v-model="s.start" placeholder="08:00" />
      <el-input v-model="s.end" placeholder="11:00" />
      <el-input v-model.number="s.price" type="number" step="0.001" />
      <el-button text type="danger" @click="rows.splice(i, 1)">×</el-button>
    </div>
    <el-button style="margin-top:var(--space-2)" @click="addRow">+ 添加时段</el-button>

    <el-alert v-if="warn" type="warning" :closable="false" class="warn">
      <template #title><div v-html="warn"></div></template>
    </el-alert>

    <div class="hint tail">
      类型固定为尖峰 / 高峰 / 平段 / 低谷四种，前端用它给图表配色和汇总。
      时段必须覆盖完整 24 小时，漏掉的那部分电量会进「未计价」，只统计度数不算钱。
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
import { energyApi, SEG_TYPES, type TariffPlan } from '@/api/energy'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'saved'): void }>()

const planName = ref('')
const rows = reactive<TariffPlan['segments']>([])
const warn = ref('')
const saving = ref(false)

watch(() => props.modelValue, (v) => { if (v) open() })

function addRow(s?: TariffPlan['segments'][number]) {
  rows.push({
    name: s?.name || '平段', type: s?.type || 'FLAT',
    start: s?.start || '08:00', end: s?.end || '11:00',
    price: s?.price == null ? 0.7 : s.price
  })
}

async function open() {
  warn.value = ''
  rows.splice(0)
  try {
    const j = await energyApi.tariff()
    const plan = j.plan || { name: '', segments: [] }
    planName.value = plan.name || ''
    ;(plan.segments || []).forEach((s) => addRow(s))
  } catch (e: any) { ElMessage.error('读不到电价方案：' + (e?.message || '')) }
}

async function save() {
  if (!rows.length) { ElMessage.warning('至少要有一个电价时段'); return }
  saving.value = true
  try {
    const j = await energyApi.saveTariff({
      name: planName.value.trim(),
      segments: rows.map((r) => ({
        name: r.name.trim(), type: r.type,
        start: r.start.trim(), end: r.end.trim(),
        price: Number(r.price) || 0
      }))
    })
    if (j && j.ok === false) {
      ElMessage.error(j.message || '保存失败')
      return
    }
    if (j.warnings && j.warnings.length) {
      /* 配漏了时段不阻止保存，但必须让人看见，否则电费对不上会赖软件 */
      warn.value = '已保存，但配置有问题：<br>' + j.warnings.map((x) => '· ' + x).join('<br>')
      emit('saved')
      return
    }
    emit('saved')
    emit('update:modelValue', false)
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败')
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
.thead, .trow { display: grid; grid-template-columns: 1.1fr 1fr .8fr .8fr .9fr 32px; gap: var(--space-2); }
.thead { font-size: var(--text-2xs); color: var(--meta); margin-bottom: 6px; }
.trow { margin-bottom: var(--space-2); align-items: center; }
.warn { margin-top: var(--space-3); border-radius: var(--radius-sm); }
</style>
