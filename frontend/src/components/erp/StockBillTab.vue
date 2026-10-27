<template>
  <div>
    <div class="tab-toolbar">
      <el-select v-model="type" placeholder="全部类型" clearable style="width:150px" @change="reload">
        <el-option v-for="t in types" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
      <el-date-picker
        v-model="range"
        type="daterange"
        unlink-panels
        range-separator="→"
        start-placeholder="开始日"
        end-placeholder="结束日"
        value-format="YYYY-MM-DD"
        style="width:240px"
        @change="reload"
      />
      <el-input
        v-model="kw"
        placeholder="单号 / 备注 / 往来单位 / 设备"
        clearable
        :prefix-icon="Search"
        style="width:230px"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-checkbox v-model="withProd" @change="reload">显示生产入库</el-checkbox>
      <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
      <span class="spacer" />
      <el-button v-if="auth.canWrite" type="primary" :icon="Plus" @click="emit('new-bill')">新建单据</el-button>
    </div>

    <div class="trend">
      <div class="trend-head">
        <span>近 14 天出入库</span>
        <span class="dim">不含调拨（厂内搬货不算进出）</span>
      </div>
      <div ref="chartEl" class="trend-body"></div>
    </div>

    <el-table :data="rows" v-loading="loading" stripe @row-dblclick="openDetail">
      <el-table-column label="单号" min-width="165">
        <template #default="{ row }">
          <a class="bill-no num" :class="{ voided: row.voided }" @click="openDetail(row)">{{ row.billNo }}</a>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="92">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" :type="tagType(row.type)">{{ row.typeLabel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="往来 / 来源" min-width="112" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.partnerName">{{ row.partnerName }}</span>
          <span v-else-if="row.deviceCode" class="num dim">{{ row.deviceCode }}</span>
          <span v-else class="dim">--</span>
        </template>
      </el-table-column>
      <el-table-column label="仓库" min-width="172">
        <template #default="{ row }">
          <template v-if="row.type === 'TRANSFER'">
            <span class="dim">{{ row.srcWarehouseName || '--' }}</span>
            <span class="arrow">→</span>{{ row.warehouseName || '--' }}
          </template>
          <span v-else>{{ row.warehouseName || '--' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="数量" width="108" align="right">
        <template #default="{ row }">
          <span class="num" :class="signClass(row)">{{ signOf(row) }}{{ qty(row.qty) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="金额" width="104" align="right">
        <template #default="{ row }"><span class="num dim">{{ row.amount ? money(row.amount) : '--' }}</span></template>
      </el-table-column>
      <el-table-column label="时间" width="150">
        <template #default="{ row }"><span class="num dim">{{ dt(row.createdAt) }}</span></template>
      </el-table-column>
      <el-table-column label="状态" width="74">
        <template #default="{ row }">
          <el-tag v-if="row.voided" size="small" type="danger" effect="plain">已作废</el-tag>
          <span v-else class="dim">有效</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" align="right" class-name="op-col">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="openDetail(row)">详情</el-button>
          <el-button v-if="auth.canWrite && !row.voided" size="small" text type="danger" @click="voidBill(row)">作废</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        layout="total, sizes, prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        :page-sizes="[20, 50, 100]"
        @current-change="onPage"
        @size-change="onSize"
      />
    </div>

    <el-drawer v-model="detailOpen" title="单据详情" size="620px">
      <div v-if="detail" class="detail">
        <div class="d-head">
          <div>
            <div class="d-no num">{{ detail.bill.billNo }}</div>
            <div class="d-sub">
              <el-tag size="small" effect="plain" :type="tagType(detail.bill.type)">{{ detail.bill.typeLabel }}</el-tag>
              <span class="dim">{{ dt(detail.bill.createdAt) }}</span>
              <el-tag v-if="detail.bill.voided" size="small" type="danger" effect="plain">已作废</el-tag>
            </div>
          </div>
          <div class="d-amount">
            <span>合计金额</span>
            <b class="num">¥{{ money(detail.bill.amount) }}</b>
          </div>
        </div>

        <div class="d-meta">
          <div><span>仓库</span><b v-if="detail.bill.type === 'TRANSFER'">{{ detail.bill.srcWarehouseName }} → {{ detail.bill.warehouseName }}</b><b v-else>{{ detail.bill.warehouseName || '--' }}</b></div>
          <div><span>往来单位</span><b>{{ detail.bill.partnerName || '--' }}</b></div>
          <div><span>来源设备</span><b class="num">{{ detail.bill.deviceCode || '--' }}</b></div>
          <div><span>制单人</span><b>{{ detail.bill.operator || '--' }}</b></div>
          <div v-if="detail.bill.voided"><span>作废人</span><b>{{ detail.bill.voidedBy || '--' }}</b></div>
          <div class="span2"><span>备注</span><b>{{ detail.bill.remark || '--' }}</b></div>
        </div>

        <table class="lines">
          <thead>
            <tr><th>#</th><th>商品</th><th>仓库</th><th class="r">数量</th><th class="r">单价</th><th class="r">金额</th></tr>
          </thead>
          <tbody>
            <tr v-for="l in detail.lines" :key="l.lineNo">
              <td class="dim">{{ l.lineNo }}</td>
              <td>
                <span class="num">{{ l.skuCode }}</span> {{ l.skuName }}
                <span v-if="l.spec" class="dim">{{ l.spec }}</span>
              </td>
              <td>{{ l.warehouseName }}</td>
              <td class="r num" :class="l.qty >= 0 ? 'plus' : 'minus'">{{ l.qty > 0 ? '+' : '' }}{{ qty(l.qty) }}</td>
              <td class="r num dim">{{ l.unitPrice == null ? '--' : money(l.unitPrice) }}</td>
              <td class="r num">{{ l.amount == null ? '--' : money(l.amount) }}</td>
            </tr>
          </tbody>
        </table>

        <div v-if="auth.canWrite && !detail.bill.voided" class="d-foot">
          <el-button type="danger" plain @click="voidBill(detail.bill)">作废整单</el-button>
          <span class="dim">作废不删数据；若货已领用，请改做反向调整单</span>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import {
  stockApi, type StockBillLine, type StockBillRow
} from '@/api/erp'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { useEcharts } from '@/composables/useEcharts'
import { dt, money, qty } from '@/utils/format'

const emit = defineEmits<{ 'new-bill': []; changed: [] }>()

const auth = useAuthStore()
const theme = useThemeStore()
const rows = ref<StockBillRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const type = ref('')
const range = ref<[string, string] | null>(null)
const kw = ref('')
const withProd = ref(false)
const loading = ref(false)
const types = ref<{ value: string; label: string }[]>([])

const detailOpen = ref(false)
const detail = ref<{ bill: StockBillRow; lines: StockBillLine[] } | null>(null)

const chartEl = ref<HTMLElement>()
const chart = useEcharts()

const TYPE_TAG: Record<string, string> = {
  PO_IN: 'success', PROD_IN: 'success', OTHER_IN: 'success',
  SO_OUT: 'danger', OTHER_OUT: 'danger',
  TRANSFER: 'primary', STOCKTAKE: 'warning'
}
const IN_TYPES = ['PO_IN', 'PROD_IN', 'OTHER_IN']

const tagType = (t: string) => (TYPE_TAG[t] || 'info') as any
const signOf = (r: StockBillRow) => (IN_TYPES.includes(r.type) ? '+' : r.type === 'TRANSFER' || r.type === 'STOCKTAKE' ? '' : '-')
const signClass = (r: StockBillRow) => (IN_TYPES.includes(r.type) ? 'plus' : r.type === 'TRANSFER' || r.type === 'STOCKTAKE' ? '' : 'minus')

function toTs(day: string, end = false): number {
  const [y, m, d] = day.split('-').map(Number)
  return new Date(y, m - 1, d, end ? 23 : 0, end ? 59 : 0, end ? 59 : 0).getTime()
}

async function load() {
  loading.value = true
  try {
    const r = await stockApi.bills({
      type: type.value,
      from: range.value ? toTs(range.value[0]) : undefined,
      to: range.value ? toTs(range.value[1], true) : undefined,
      withProd: withProd.value,
      kw: kw.value,
      page: page.value,
      size: size.value
    })
    rows.value = r.rows || []
    total.value = r.total || 0
  } catch (e: any) {
    ElMessage.error(e?.message || '单据加载失败')
  } finally {
    loading.value = false
  }
}

function reload() {
  page.value = 1
  load()
}

function onPage(p: number) {
  page.value = p
  load()
}

function onSize(s: number) {
  size.value = s
  page.value = 1
  load()
}

async function openDetail(row: StockBillRow) {
  try {
    const r = await stockApi.billDetail(row.billNo)
    detail.value = { bill: r.bill, lines: r.lines }
    detailOpen.value = true
  } catch (e: any) {
    ElMessage.error(e?.message || '详情加载失败')
  }
}

async function voidBill(row: StockBillRow) {
  try {
    await ElMessageBox.confirm(
      `作废后「${row.billNo}」不再计入库存，单据仍保留可查。确定作废？`,
      '作废单据',
      { type: 'warning', confirmButtonText: '作废', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const r = await stockApi.voidBill(row.billNo)
    if (!r.ok) {
      ElMessage.error(r.message || '作废失败')
      return
    }
    ElMessage.success(r.message || '已作废')
    detailOpen.value = false
    await load()
    emit('changed')
  } catch (e: any) {
    ElMessage.error(e?.message || '作废失败')
  }
}

async function drawChart() {
  try {
    const data = await stockApi.trend(14)
    if (!chartEl.value) return
    const css = (n: string) => getComputedStyle(document.documentElement).getPropertyValue(n).trim()
    chart.setOption({
      color: [css('--run'), css('--idle')],
      grid: { left: 8, right: 12, top: 24, bottom: 4, containLabel: true },
      tooltip: { trigger: 'axis' },
      legend: { right: 0, top: 0, itemWidth: 8, itemHeight: 8, data: ['入库', '出库'] },
      xAxis: { type: 'category', data: data.map((d) => d.date) },
      yAxis: { type: 'value', splitNumber: 3 },
      series: [
        { name: '入库', type: 'bar', barMaxWidth: 12, itemStyle: { borderRadius: [3, 3, 0, 0] }, data: data.map((d) => Number(d.in)) },
        { name: '出库', type: 'bar', barMaxWidth: 12, itemStyle: { borderRadius: [3, 3, 0, 0] }, data: data.map((d) => Number(d.out)) }
      ]
    })
  } catch {
    /* 图表失败不影响列表 */
  }
}

onMounted(async () => {
  try {
    const t = await stockApi.types()
    types.value = t.all
  } catch {
    types.value = []
  }
  await nextTick()
  chart.init(chartEl.value as HTMLElement)
  await Promise.all([load(), drawChart()])
})

/* 深浅色切换后重取色重画（图表颜色取自 CSS 令牌，不写死色值） */
watch(() => theme.light, () => { drawChart() })

defineExpose({ load })
</script>

<style scoped>
.tab-toolbar { display: flex; gap: 10px; margin-bottom: var(--space-3); flex-wrap: wrap; align-items: center; }
.tab-toolbar .spacer { flex: 1; }
.trend {
  background: var(--surface-2); border: 1px solid var(--border-soft);
  border-radius: var(--radius-md); padding: var(--space-3) var(--space-4) var(--space-2);
  margin-bottom: var(--space-3);
}
.trend-head { display: flex; justify-content: space-between; align-items: baseline; font-size: var(--text-xs); color: var(--fg-2); }
.trend-head .dim { color: var(--meta); font-size: var(--text-2xs); }
.trend-body { height: 132px; }
.bill-no { color: var(--accent); cursor: pointer; text-decoration: none; }
.bill-no:hover { text-decoration: underline; }
.bill-no.voided { color: var(--meta); text-decoration: line-through; }
.arrow { color: var(--meta); margin: 0 6px; }
.dim { color: var(--muted); }
.plus { color: var(--run); }
.minus { color: var(--alarm); }
:deep(.op-col .el-button + .el-button) { margin-left: 2px; }
.pager { display: flex; justify-content: flex-end; padding-top: var(--space-3); }
.detail { display: flex; flex-direction: column; gap: var(--space-4); }
.d-head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--space-3); }
.d-no { font-size: var(--text-lg); font-weight: 600; }
.d-sub { display: flex; gap: var(--space-2); align-items: center; margin-top: 6px; font-size: var(--text-xs); }
.d-amount { text-align: right; }
.d-amount span { display: block; font-size: var(--text-2xs); color: var(--muted); }
.d-amount b { font-size: var(--text-xl); color: var(--fg); }
.d-meta { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-2) var(--space-4); }
.d-meta > div { display: flex; gap: 10px; font-size: var(--text-sm); }
.d-meta span { color: var(--muted); min-width: 60px; }
.d-meta b { font-weight: 500; }
.d-meta .span2 { grid-column: span 2; }
.lines { width: 100%; border-collapse: collapse; font-size: var(--text-sm); }
.lines th { text-align: left; font-weight: 400; font-size: var(--text-2xs); color: var(--meta); padding: 6px 8px; border-bottom: 1px solid var(--border); }
.lines td { padding: 8px; border-bottom: 1px solid var(--border-soft); }
.lines .r { text-align: right; }
.d-foot { display: flex; align-items: center; gap: var(--space-3); }
.d-foot .dim { font-size: var(--text-2xs); }
</style>
