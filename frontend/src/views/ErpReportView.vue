<template>
  <div class="erp-report">
    <div class="toolbar">
      <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" range-separator="→"
        start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false" style="width:250px" @change="reload" />
      <el-select v-model="whId" placeholder="全部仓库" clearable style="width:150px" @change="reload">
        <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
      </el-select>
      <el-radio-group v-if="tab === 'trade'" v-model="side" size="small" @change="reload">
        <el-radio-button value="purchase">采购</el-radio-button>
        <el-radio-button value="sales">销售</el-radio-button>
      </el-radio-group>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <span class="spacer" />
      <span class="dim tip">{{ fromDate }} ~ {{ toDate }}</span>
      <el-button type="primary" :icon="Download" :loading="exporting" @click="doExport">导出 Excel</el-button>
    </div>

    <el-tabs v-model="tab" class="tabs" @tab-change="load">
      <!-- ==================== 经营概况 ==================== -->
      <el-tab-pane label="经营概况" name="overview">
        <div class="kpis six">
          <div class="kpi">
            <span>本期采购入库额</span>
            <b class="num">¥{{ money(ov?.purchaseAmount) }}</b>
            <em>退货已冲减 · {{ ov?.poCount || 0 }} 张采购订单</em>
          </div>
          <div class="kpi">
            <span>本期销售出库额</span>
            <b class="num">¥{{ money(ov?.salesAmount) }}</b>
            <em>退货已冲减 · {{ ov?.soCount || 0 }} 张销售订单</em>
          </div>
          <div class="kpi">
            <span>本期生产入库量</span>
            <b class="num">{{ qty(ov?.prodInQty) }}<i>件</i></b>
            <em>设备计数自动入库（各计量单位混计）</em>
          </div>
          <div class="kpi">
            <span>当前库存金额</span>
            <b class="num">¥{{ money(ov?.stockValue) }}</b>
            <em>{{ ov?.stockRows || 0 }} 个库位 · 时点数，不随区间变化</em>
          </div>
          <div class="kpi">
            <span>应收未收</span>
            <b class="num up">¥{{ money(ov?.arOutstanding) }}</b>
            <em>其中逾期 <i class="al">¥{{ money(ov?.arOverdue) }}</i></em>
          </div>
          <div class="kpi">
            <span>应付未付</span>
            <b class="num down">¥{{ money(ov?.apOutstanding) }}</b>
            <em>其中逾期 <i class="al">¥{{ money(ov?.apOverdue) }}</i></em>
          </div>
        </div>

        <div class="grid-2">
          <div class="panel">
            <div class="p-head">
              <h3>采购 / 销售趋势</h3>
              <span>按单据日期，净额（退货已冲减）</span>
            </div>
            <div ref="trendEl" class="chart"></div>
          </div>
          <div class="panel">
            <div class="p-head"><h3>客户排行</h3><span>按本期出库净额</span></div>
            <div v-if="!ov?.topCustomers?.length" class="empty">本期没有销售出库。</div>
            <div v-else class="rank">
              <div v-for="(c, i) in ov.topCustomers" :key="c.name + i" class="rank-row">
                <span class="idx" :class="{ top: i < 3 }">{{ i + 1 }}</span>
                <span class="nm" :title="c.name">{{ c.name }}</span>
                <div class="track"><div class="fill" :style="{ width: pct(c.amount, maxCustomer) }"></div></div>
                <span class="amt num">¥{{ money(c.amount) }}</span>
              </div>
            </div>
          </div>
        </div>

        <div class="grid-2">
          <div class="panel">
            <div class="p-head"><h3>供应商排行</h3><span>按本期入库净额</span></div>
            <div v-if="!ov?.topSuppliers?.length" class="empty">本期没有采购入库。</div>
            <div v-else class="rank">
              <div v-for="(c, i) in ov.topSuppliers" :key="c.name + i" class="rank-row">
                <span class="idx" :class="{ top: i < 3 }">{{ i + 1 }}</span>
                <span class="nm" :title="c.name">{{ c.name }}</span>
                <div class="track"><div class="fill green" :style="{ width: pct(c.amount, maxSupplier) }"></div></div>
                <span class="amt num">¥{{ money(c.amount) }}</span>
              </div>
            </div>
          </div>
          <div class="panel">
            <div class="p-head"><h3>商品动销</h3><span>按金额排前 8</span></div>
            <el-table :data="ov?.topSkus || []" size="small" :show-header="true" class="mini">
              <el-table-column prop="code" label="编码" width="90">
                <template #default="{ row }"><span class="num dim">{{ row.code }}</span></template>
              </el-table-column>
              <el-table-column prop="name" label="商品" min-width="140" show-overflow-tooltip />
              <el-table-column label="出库" width="96" align="right">
                <template #default="{ row }"><span class="num">{{ qty(row.outQty) }}</span></template>
              </el-table-column>
              <el-table-column label="金额" width="110" align="right">
                <template #default="{ row }"><span class="num">{{ money(row.amount) }}</span></template>
              </el-table-column>
            </el-table>
          </div>
        </div>
        <div class="panel">
          <div class="p-head">
            <h3>AI 经营解读</h3>
            <span>事实数据本地算好给模型，AI 只负责组织语言与提示风险</span>
            <span class="spacer" />
            <el-button v-if="auth.canWrite" size="small" type="primary" :loading="aiLoading"
              :disabled="!(ov && ov.trend)" @click="aiInterpret">生成解读</el-button>
          </div>
          <div v-if="aiLoading" class="ai-loading">正在结合本期采购/销售/应收/库存数据生成解读…</div>
          <div v-else-if="aiText" class="ai-text">{{ aiText }}</div>
          <div v-else-if="aiErr" class="ai-err">{{ aiErr }}</div>
          <div v-else class="ai-empty">
            点「生成解读」，AI 会基于上方数据给出经营状态总结、风险提示与建议。
            <template v-if="!auth.canWrite">（经营解读含财务数据，仅管理员/车间主任可用）</template>
          </div>
        </div>
      </el-tab-pane>

      <!-- ==================== 库存状况 ==================== -->
      <el-tab-pane label="库存状况" name="stock">
        <div class="note">
          期初 + 本期入库 − 本期出库 = 期末；库存金额按商品采购价估值。
          统计口径是<strong>单据业务日期</strong>，月底补录历史单据不会算到录单当天。
          数量合计跨计量单位无意义，只作参考，跨商品请看金额。
        </div>
        <el-table :data="st?.rows || []" v-loading="loading" stripe show-summary :summary-method="stockSummary"
          max-height="620">
          <el-table-column prop="code" label="商品编码" width="110" fixed>
            <template #default="{ row }"><span class="num">{{ row.code }}</span></template>
          </el-table-column>
          <el-table-column prop="name" label="名称" min-width="170" show-overflow-tooltip />
          <el-table-column prop="spec" label="规格" min-width="120" show-overflow-tooltip>
            <template #default="{ row }"><span class="dim">{{ row.spec || '--' }}</span></template>
          </el-table-column>
          <el-table-column prop="unit" label="单位" width="70">
            <template #default="{ row }"><span class="dim">{{ row.unit || '--' }}</span></template>
          </el-table-column>
          <el-table-column prop="warehouseName" label="仓库" width="130" show-overflow-tooltip />
          <el-table-column label="期初" width="110" align="right">
            <template #default="{ row }"><span class="num dim">{{ qty(row.opening) }}</span></template>
          </el-table-column>
          <el-table-column label="本期入库" width="110" align="right">
            <template #default="{ row }"><span class="num up">+{{ qty(row.inQty) }}</span></template>
          </el-table-column>
          <el-table-column label="本期出库" width="110" align="right">
            <template #default="{ row }"><span class="num down">-{{ qty(row.outQty) }}</span></template>
          </el-table-column>
          <el-table-column label="期末" width="110" align="right">
            <template #default="{ row }">
              <b class="num" :class="{ al: Number(row.closing) < 0 }">{{ qty(row.closing) }}</b>
            </template>
          </el-table-column>
          <el-table-column label="期末金额" width="130" align="right">
            <template #default="{ row }"><span class="num">{{ money(row.amount) }}</span></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ==================== 出入库统计 ==================== -->
      <el-tab-pane label="出入库统计" name="flows">
        <div class="kpis">
          <div class="kpi">
            <span>单据张数</span>
            <b class="num">{{ fl?.totals.bills || 0 }}<i>张</i></b>
            <em>不含作废 · 含设备自动入库</em>
          </div>
          <div class="kpi">
            <span>入库金额</span>
            <b class="num up">¥{{ money(fl?.totals.inAmount) }}</b>
            <em>数量 {{ qty(fl?.totals.inQty) }}（混计）</em>
          </div>
          <div class="kpi">
            <span>出库金额</span>
            <b class="num down">¥{{ money(fl?.totals.outAmount) }}</b>
            <em>数量 {{ qty(fl?.totals.outQty) }}（混计）</em>
          </div>
          <div class="kpi">
            <span>净额</span>
            <b class="num">¥{{ money(fl?.totals.netAmount) }}</b>
            <em>入库 − 出库（含厂内调拨）</em>
          </div>
        </div>

        <div class="panel">
          <div class="p-head"><h3>每日出入库金额</h3><span>{{ fromDate }} ~ {{ toDate }}</span></div>
          <div ref="flowEl" class="chart"></div>
        </div>

        <div class="grid-2">
          <div class="panel">
            <div class="p-head"><h3>按单据类型</h3><span>看哪类业务在动库存</span></div>
            <el-table :data="fl?.byType || []" size="small" show-summary :summary-method="typeSummary">
              <el-table-column prop="typeLabel" label="类型" min-width="100" />
              <el-table-column label="张数" width="70" align="right">
                <template #default="{ row }"><span class="num">{{ row.bills }}</span></template>
              </el-table-column>
              <el-table-column label="入库金额" width="120" align="right">
                <template #default="{ row }"><span class="num up">{{ money(row.inAmount) }}</span></template>
              </el-table-column>
              <el-table-column label="出库金额" width="120" align="right">
                <template #default="{ row }"><span class="num down">{{ money(row.outAmount) }}</span></template>
              </el-table-column>
            </el-table>
          </div>
          <div class="panel">
            <div class="p-head"><h3>按仓库</h3><span>调拨会在两个仓各记一笔</span></div>
            <el-table :data="fl?.byWarehouse || []" size="small">
              <el-table-column prop="warehouseName" label="仓库" min-width="120" show-overflow-tooltip />
              <el-table-column label="张数" width="70" align="right">
                <template #default="{ row }"><span class="num">{{ row.bills }}</span></template>
              </el-table-column>
              <el-table-column label="入库金额" width="120" align="right">
                <template #default="{ row }"><span class="num up">{{ money(row.inAmount) }}</span></template>
              </el-table-column>
              <el-table-column label="出库金额" width="120" align="right">
                <template #default="{ row }"><span class="num down">{{ money(row.outAmount) }}</span></template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <div class="panel">
          <div class="p-head"><h3>商品动销排行</h3><span>按出入库金额排前 15</span></div>
          <el-table :data="fl?.topSkus || []" size="small" max-height="360">
            <el-table-column prop="code" label="编码" width="110">
              <template #default="{ row }"><span class="num dim">{{ row.code }}</span></template>
            </el-table-column>
            <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
            <el-table-column prop="spec" label="规格" min-width="120" show-overflow-tooltip>
              <template #default="{ row }"><span class="dim">{{ row.spec || '--' }}</span></template>
            </el-table-column>
            <el-table-column label="入库数量" width="120" align="right">
              <template #default="{ row }"><span class="num up">+{{ qty(row.inQty) }}</span></template>
            </el-table-column>
            <el-table-column label="出库数量" width="120" align="right">
              <template #default="{ row }"><span class="num down">-{{ qty(row.outQty) }}</span></template>
            </el-table-column>
            <el-table-column label="金额" width="130" align="right">
              <template #default="{ row }"><span class="num">{{ money(row.amount) }}</span></template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- ==================== 采购 / 销售执行 ==================== -->
      <el-tab-pane label="采购销售" name="trade">
        <div class="kpis">
          <div class="kpi">
            <span>{{ tr?.sideLabel || '采购' }}订单</span>
            <b class="num">{{ tr?.totals.orders || 0 }}<i>张</i></b>
            <em>{{ tr?.totals.partners || 0 }} 家{{ tr?.partnerLabel || '供应商' }}</em>
          </div>
          <div class="kpi">
            <span>下单金额</span>
            <b class="num">¥{{ money(tr?.totals.orderAmount) }}</b>
            <em>按订单日期落在区间</em>
          </div>
          <div class="kpi">
            <span>已{{ tr?.side === 'sales' ? '发货' : '入库' }}金额</span>
            <b class="num up">¥{{ money(tr?.totals.executedAmount) }}</b>
            <em>执行率 {{ tr?.totals.rate ?? 0 }}%</em>
          </div>
          <div class="kpi">
            <span>未执行金额</span>
            <b class="num down">¥{{ money(tr?.totals.openAmount) }}</b>
            <em>{{ tr?.side === 'sales' ? '待发货' : '待入库' }}（含逾期）</em>
          </div>
        </div>

        <div class="panel">
          <div class="p-head"><h3>{{ tr?.partnerLabel || '供应商' }}汇总</h3><span>下单 / 已执行 / 未执行</span></div>
          <el-table :data="tr?.partners || []" size="small" show-summary :summary-method="partnerSummary">
            <el-table-column prop="partnerName" :label="tr?.partnerLabel || '供应商'" min-width="180" show-overflow-tooltip />
            <el-table-column label="订单数" width="90" align="right">
              <template #default="{ row }"><span class="num">{{ row.orders }}</span></template>
            </el-table-column>
            <el-table-column label="下单金额" width="140" align="right">
              <template #default="{ row }"><span class="num">{{ money(row.orderAmount) }}</span></template>
            </el-table-column>
            <el-table-column label="已执行" width="140" align="right">
              <template #default="{ row }"><span class="num up">{{ money(row.executedAmount) }}</span></template>
            </el-table-column>
            <el-table-column label="未执行" width="140" align="right">
              <template #default="{ row }">
                <span class="num" :class="{ down: Number(row.openAmount) > 0 }">{{ money(row.openAmount) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="执行率" width="150">
              <template #default="{ row }">
                <div class="prog">
                  <el-progress :percentage="Math.min(100, Math.max(0, Number(row.rate)))" :stroke-width="6"
                    :show-text="false" />
                  <span class="num prog-text">{{ row.rate }}%</span>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="panel">
          <div class="p-head"><h3>订单执行明细</h3><span>共 {{ tr?.orders?.length || 0 }} 张</span></div>
          <el-table :data="tr?.orders || []" size="small" max-height="420">
            <el-table-column prop="orderNo" label="订单号" width="180">
              <template #default="{ row }"><span class="num">{{ row.orderNo }}</span></template>
            </el-table-column>
            <el-table-column label="下单日期" width="110">
              <template #default="{ row }"><span class="num dim">{{ dayText(row.orderDate) }}</span></template>
            </el-table-column>
            <el-table-column prop="partnerName" :label="tr?.partnerLabel || '供应商'" min-width="160"
              show-overflow-tooltip />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="statusTag(row.status)">{{ row.statusLabel }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="下单金额" width="130" align="right">
              <template #default="{ row }"><span class="num">{{ money(row.orderAmount) }}</span></template>
            </el-table-column>
            <el-table-column label="已执行" width="130" align="right">
              <template #default="{ row }"><span class="num up">{{ money(row.executedAmount) }}</span></template>
            </el-table-column>
            <el-table-column label="未执行" width="130" align="right">
              <template #default="{ row }"><span class="num down">{{ money(row.openAmount) }}</span></template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Refresh } from '@element-plus/icons-vue'
import {
  downloadReport, reportApi,
  type FlowReport, type Overview, type StockReport, type TradeReport
} from '@/api/erpReport'
import { warehouseApi, type ErpWarehouse } from '@/api/erp'
import { useEcharts } from '@/composables/useEcharts'
import { useAuthStore } from '@/stores/auth'
import { dayText, money, qty } from '@/utils/format'

const auth = useAuthStore()

const pad = (n: number) => String(n).padStart(2, '0')
const fmt = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
const firstOfMonth = () => {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-01`
}

const range = ref<[string, string]>([firstOfMonth(), fmt(new Date())])
const fromDate = computed(() => range.value?.[0] || firstOfMonth())
const toDate = computed(() => range.value?.[1] || fmt(new Date()))

const tab = ref('overview')
const side = ref<'purchase' | 'sales'>('purchase')
const whId = ref<number | null>(null)
const warehouses = ref<ErpWarehouse[]>([])
const loading = ref(false)
const exporting = ref(false)

const ov = ref<Overview | null>(null)
const st = ref<StockReport | null>(null)
const fl = ref<FlowReport | null>(null)
const tr = ref<TradeReport | null>(null)

const trendEl = ref<HTMLElement | null>(null)
const flowEl = ref<HTMLElement | null>(null)
const trendChart = useEcharts()
const flowChart = useEcharts()

/* AI 经营解读 */
const aiLoading = ref(false)
const aiText = ref('')
const aiErr = ref('')

async function aiInterpret() {
  aiLoading.value = true
  aiErr.value = ''
  aiText.value = ''
  try {
    const r = await chatApi.interpret(fromDate.value, toDate.value)
    if (r.ok && r.content) {
      aiText.value = r.content
    } else {
      aiErr.value = r.message || 'AI 解读失败'
    }
  } catch (e: any) {
    aiErr.value = e?.message || 'AI 解读失败'
  } finally {
    aiLoading.value = false
  }
}

const maxCustomer = computed(() =>
  Math.max(1, ...(ov.value?.topCustomers || []).map((c) => Number(c.amount) || 0)))
const maxSupplier = computed(() =>
  Math.max(1, ...(ov.value?.topSuppliers || []).map((c) => Number(c.amount) || 0)))

const pct = (v: number, max: number) => `${Math.min(100, Math.max(2, (Number(v) / max) * 100))}%`

function statusTag(s: string) {
  if (s === 'DONE') return 'success'
  if (s === 'PARTIAL') return 'warning'
  if (s === 'CANCELLED') return 'info'
  if (s === 'CONFIRMED') return 'primary'
  return 'info'
}

async function load() {
  loading.value = true
  try {
    if (tab.value === 'overview') {
      ov.value = await reportApi.overview(fromDate.value, toDate.value)
      await nextTick()
      drawTrend()
    } else if (tab.value === 'stock') {
      st.value = await reportApi.stock(fromDate.value, toDate.value, whId.value)
    } else if (tab.value === 'flows') {
      fl.value = await reportApi.flows(fromDate.value, toDate.value, whId.value)
      await nextTick()
      drawFlow()
    } else {
      tr.value = await reportApi.trade(side.value, fromDate.value, toDate.value)
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '报表加载失败')
  } finally {
    loading.value = false
  }
}

function reload() {
  ov.value = null
  st.value = null
  fl.value = null
  tr.value = null
  load()
}

/* ---------- 图表 ---------- */
function drawTrend() {
  const rows = ov.value?.trend || []
  trendChart.setOption({
    grid: { left: 8, right: 12, top: 34, bottom: 4, containLabel: true },
    tooltip: { trigger: 'axis', valueFormatter: (v: number) => `¥${money(v)}` },
    legend: { right: 0, top: 0, itemWidth: 8, itemHeight: 8, data: ['采购入库', '销售出库'] },
    xAxis: { type: 'category', data: rows.map((r) => r.date.slice(5)) },
    yAxis: { type: 'value' },
    series: [
      { name: '采购入库', type: 'bar', barMaxWidth: 14, itemStyle: { borderRadius: [3, 3, 0, 0] },
        data: rows.map((r) => Number(r.purchase)) },
      { name: '销售出库', type: 'bar', barMaxWidth: 14, itemStyle: { borderRadius: [3, 3, 0, 0] },
        data: rows.map((r) => Number(r.sales)) }
    ]
  })
}

function drawFlow() {
  const rows = fl.value?.daily || []
  flowChart.setOption({
    grid: { left: 8, right: 12, top: 34, bottom: 4, containLabel: true },
    tooltip: { trigger: 'axis', valueFormatter: (v: number) => `¥${money(v)}` },
    legend: { right: 0, top: 0, itemWidth: 8, itemHeight: 8, data: ['入库金额', '出库金额'] },
    xAxis: { type: 'category', data: rows.map((r) => String(r.date).slice(5)) },
    yAxis: { type: 'value' },
    series: [
      { name: '入库金额', type: 'line', smooth: true, symbolSize: 5,
        areaStyle: { opacity: 0.12 }, data: rows.map((r) => Number(r.inAmount)) },
      { name: '出库金额', type: 'line', smooth: true, symbolSize: 5,
        areaStyle: { opacity: 0.12 }, data: rows.map((r) => Number(r.outAmount)) }
    ]
  })
}

/* ---------- 合计行 ---------- */
function stockSummary({ columns }: any) {
  return columns.map((_c: any, i: number) => {
    if (i === 0) return `合计（${st.value?.totals.skuCount || 0} 项）`
    // 数量列不合计（kg/件/套相加没有意义），只合计期末金额
    if (i === 9) return money(st.value?.totals.amount)
    return ''
  })
}

function typeSummary({ columns }: any) {
  return columns.map((_c: any, i: number) => {
    if (i === 0) return '合计'
    if (i === 1) return String(fl.value?.totals.bills || 0)
    if (i === 2) return money(fl.value?.totals.inAmount)
    if (i === 3) return money(fl.value?.totals.outAmount)
    return ''
  })
}

function partnerSummary({ columns }: any) {
  return columns.map((_c: any, i: number) => {
    if (i === 0) return '合计'
    if (i === 1) return String(tr.value?.totals.orders || 0)
    if (i === 2) return money(tr.value?.totals.orderAmount)
    if (i === 3) return money(tr.value?.totals.executedAmount)
    if (i === 4) return money(tr.value?.totals.openAmount)
    if (i === 5) return `${tr.value?.totals.rate ?? 0}%`
    return ''
  })
}

/* ---------- 导出 ---------- */
async function doExport() {
  exporting.value = true
  try {
    const type = tab.value as 'overview' | 'stock' | 'flows' | 'trade'
    await downloadReport(type, fromDate.value, toDate.value, {
      warehouseId: type === 'stock' || type === 'flows' ? whId.value : null,
      side: type === 'trade' ? side.value : undefined
    })
    ElMessage.success('已导出 Excel')
  } catch (e: any) {
    ElMessage.error(e?.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

onMounted(async () => {
  try {
    warehouses.value = await warehouseApi.list()
  } catch {
    warehouses.value = []
  }
  await nextTick()
  if (trendEl.value) trendChart.init(trendEl.value)
  if (flowEl.value) flowChart.init(flowEl.value)
  await load()
})
</script>

<style scoped>
.erp-report { display: flex; flex-direction: column; gap: var(--space-3); }
.toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.toolbar .spacer { flex: 1; }
.toolbar .tip { font-size: var(--text-2xs); white-space: nowrap; }
.tabs { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--elev-raised); padding: var(--space-3) var(--space-4) var(--space-4); }
.kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--space-3); margin-bottom: var(--space-3); }
.kpis.six { grid-template-columns: repeat(3, 1fr); }
.kpi { background: var(--surface-2); border: 1px solid var(--border-soft); border-radius: var(--radius-md);
  padding: var(--space-3) var(--space-4); display: flex; flex-direction: column; gap: 2px; }
.kpi > span { font-size: var(--text-xs); color: var(--muted); letter-spacing: .06em; }
.kpi b { font-size: var(--text-xl); font-weight: 600; }
.kpi b i { font-style: normal; font-size: var(--text-xs); color: var(--muted); margin-left: 2px; }
.kpi em { font-style: normal; font-size: var(--text-2xs); color: var(--meta); }
.kpi em i { font-style: normal; }
.up { color: var(--run); }
.down { color: var(--alarm); }
.al { color: var(--alarm); }
.dim { color: var(--muted); }
.grid-2 { display: grid; grid-template-columns: 1.4fr 1fr; gap: var(--space-3); margin-bottom: var(--space-3); }
.panel { background: var(--surface); border: 1px solid var(--border-soft); border-radius: var(--radius-md);
  padding: var(--space-3) var(--space-4); }
.p-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: var(--space-2); }
.p-head h3 { margin: 0; font-size: var(--text-md); font-weight: 600; }
.p-head span { font-size: var(--text-2xs); color: var(--meta); }
.chart { height: 230px; }
.note { font-size: var(--text-xs); color: var(--muted); background: var(--surface-2);
  border: 1px solid var(--border-soft); border-radius: var(--radius-sm); padding: var(--space-2) var(--space-3);
  margin-bottom: var(--space-3); }
.empty { font-size: var(--text-xs); color: var(--meta); padding: var(--space-4) 0; text-align: center; }
.rank { display: flex; flex-direction: column; gap: 7px; }
.rank-row { display: grid; grid-template-columns: 18px minmax(70px, 1.1fr) 2fr auto; gap: 8px; align-items: center; }
.rank-row .idx { font-size: var(--text-2xs); color: var(--meta); text-align: center; }
.rank-row .idx.top { color: var(--accent); font-weight: 600; }
.rank-row .nm { font-size: var(--text-xs); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rank-row .track { height: 6px; background: var(--surface-3); border-radius: 3px; overflow: hidden; }
.rank-row .fill { height: 100%; background: var(--accent); border-radius: 3px; }
.rank-row .fill.green { background: var(--run); }
.rank-row .amt { font-size: var(--text-2xs); color: var(--muted); }
.mini { --el-table-border-color: transparent; }
.prog { display: flex; align-items: center; gap: 8px; }
.prog :deep(.el-progress) { flex: 1; min-width: 0; }
.prog-text { font-size: var(--text-2xs); color: var(--muted); white-space: nowrap; }
.p-head .spacer { flex: 1; }
.ai-text { font-size: var(--text-xs); line-height: 1.9; white-space: pre-wrap; }
.ai-empty, .ai-loading, .ai-err { font-size: var(--text-xs); color: var(--meta); padding: var(--space-2) 0; }
.ai-err { color: var(--alarm); }
.ai-loading { color: var(--muted); }
@media (max-width: 1200px) {
  .kpis, .kpis.six { grid-template-columns: repeat(2, 1fr); }
  .grid-2 { grid-template-columns: 1fr; }
}
</style>
