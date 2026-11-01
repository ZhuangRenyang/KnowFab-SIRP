<template>
  <div class="lines">
    <div class="kpis">
      <div class="kpi">
        <span>{{ t('erpLine.lines') }}</span>
        <b class="num">{{ list?.summary.lines || 0 }}<i>{{ t('erpLine.unitLine') }}</i></b>
        <em>{{ t('erpLine.withFinished', { n: list?.summary.withFinished || 0 }) }}</em>
      </div>
      <div class="kpi">
        <span>{{ t('erpLine.devices') }}</span>
        <b class="num">{{ list?.summary.devices || 0 }}<i>{{ t('erpLine.unitDevice') }}</i></b>
        <em>{{ t('erpLine.onlineN', { n: list?.summary.online || 0 }) }}</em>
      </div>
      <div class="kpi">
        <span>{{ t('erpLine.todayOutput') }}</span>
        <b class="num up">{{ qty(list?.summary.today) }}<i>{{ t('erpLine.unitPiece') }}</i></b>
        <em>{{ t('erpLine.todayEm', { v: qty(list?.summary.month) }) }}</em>
      </div>
      <div class="kpi">
        <span>{{ t('erpLine.unassigned') }}</span>
        <b class="num">{{ list?.unassigned || 0 }}<i>{{ t('erpLine.unitDevice') }}</i></b>
        <em>{{ t('erpLine.unassignedEm') }}</em>
      </div>
    </div>

    <el-card shadow="never">
      <div class="tab-toolbar">
        <el-input v-model="kw" :placeholder="t('erpLine.searchPh')" clearable :prefix-icon="Search"
          style="width:220px" />
        <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('erpLine.refresh') }}</el-button>
        <span class="spacer" />
        <span class="dim tip">{{ t('erpLine.finishedTip') }}</span>
        <el-button v-if="auth.canWrite" type="primary" :icon="Plus" @click="openCreate">{{ t('erpLine.create') }}</el-button>
      </div>

      <el-table :data="filtered" v-loading="loading" stripe>
        <el-table-column :label="t('erpLine.colLine')" min-width="200">
          <template #default="{ row }">
            <div class="lname">
              {{ row.name }}
              <el-tag v-if="!row.enabled" size="small" type="info" effect="plain">{{ t('erpLine.disabled') }}</el-tag>
            </div>
            <div class="dim sub">{{ row.code }} · {{ row.workshop || t('erpLine.noWorkshop') }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('erpLine.colDevices')" width="130">
          <template #default="{ row }">
            <span class="num">{{ row.deviceCount }}</span>
            <span class="dim">{{ t('erpLine.onlineN2', { n: row.onlineCount }) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('erpLine.colFinished')" min-width="230">
          <template #default="{ row }">
            <template v-if="row.finishedCode">
              <span class="num">{{ row.finishedCode }}</span>
              <el-tag size="small" :type="row.finishedAuto ? 'info' : 'success'" effect="plain" class="ml6">
                {{ row.finishedAuto ? t('erpLine.auto') : t('erpLine.assigned') }}
              </el-tag>
              <el-tag v-if="!row.finishedHasCount" size="small" type="warning" effect="plain" class="ml6">
                {{ t('erpLine.noPoint') }}
              </el-tag>
            </template>
            <span v-else class="dim">{{ t('erpLine.notArranged') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('erpLine.colToday')" width="120" align="right">
          <template #default="{ row }">
            <b v-if="row.finishedCode" class="num">{{ qty(row.today) }}</b>
            <span v-else class="dim">--</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('erpLine.colMonth')" width="110" align="right">
          <template #default="{ row }">
            <span class="num dim">{{ row.finishedCode ? qty(row.month) : '--' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('erpLine.colAction')" width="190" align="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openArrange(row)">{{ t('erpLine.arrange') }}</el-button>
            <el-button v-if="auth.canWrite" size="small" text @click="openEdit(row)">{{ t('erpLine.edit') }}</el-button>
            <el-button v-if="auth.canWrite" size="small" text type="danger" @click="removeLine(row)">{{ t('erpLine.remove') }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!loading && !filtered.length" class="empty">
        {{ t('erpLine.empty') }}
      </div>
    </el-card>

    <!-- 新建 / 编辑 -->
    <el-dialog v-model="dlgOpen" :title="editing ? t('erpLine.titleEdit') : t('erpLine.titleNew')" width="520px"
      :close-on-click-modal="false">
      <el-form label-width="88px">
        <el-form-item :label="t('erpLine.fCode')">
          <el-input v-model="form.code" :disabled="!!editing" placeholder="LINE-01" />
        </el-form-item>
        <el-form-item :label="t('erpLine.fName')">
          <el-input v-model="form.name" :placeholder="t('erpLine.namePh')" />
        </el-form-item>
        <el-form-item :label="t('erpLine.fWorkshop')">
          <el-input v-model="form.workshop" :placeholder="t('erpLine.workshopPh')" />
        </el-form-item>
        <el-form-item :label="t('erpLine.fSort')">
          <el-input-number v-model="form.sortOrder" :min="0" :step="10" :controls="false" style="width:120px" />
          <span class="dim ml6">{{ t('erpLine.sortTip') }}</span>
        </el-form-item>
        <el-form-item :label="t('erpLine.fEnabled')">
          <el-switch v-model="form.enabled" />
          <span class="dim ml6">{{ t('erpLine.enabledTip') }}</span>
        </el-form-item>
        <el-form-item :label="t('erpLine.fRemark')">
          <el-input v-model="form.remark" type="textarea" :rows="2" :placeholder="t('erpLine.optional')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlgOpen = false">{{ t('erpLine.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="submit">{{ t('erpLine.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- 编排抽屉 -->
    <el-drawer v-model="arrangeOpen" :size="780" :title="cur ? t('erpLine.arrangeTitle', { name: cur.name }) : t('erpLine.arrangeTitlePlain')"
      :destroy-on-close="true" @opened="onDrawerOpened" @closed="onDrawerClosed">
      <div v-if="cur" class="arrange">
        <div class="hint">
          {{ t('erpLine.arrangeTip1') }}<b>{{ t('erpLine.arrangeTip2') }}</b>{{ t('erpLine.arrangeTip3') }}
          {{ t('erpLine.arrangeTip4') }}
        </div>

        <div class="add-row">
          <el-select v-model="addCode" :placeholder="t('erpLine.addPh')" filterable clearable style="flex:1">
            <el-option v-for="d in addable" :key="d.code" :label="`${d.name || d.code}（${d.code}）`"
              :value="d.code" />
          </el-select>
          <el-button :icon="Plus" :disabled="!addCode" @click="addDevice">{{ t('erpLine.add') }}</el-button>
        </div>

        <el-table :data="local" size="small" class="dev-table" max-height="320">
          <el-table-column :label="t('erpLine.colStation')" width="64">
            <template #default="{ $index }"><span class="num">{{ $index + 1 }}</span></template>
          </el-table-column>
          <el-table-column :label="t('erpLine.colDevice')" min-width="180">
            <template #default="{ row }">
              <div>{{ row.name || row.code }}</div>
              <div class="dim sub">{{ row.code }}<span v-if="row.location"> · {{ row.location }}</span></div>
            </template>
          </el-table-column>
          <el-table-column :label="t('erpLine.colStatus')" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="row.online ? 'success' : 'info'" effect="plain">
                {{ row.online ? t('erpLine.online') : t('erpLine.offline') }}
              </el-tag>
              <el-tag v-if="!row.hasCount" size="small" type="warning" effect="plain" class="ml6">{{ t('erpLine.noPoint') }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('erpLine.colTodayShort')" width="86" align="right">
            <template #default="{ row }"><span class="num">{{ qty(row.today) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('erpLine.colFinishedPoint')" width="78" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.finished" size="small" type="success">{{ t('erpLine.finished') }}</el-tag>
              <el-button v-else size="small" text type="primary" :disabled="dirty || !row.hasCount"
                @click="setFinished(row)">{{ t('erpLine.setAs') }}</el-button>
            </template>
          </el-table-column>
          <el-table-column :label="t('erpLine.colAdjust')" width="152" align="right" class-name="op-cell">
            <template #default="{ $index }">
              <el-button size="small" text :icon="Top" :title="t('erpLine.moveUp')" :disabled="$index === 0"
                @click="move($index, -1)" />
              <el-button size="small" text :icon="Bottom" :title="t('erpLine.moveDown')" :disabled="$index === local.length - 1"
                @click="move($index, 1)" />
              <el-button size="small" text type="danger" @click="removeDevice($index)">{{ t('erpLine.removeDevice') }}</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="arrange-foot">
          <span v-if="dirty" class="warn-tip">{{ t('erpLine.dirty') }}</span>
          <span v-else class="dim">{{ t('erpLine.clean') }}</span>
          <span class="spacer" />
          <el-button size="small" @click="resetLocal">{{ t('erpLine.revert') }}</el-button>
          <el-button size="small" type="primary" :disabled="!dirty" :loading="saving" @click="saveArrange">
            {{ t('erpLine.saveArrange') }}
          </el-button>
        </div>

        <div class="panel chart-panel">
          <div class="p-head">
            <h3>{{ t('erpLine.chartTitle') }}</h3>
            <span>{{ cur.finishedName || t('erpLine.noFinished') }}</span>
          </div>
          <div ref="chartEl" class="chart"></div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Bottom, Plus, Refresh, Search, Top } from '@element-plus/icons-vue'
import { lineApi, type LineDeviceRow, type ProdLineDetail, type ProdLineList, type ProdLineRow } from '@/api/lines'
import { devicesApi } from '@/api/devices'
import { useAuthStore } from '@/stores/auth'
import { useEcharts } from '@/composables/useEcharts'
import { qty } from '@/utils/format'

const { t } = useI18n()

const auth = useAuthStore()
const list = ref<ProdLineList | null>(null)
const loading = ref(false)
const saving = ref(false)
const kw = ref('')

const filtered = computed(() => {
  const rows = list.value?.rows || []
  const k = kw.value.trim().toLowerCase()
  if (!k) return rows
  return rows.filter((r) =>
    `${r.name} ${r.code} ${r.workshop || ''}`.toLowerCase().includes(k))
})

/* ---------- 新建 / 编辑 ---------- */
const dlgOpen = ref(false)
const editing = ref<ProdLineRow | null>(null)
const form = ref({ code: '', name: '', workshop: '', sortOrder: 10, remark: '', enabled: true })

function openCreate() {
  editing.value = null
  form.value = { code: '', name: '', workshop: '', sortOrder: 10, remark: '', enabled: true }
  dlgOpen.value = true
}

function openEdit(row: ProdLineRow) {
  editing.value = row
  form.value = {
    code: row.code, name: row.name, workshop: row.workshop || '',
    sortOrder: row.sortOrder, remark: row.remark || '', enabled: row.enabled
  }
  dlgOpen.value = true
}

async function submit() {
  if (!form.value.name.trim()) {
    ElMessage.warning(t('erpLine.needName'))
    return
  }
  if (!editing.value && !form.value.code.trim()) {
    ElMessage.warning(t('erpLine.needCode'))
    return
  }
  saving.value = true
  try {
    const res = editing.value
      ? await lineApi.update(editing.value.code, form.value)
      : await lineApi.create(form.value)
    if (!res.ok) {
      ElMessage.error(res.message || t('erpLine.saveFailed'))
      return
    }
    ElMessage.success(res.message || t('erpLine.saved'))
    dlgOpen.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpLine.saveFailed'))
  } finally {
    saving.value = false
  }
}

async function removeLine(row: ProdLineRow) {
  try {
    await ElMessageBox.confirm(
      t('erpLine.deleteConfirm', { name: row.name }),
      t('erpLine.deleteTitle'), { type: 'warning', confirmButtonText: t('erpLine.deleteOk'), cancelButtonText: t('erpLine.cancel') })
  } catch {
    return
  }
  const res = await lineApi.remove(row.code)
  if (!res.ok) {
    ElMessage.error(res.message || t('erpLine.deleteFailed'))
    return
  }
  ElMessage.success(t('erpLine.deleted'))
  await load()
}

/* ---------- 编排 ---------- */
const arrangeOpen = ref(false)
const cur = ref<ProdLineDetail['line'] | null>(null)
const local = ref<LineDeviceRow[]>([])
const originCodes = ref<string[]>([])
const addCode = ref<string | null>(null)
const allDevices = ref<any[]>([])
const chartEl = ref<HTMLElement | null>(null)
const chart = useEcharts()
/** 抽屉动画结束前不 init：el-drawer 的内容这时还没尺寸，init 出来是一张空白图 */
let chartReady = false
const pendingDaily = ref<{ date: string; qty: number }[]>([])

const dirty = computed(() =>
  local.value.map((d) => d.code).join(',') !== originCodes.value.join(','))

const addable = computed(() => {
  const inLine = new Set(local.value.map((d) => d.code))
  return allDevices.value.filter((d) => !inLine.has(d.code))
})

async function openArrange(row: ProdLineRow) {
  arrangeOpen.value = true
  addCode.value = null
  await loadArrange(row.code)
}

/** 抽屉展开动画结束：此时容器有真实宽高，才能 init 图表 */
function onDrawerOpened() {
  if (chartEl.value) {
    chart.init(chartEl.value)
    chartReady = true
    drawChart(pendingDaily.value)
  }
}

function onDrawerClosed() {
  chartReady = false
  chart.dispose()
}

async function loadArrange(code: string) {
  try {
    const [d, devs] = await Promise.all([lineApi.detail(code, 30), devicesApi.list()])
    if (!d.ok) {
      ElMessage.error(d.message || t('erpLine.loadFailed'))
      return
    }
    cur.value = d.line
    local.value = d.devices.map((x) => ({ ...x }))
    originCodes.value = d.devices.map((x) => x.code)
    allDevices.value = devs || []
    pendingDaily.value = d.daily || []
    if (chartReady) {
      await nextTick()
      drawChart(pendingDaily.value)
    }
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpLine.loadFailed'))
  }
}

function drawChart(rows: { date: string; qty: number }[]) {
  chart.setOption({
    grid: { left: 8, right: 14, top: 24, bottom: 4, containLabel: true },
    tooltip: { trigger: 'axis', valueFormatter: (v: number) => t('erpLine.unitPieceChart', { v: qty(v) }) },
    xAxis: { type: 'category', data: rows.map((r) => r.date) },
    yAxis: { type: 'value' },
    series: [{
      name: t('erpLine.seriesOutput'), type: 'line', smooth: true, symbolSize: 5,
      areaStyle: { opacity: 0.14 }, data: rows.map((r) => Number(r.qty))
    }]
  })
}

function addDevice() {
  const dev = allDevices.value.find((d) => d.code === addCode.value)
  if (!dev) return
  const pts = Array.isArray(dev.points) ? dev.points : []
  local.value.push({
    code: dev.code, name: dev.name, location: dev.location,
    seq: local.value.length + 1, online: !!dev.online,
    // 设备列表里带点表，直接看有没有 role=count，别让用户点了"设为成品"才被后端拒
    hasCount: pts.some((x: any) => x.role === 'count'),
    finished: false, today: 0, month: 0
  })
  addCode.value = null
}

function removeDevice(i: number) {
  local.value.splice(i, 1)
}

function move(i: number, delta: number) {
  const j = i + delta
  if (j < 0 || j >= local.value.length) return
  const arr = local.value
  const t = arr[i]
  arr[i] = arr[j]
  arr[j] = t
}

function resetLocal() {
  if (cur.value) loadArrange(cur.value.code)
}

async function saveArrange() {
  if (!cur.value) return
  saving.value = true
  try {
    const res = await lineApi.assign(cur.value.code, local.value.map((d) => d.code))
    if (!res.ok) {
      ElMessage.error(res.message || t('erpLine.saveFailed'))
      return
    }
    ElMessage.success(res.message || t('erpLine.arrangeSaved'))
    await Promise.all([loadArrange(cur.value.code), load()])
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpLine.saveFailed'))
  } finally {
    saving.value = false
  }
}

async function setFinished(row: LineDeviceRow) {
  if (!cur.value) return
  const res = await lineApi.setFinished(cur.value.code, row.code)
  if (!res.ok) {
    ElMessage.error(res.message || t('erpLine.setFailed'))
    return
  }
  ElMessage.success(res.message || t('erpLine.setDone'))
  await Promise.all([loadArrange(cur.value.code), load()])
}

async function load() {
  loading.value = true
  try {
    list.value = await lineApi.list()
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpLine.loadOnlyFailed'))
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.lines { display: flex; flex-direction: column; gap: var(--space-4); }
.kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--space-3); }
.kpi {
  background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--elev-raised); padding: var(--space-4); display: flex; flex-direction: column; gap: 2px;
}
.kpi > span { font-size: var(--text-xs); color: var(--muted); letter-spacing: .06em; }
.kpi b { font-size: var(--text-xl); font-weight: 600; }
.kpi b i { font-style: normal; font-size: var(--text-xs); color: var(--muted); margin-left: 2px; }
.kpi em { font-style: normal; font-size: var(--text-2xs); color: var(--meta); }
.tab-toolbar { display: flex; gap: 10px; margin-bottom: var(--space-3); flex-wrap: wrap; align-items: center; }
.tab-toolbar .spacer { flex: 1; }
.tip { font-size: var(--text-2xs); }
.lname { display: flex; align-items: center; gap: 6px; font-weight: 600; }
.sub { font-size: var(--text-2xs); }
.dim { color: var(--muted); }
.ml6 { margin-left: 6px; }
.up { color: var(--run); }
.empty { padding: var(--space-4) 0; text-align: center; font-size: var(--text-xs); color: var(--meta); }

.arrange { display: flex; flex-direction: column; gap: var(--space-3); }
/* 调整列三个按钮必须并排：默认 + 选择器会把它们挤成竖排，行高翻倍 */
:deep(.op-cell) .cell { display: flex; align-items: center; justify-content: flex-end; white-space: nowrap; }
:deep(.op-cell) .el-button + .el-button { margin-left: 2px; }
.hint {
  font-size: var(--text-xs); color: var(--muted); background: var(--surface-2);
  border: 1px solid var(--border-soft); border-radius: var(--radius-sm); padding: var(--space-2) var(--space-3);
  line-height: 1.6;
}
.add-row { display: flex; gap: 8px; }
.arrange-foot { display: flex; align-items: center; gap: 10px; }
.arrange-foot .spacer { flex: 1; }
.warn-tip { font-size: var(--text-2xs); color: var(--alarm); }
.chart-panel { background: var(--surface); border: 1px solid var(--border-soft); border-radius: var(--radius-md);
  padding: var(--space-3) var(--space-4); }
.p-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: var(--space-2); }
.p-head h3 { margin: 0; font-size: var(--text-md); font-weight: 600; }
.p-head span { font-size: var(--text-2xs); color: var(--meta); }
.chart { height: 200px; }
@media (max-width: 1200px) {
  .kpis { grid-template-columns: repeat(2, 1fr); }
}
</style>
