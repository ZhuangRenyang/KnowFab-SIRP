<template>
  <div class="page">
    <header class="bar">
      <h2>{{ t('sch.title') }}</h2>
      <div class="acts">
        <el-date-picker v-model="range" type="datetimerange" range-separator="→"
                        start-placeholder="from" end-placeholder="to" style="width: 360px" />
        <el-button :loading="loading" @click="load">{{ t('base.refresh') }}</el-button>
      </div>
    </header>

    <el-alert v-if="gantt.conflicts?.length" type="error" :closable="false" class="alert">
      <template #title>
        {{ t('sch.conflictTitle', { n: gantt.conflicts.length }) }}
      </template>
      <ul class="clist">
        <li v-for="(c, i) in gantt.conflicts" :key="i">
          {{ c.lineCode }} · {{ c.aOrderNo }} × {{ c.bOrderNo }}
          （重叠 {{ fmt(c.overlapStart) }} → {{ fmt(c.overlapEnd) }}）
        </li>
      </ul>
    </el-alert>
    <el-alert v-else type="success" :closable="false" class="alert">
      {{ t('sch.noConflict') }}
    </el-alert>

    <!-- 甘特：按产线分组 -->
    <section class="card">
      <h3>{{ t('sch.gantt') }}</h3>
      <p class="hint">{{ t('sch.ganttHint') }}</p>
      <div v-if="!gantt.lines?.length" class="empty">{{ t('sch.noScheduled') }}</div>
      <div v-for="ln in gantt.lines" :key="ln.lineCode" class="line">
        <div class="lname">{{ ln.lineCode }}</div>
        <div class="track">
          <div v-for="o in ln.orders" :key="o.orderNo" class="bar-item"
               :class="{ conflict: o.conflict }"
               :style="barStyle(o)"
               :title="barTitle(o)">
            <span class="bn">{{ o.orderNo }}</span>
            <span class="bp">{{ o.productName }}</span>
          </div>
        </div>
      </div>
      <div class="axis">
        <span>{{ fmt(axisStart) }}</span>
        <span class="mid">{{ fmt((axisStart + axisEnd) / 2) }}</span>
        <span>{{ fmt(axisEnd) }}</span>
      </div>
    </section>

    <!-- 未排程的工单 -->
    <section class="card">
      <h3>{{ t('sch.unscheduled') }}</h3>
      <el-table :data="gantt.unscheduled || []" size="small" stripe>
        <el-table-column prop="orderNo" :label="t('wo.colNo')" width="170" />
        <el-table-column prop="productName" :label="t('wo.colProduct')" min-width="140" />
        <el-table-column prop="lineCode" :label="t('sch.line')" width="130" />
        <el-table-column prop="status" :label="t('wo.colStatus')" width="100" />
        <el-table-column :label="t('base.action')" width="110" align="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDialog(row)">
              {{ t('sch.schedule') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- 排程弹窗 -->
    <el-dialog v-model="dlg" :title="t('sch.dlgTitle', { no: cur?.orderNo || '' })" width="480px">
      <el-form label-width="90px">
        <el-form-item :label="t('sch.line')">
          <el-input v-model="form.lineCode" :placeholder="t('sch.linePlaceholder')" />
          <div class="hint">{{ t('sch.lineHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('sch.start')">
          <el-date-picker v-model="form.start" type="datetime" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('sch.end')">
          <el-date-picker v-model="form.end" type="datetime" style="width: 100%" />
        </el-form-item>
      </el-form>
      <el-alert v-if="dlgMsg" :type="dlgErr ? 'error' : 'info'" :closable="false">{{ dlgMsg }}</el-alert>
      <template #footer>
        <el-button v-if="cur?.planStart" @click="doUnschedule">{{ t('sch.clear') }}</el-button>
        <el-button @click="dlg = false">{{ t('base.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="doSave(false)">{{ t('sch.save') }}</el-button>
        <el-button type="danger" plain :loading="saving" @click="doSave(true)">
          {{ t('sch.forceSave') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { scheduleApi, type ScheduleGantt, type ScheduleOrderRow } from '@/api/workOrder'

const { t } = useI18n()
const loading = ref(false)
const saving = ref(false)
const gantt = reactive<ScheduleGantt>({ lines: [], unscheduled: [], conflicts: [] })
const range = ref<[Date, Date] | null>(null)
const dlg = ref(false)
const dlgMsg = ref('')
const dlgErr = ref(false)
const cur = ref<ScheduleOrderRow | null>(null)
const form = reactive<{ lineCode: string; start: Date | null; end: Date | null }>({
  lineCode: '', start: null, end: null
})

/** 时间轴范围：默认本周；没选就用数据里最早/最晚 */
const axisStart = computed(() => {
  if (range.value) return range.value[0].getTime()
  const ts = (gantt.lines || []).flatMap(l => l.orders.map(o => o.planStart || 0)).filter(x => x > 0)
  return ts.length ? Math.min(...ts) : Date.now() - 86400000
})
const axisEnd = computed(() => {
  if (range.value) return range.value[1].getTime()
  const ts = (gantt.lines || []).flatMap(l => l.orders.map(o => o.planEnd || 0)).filter(x => x > 0)
  return ts.length ? Math.max(...ts) : Date.now() + 86400000
})

function barStyle(o: ScheduleOrderRow) {
  const span = Math.max(1, axisEnd.value - axisStart.value)
  const left = Math.max(0, ((o.planStart || 0) - axisStart.value) / span * 100)
  const width = Math.max(1.2, ((o.planEnd || 0) - (o.planStart || 0)) / span * 100)
  return { left: left + '%', width: Math.min(width, 100 - left) + '%' }
}

function barTitle(o: ScheduleOrderRow) {
  return `${o.orderNo} ${o.productName || ''}\n${fmt(o.planStart)} → ${fmt(o.planEnd)}`
}

function fmt(ts?: number | null) {
  if (!ts) return '-'
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

async function load() {
  loading.value = true
  try {
    const r = await scheduleApi.gantt()
    gantt.lines = r?.lines || []
    gantt.unscheduled = r?.unscheduled || []
    gantt.conflicts = r?.conflicts || []
  } catch (e: any) {
    ElMessage.error(e?.message || String(e))
  } finally {
    loading.value = false
  }
}

function openDialog(row: ScheduleOrderRow) {
  cur.value = row
  form.lineCode = row.lineCode || ''
  form.start = row.planStart ? new Date(row.planStart) : new Date()
  form.end = row.planEnd ? new Date(row.planEnd) : new Date(Date.now() + 8 * 3600 * 1000)
  dlgMsg.value = ''
  dlgErr.value = false
  dlg.value = true
}

async function doSave(force: boolean) {
  if (!cur.value) return
  if (!form.start || !form.end) {
    dlgMsg.value = t('sch.needTime')
    dlgErr.value = true
    return
  }
  saving.value = true
  try {
    const r = await scheduleApi.schedule(cur.value.orderNo, form.start.getTime(), form.end.getTime(), force, form.lineCode)
    if (r?.ok) {
      dlgMsg.value = r.message || t('sch.saved')
      dlgErr.value = false
      ElMessage.success(t('sch.saved'))
      dlg.value = false
      await load()
    } else {
      dlgMsg.value = r?.message || t('sch.failed')
      dlgErr.value = true
    }
  } catch (e: any) {
    dlgMsg.value = e?.message || String(e)
    dlgErr.value = true
  } finally {
    saving.value = false
  }
}

async function doUnschedule() {
  if (!cur.value) return
  saving.value = true
  try {
    const r = await scheduleApi.unschedule(cur.value.orderNo)
    if (r?.ok) {
      ElMessage.success(r.message || t('sch.saved'))
      dlg.value = false
      await load()
    } else {
      dlgMsg.value = r?.message || t('sch.failed')
      dlgErr.value = true
    }
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.bar h2 { margin: 0; font-size: 18px; }
.acts { display: flex; gap: 8px; align-items: center; }
.alert { margin-bottom: 12px; }
.clist { margin: 6px 0 0; padding-left: 18px; font-size: 12px; }
.card { background: var(--dh-card, #fff); border: 1px solid var(--dh-border, #e5e7eb);
        border-radius: 6px; padding: 12px 14px; margin-bottom: 12px; }
.card h3 { margin: 0 0 6px; font-size: 15px; }
.hint { margin: 0 0 10px; font-size: 12px; color: #6b7280; }
.empty { padding: 16px; text-align: center; color: #9ca3af; font-size: 13px; }
.line { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.lname { width: 130px; flex: none; font-size: 12px; color: #374151;
         overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.track { position: relative; flex: 1; height: 26px; background: #f3f4f6;
         border-radius: 4px; overflow: hidden; }
.bar-item { position: absolute; top: 2px; bottom: 2px; background: #3b82f6; color: #fff;
            border-radius: 3px; padding: 0 6px; display: flex; align-items: center;
            gap: 6px; font-size: 11px; overflow: hidden; white-space: nowrap; }
.bar-item.conflict { background: #dc2626; }
.bn { font-weight: 600; }
.bp { opacity: .85; }
.axis { display: flex; justify-content: space-between; margin-left: 138px;
        font-size: 11px; color: #6b7280; }
</style>
