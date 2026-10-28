<template>
  <div class="lines">
    <div class="kpis">
      <div class="kpi">
        <span>产线</span>
        <b class="num">{{ list?.summary.lines || 0 }}<i>条</i></b>
        <em>{{ list?.summary.withFinished || 0 }} 条有成品计数点</em>
      </div>
      <div class="kpi">
        <span>产线设备</span>
        <b class="num">{{ list?.summary.devices || 0 }}<i>台</i></b>
        <em>在线 {{ list?.summary.online || 0 }} 台</em>
      </div>
      <div class="kpi">
        <span>今日产线产量</span>
        <b class="num up">{{ qty(list?.summary.today) }}<i>件</i></b>
        <em>各线成品计数设备合计 · 本月 {{ qty(list?.summary.month) }}</em>
      </div>
      <div class="kpi">
        <span>未分配设备</span>
        <b class="num">{{ list?.unassigned || 0 }}<i>台</i></b>
        <em>还没挂到任何产线</em>
      </div>
    </div>

    <el-card shadow="never">
      <div class="tab-toolbar">
        <el-input v-model="kw" placeholder="产线名称 / 编码 / 车间" clearable :prefix-icon="Search"
          style="width:220px" />
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <span class="spacer" />
        <span class="dim tip">成品计数点默认取工位序号最大的一台，可单独指定</span>
        <el-button v-if="auth.canWrite" type="primary" :icon="Plus" @click="openCreate">新建产线</el-button>
      </div>

      <el-table :data="filtered" v-loading="loading" stripe>
        <el-table-column label="产线" min-width="200">
          <template #default="{ row }">
            <div class="lname">
              {{ row.name }}
              <el-tag v-if="!row.enabled" size="small" type="info" effect="plain">停用</el-tag>
            </div>
            <div class="dim sub">{{ row.code }} · {{ row.workshop || '未填车间' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="设备" width="130">
          <template #default="{ row }">
            <span class="num">{{ row.deviceCount }}</span>
            <span class="dim"> 台 · 在线 {{ row.onlineCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="成品计数点" min-width="230">
          <template #default="{ row }">
            <template v-if="row.finishedCode">
              <span class="num">{{ row.finishedCode }}</span>
              <el-tag size="small" :type="row.finishedAuto ? 'info' : 'success'" effect="plain" class="ml6">
                {{ row.finishedAuto ? '自动（最后一台）' : '已指定' }}
              </el-tag>
              <el-tag v-if="!row.finishedHasCount" size="small" type="warning" effect="plain" class="ml6">
                无计数点
              </el-tag>
            </template>
            <span v-else class="dim">尚未编排设备</span>
          </template>
        </el-table-column>
        <el-table-column label="今日产量" width="120" align="right">
          <template #default="{ row }">
            <b v-if="row.finishedCode" class="num">{{ qty(row.today) }}</b>
            <span v-else class="dim">--</span>
          </template>
        </el-table-column>
        <el-table-column label="本月" width="110" align="right">
          <template #default="{ row }">
            <span class="num dim">{{ row.finishedCode ? qty(row.month) : '--' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="190" align="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openArrange(row)">编排</el-button>
            <el-button v-if="auth.canWrite" size="small" text @click="openEdit(row)">编辑</el-button>
            <el-button v-if="auth.canWrite" size="small" text type="danger" @click="removeLine(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!loading && !filtered.length" class="empty">
        还没有产线。新建一条产线，把设备按工位顺序编排进来，最后一台机器的计数就是整条线的成品产量。
      </div>
    </el-card>

    <!-- 新建 / 编辑 -->
    <el-dialog v-model="dlgOpen" :title="editing ? '编辑产线' : '新建产线'" width="520px"
      :close-on-click-modal="false">
      <el-form label-width="88px">
        <el-form-item label="产线编码">
          <el-input v-model="form.code" :disabled="!!editing" placeholder="如 LINE-01" />
        </el-form-item>
        <el-form-item label="产线名称">
          <el-input v-model="form.name" placeholder="如 装配一线" />
        </el-form-item>
        <el-form-item label="所属车间">
          <el-input v-model="form.workshop" placeholder="选填，如 总装车间" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" :step="10" :controls="false" style="width:120px" />
          <span class="dim ml6">数字越小越靠前</span>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
          <span class="dim ml6">停用后大屏不显示这条线</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlgOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 编排抽屉 -->
    <el-drawer v-model="arrangeOpen" :size="780" :title="cur ? `编排设备 · ${cur.name}` : '编排设备'"
      :destroy-on-close="true" @opened="onDrawerOpened" @closed="onDrawerClosed">
      <div v-if="cur" class="arrange">
        <div class="hint">
          工位顺序从上到下；<b>成品计数点</b>默认取最后一行（序号最大），也可以单独指定某台。
          产线产量只取自成品计数设备，机台自己的计数不受影响。
        </div>

        <div class="add-row">
          <el-select v-model="addCode" placeholder="选择要加入本线的设备" filterable clearable style="flex:1">
            <el-option v-for="d in addable" :key="d.code" :label="`${d.name || d.code}（${d.code}）`"
              :value="d.code" />
          </el-select>
          <el-button :icon="Plus" :disabled="!addCode" @click="addDevice">加入</el-button>
        </div>

        <el-table :data="local" size="small" class="dev-table" max-height="320">
          <el-table-column label="工位" width="64">
            <template #default="{ $index }"><span class="num">{{ $index + 1 }}</span></template>
          </el-table-column>
          <el-table-column label="设备" min-width="180">
            <template #default="{ row }">
              <div>{{ row.name || row.code }}</div>
              <div class="dim sub">{{ row.code }}<span v-if="row.location"> · {{ row.location }}</span></div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="row.online ? 'success' : 'info'" effect="plain">
                {{ row.online ? '在线' : '离线' }}
              </el-tag>
              <el-tag v-if="!row.hasCount" size="small" type="warning" effect="plain" class="ml6">无计数点</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="今日" width="86" align="right">
            <template #default="{ row }"><span class="num">{{ qty(row.today) }}</span></template>
          </el-table-column>
          <el-table-column label="成品点" width="78" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.finished" size="small" type="success">成品</el-tag>
              <el-button v-else size="small" text type="primary" :disabled="dirty || !row.hasCount"
                @click="setFinished(row)">设为</el-button>
            </template>
          </el-table-column>
          <el-table-column label="调整" width="152" align="right" class-name="op-cell">
            <template #default="{ $index }">
              <el-button size="small" text :icon="Top" title="上移" :disabled="$index === 0"
                @click="move($index, -1)" />
              <el-button size="small" text :icon="Bottom" title="下移" :disabled="$index === local.length - 1"
                @click="move($index, 1)" />
              <el-button size="small" text type="danger" @click="removeDevice($index)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="arrange-foot">
          <span v-if="dirty" class="warn-tip">顺序/设备有改动，保存后生效</span>
          <span v-else class="dim">当前编排已保存</span>
          <span class="spacer" />
          <el-button size="small" @click="resetLocal">还原</el-button>
          <el-button size="small" type="primary" :disabled="!dirty" :loading="saving" @click="saveArrange">
            保存编排
          </el-button>
        </div>

        <div class="panel chart-panel">
          <div class="p-head">
            <h3>近 30 天产线产量</h3>
            <span>{{ cur.finishedName || '未设成品计数点' }}</span>
          </div>
          <div ref="chartEl" class="chart"></div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Bottom, Plus, Refresh, Search, Top } from '@element-plus/icons-vue'
import { lineApi, type LineDeviceRow, type ProdLineDetail, type ProdLineList, type ProdLineRow } from '@/api/lines'
import { devicesApi } from '@/api/devices'
import { useAuthStore } from '@/stores/auth'
import { useEcharts } from '@/composables/useEcharts'
import { qty } from '@/utils/format'

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
    ElMessage.warning('请填产线名称')
    return
  }
  if (!editing.value && !form.value.code.trim()) {
    ElMessage.warning('请填产线编码')
    return
  }
  saving.value = true
  try {
    const res = editing.value
      ? await lineApi.update(editing.value.code, form.value)
      : await lineApi.create(form.value)
    if (!res.ok) {
      ElMessage.error(res.message || '保存失败')
      return
    }
    ElMessage.success(res.message || '已保存')
    dlgOpen.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function removeLine(row: ProdLineRow) {
  try {
    await ElMessageBox.confirm(
      `删除产线「${row.name}」？线上设备会保留在设备列表里（需先移出本线）。`,
      '删除产线', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch {
    return
  }
  const res = await lineApi.remove(row.code)
  if (!res.ok) {
    ElMessage.error(res.message || '删除失败')
    return
  }
  ElMessage.success('已删除')
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
      ElMessage.error(d.message || '产线加载失败')
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
    ElMessage.error(e?.message || '产线加载失败')
  }
}

function drawChart(rows: { date: string; qty: number }[]) {
  chart.setOption({
    grid: { left: 8, right: 14, top: 24, bottom: 4, containLabel: true },
    tooltip: { trigger: 'axis', valueFormatter: (v: number) => `${qty(v)} 件` },
    xAxis: { type: 'category', data: rows.map((r) => r.date) },
    yAxis: { type: 'value' },
    series: [{
      name: '产量', type: 'line', smooth: true, symbolSize: 5,
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
      ElMessage.error(res.message || '保存失败')
      return
    }
    ElMessage.success(res.message || '已保存编排')
    await Promise.all([loadArrange(cur.value.code), load()])
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function setFinished(row: LineDeviceRow) {
  if (!cur.value) return
  const res = await lineApi.setFinished(cur.value.code, row.code)
  if (!res.ok) {
    ElMessage.error(res.message || '设置失败')
    return
  }
  ElMessage.success(res.message || '已设置')
  await Promise.all([loadArrange(cur.value.code), load()])
}

async function load() {
  loading.value = true
  try {
    list.value = await lineApi.list()
  } catch (e: any) {
    ElMessage.error(e?.message || '加载失败')
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
