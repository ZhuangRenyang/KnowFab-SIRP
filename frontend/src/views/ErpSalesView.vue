<template>
  <div class="sales">
    <div class="kpis">
      <div class="kpi">
        <span>本月销售额</span>
        <b class="num">¥{{ money(stats?.monthAmount) }}</b>
        <em>不含已取消订单 · 按下单日期</em>
      </div>
      <div class="kpi">
        <span>待发货订单</span>
        <b class="num">{{ stats?.openCount || 0 }}<i>单</i></b>
        <em>已确认 + 部分发货</em>
      </div>
      <div class="kpi">
        <span>部分发货</span>
        <b class="num">{{ stats?.partialCount || 0 }}<i>单</i></b>
        <em>还有货没发完</em>
      </div>
      <div class="kpi" :class="{ hot: (stats?.overdueCount || 0) > 0 }">
        <span>逾期未发</span>
        <b class="num">{{ stats?.overdueCount || 0 }}<i>单</i></b>
        <em>{{ (stats?.overdueCount || 0) > 0 ? '已过预计发货日，去催单' : '没有超期订单' }}</em>
      </div>
    </div>

    <el-card shadow="never">
      <div class="tab-toolbar">
        <el-select v-model="status" placeholder="全部状态" clearable style="width:130px" @change="reload">
          <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-select v-model="customerId" placeholder="全部客户" clearable filterable style="width:170px" @change="reload">
          <el-option v-for="p in customers" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <el-date-picker
          v-model="range" type="daterange" unlink-panels range-separator="→"
          start-placeholder="下单起" end-placeholder="下单止"
          value-format="YYYY-MM-DD" style="width:240px" @change="reload"
        />
        <el-input
          v-model="kw" placeholder="单号 / 客户 / 备注" clearable :prefix-icon="Search"
          style="width:200px" @keyup.enter="reload" @clear="reload"
        />
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
        <span class="spacer" />
        <el-button v-if="auth.canWrite" type="primary" :icon="Plus" @click="openNew">新建订单</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" stripe @row-dblclick="openDetail">
        <el-table-column label="订单号" min-width="165">
          <template #default="{ row }">
            <a class="order-no num" @click="openDetail(row)">{{ row.orderNo }}</a>
          </template>
        </el-table-column>
        <el-table-column label="客户" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.customerName || '--' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="tagOf(row.status)">{{ row.statusLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="订货数量" width="100" align="right">
          <template #default="{ row }"><span class="num">{{ qty(row.totalQty) }}</span></template>
        </el-table-column>
        <el-table-column label="金额" width="112" align="right">
          <template #default="{ row }"><span class="num">{{ row.totalAmount ? money(row.totalAmount) : '--' }}</span></template>
        </el-table-column>
        <el-table-column label="发货进度" width="168">
          <template #default="{ row }">
            <div class="prog">
              <el-progress :percentage="Math.min(100, Math.max(0, row.progress))" :stroke-width="6" :show-text="false" />
              <span class="num prog-text">{{ qty(row.shippedQty) }}/{{ qty(row.totalQty) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="下单" width="100">
          <template #default="{ row }"><span class="num dim">{{ dayText(row.orderDate) }}</span></template>
        </el-table-column>
        <el-table-column label="预计发货" width="104">
          <template #default="{ row }">
            <span class="num" :class="row.overdue ? 'overdue' : 'dim'">{{ row.deliverDate ? dayText(row.deliverDate) : '--' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="196" align="right" class-name="op-col">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="auth.canWrite && canShip(row)" size="small" text
              @click="openShip(row, 'ship')">发货</el-button>
            <el-dropdown v-if="auth.canWrite && row.status !== 'CANCELLED'" trigger="click" @command="(c: string) => onCommand(c, row)">
              <el-button size="small" text>更多<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-if="row.status === 'DRAFT'" command="edit">编辑</el-dropdown-item>
                  <el-dropdown-item v-if="row.status === 'DRAFT'" command="confirm">确认订单</el-dropdown-item>
                  <el-dropdown-item v-if="row.shippedQty > 0" command="return">客户退货</el-dropdown-item>
                  <el-dropdown-item
                    v-if="row.status === 'DRAFT' || (row.status === 'CONFIRMED' && row.shippedQty <= 0)"
                    command="cancel" divided
                  >取消订单</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          layout="total, sizes, prev, pager, next" :total="total" :page-size="size" :current-page="page"
          :page-sizes="[20, 50, 100]" @current-change="onPage" @size-change="onSize"
        />
      </div>
    </el-card>

    <SalesOrderDialog v-model="dlgOpen" :order-no="editingNo" @saved="refresh" />
    <SalesShipDialog v-model="shipOpen" :order-no="shipNo" :mode="shipMode" @saved="refresh" />

    <el-drawer v-model="detailOpen" title="销售订单" size="680px">
      <div v-if="detail" class="detail">
        <div class="d-head">
          <div>
            <div class="d-no num">{{ detail.order.orderNo }}</div>
            <div class="d-sub">
              <el-tag size="small" effect="plain" :type="tagOf(detail.order.status)">{{ detail.order.statusLabel }}</el-tag>
              <span class="dim">{{ detail.order.customerName }}</span>
            </div>
          </div>
          <div class="d-amount">
            <span>订单金额</span>
            <b class="num">¥{{ money(detail.order.totalAmount) }}</b>
          </div>
        </div>

        <div class="d-prog">
          <el-progress :percentage="Math.min(100, Math.max(0, detail.order.progress))" :stroke-width="8" />
          <span class="dim">已发货 {{ qty(detail.order.shippedQty) }} / 订货 {{ qty(detail.order.totalQty) }}</span>
        </div>

        <div class="d-meta">
          <div><span>下单日期</span><b class="num">{{ dayText(detail.order.orderDate) }}</b></div>
          <div><span>预计发货</span><b class="num">{{ detail.order.deliverDate ? dayText(detail.order.deliverDate) : '--' }}</b></div>
          <div><span>制单人</span><b>{{ detail.order.operator || '--' }}</b></div>
          <div><span>最后更新</span><b class="num">{{ dt(detail.order.updatedAt) }}</b></div>
          <div class="span2"><span>备注</span><b>{{ detail.order.remark || '--' }}</b></div>
        </div>

        <div class="ar-box">
          <div class="ar-item">
            <span>已发货金额（应收）</span>
            <b class="num">¥{{ money(detail.order.shippedAmount) }}</b>
          </div>
          <div class="ar-item">
            <span>未发货金额</span>
            <b class="num dim">¥{{ money(Number(detail.order.totalAmount) - Number(detail.order.shippedAmount)) }}</b>
          </div>
          <div class="ar-tip">收款单与账龄在「财务」里做；这里只按发货计价。</div>
        </div>

        <table class="lines">
          <thead>
            <tr><th>#</th><th>商品</th><th class="r">订货</th><th class="r">已发货</th><th class="r">未发货</th><th class="r">单价</th><th class="r">金额</th></tr>
          </thead>
          <tbody>
            <tr v-for="l in detail.lines" :key="l.itemId">
              <td class="dim">{{ l.lineNo }}</td>
              <td><span class="num">{{ l.skuCode }}</span> {{ l.skuName }}<span v-if="l.spec" class="dim"> {{ l.spec }}</span></td>
              <td class="r num">{{ qty(l.qty) }}</td>
              <td class="r num plus">{{ qty(l.shippedQty) }}</td>
              <td class="r num dim">{{ qty(l.remainQty) }}</td>
              <td class="r num dim">{{ l.unitPrice == null ? '--' : money(l.unitPrice) }}</td>
              <td class="r num">{{ l.amount == null ? '--' : money(l.amount) }}</td>
            </tr>
          </tbody>
        </table>

        <div class="sec">
          <div class="sec-head">发货记录</div>
          <table v-if="detail.bills.length" class="lines">
            <thead><tr><th>单号</th><th>类型</th><th class="r">数量</th><th class="r">金额</th><th>时间</th><th>操作人</th></tr></thead>
            <tbody>
              <tr v-for="b in detail.bills" :key="b.billNo">
                <td class="num" :class="{ strike: b.voided }">{{ b.billNo }}</td>
                <td>
                  <el-tag size="small" effect="plain" :type="b.type === 'SO_RETURN' ? 'danger' : 'warning'">{{ b.typeLabel }}</el-tag>
                  <el-tag v-if="b.voided" size="small" type="info" effect="plain" class="ml4">已作废</el-tag>
                </td>
                <td class="r num" :class="b.qty >= 0 ? 'plus' : 'minus'">{{ b.qty > 0 ? '+' : '' }}{{ qty(b.qty) }}</td>
                <td class="r num dim">{{ b.amount ? money(b.amount) : '--' }}</td>
                <td class="num dim">{{ dt(b.createdAt) }}</td>
                <td class="dim">{{ b.operator || '--' }}</td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty">还没有发货记录</div>
        </div>

        <div v-if="auth.canWrite" class="d-foot">
          <el-button v-if="detail.order.shippable" type="primary" @click="openShip(detail.order, 'ship')">发货</el-button>
          <el-button v-if="detail.order.shippedQty > 0" @click="openShip(detail.order, 'return')">客户退货</el-button>
          <el-button v-if="detail.order.editable" @click="openEdit(detail.order)">编辑</el-button>
          <el-button v-if="detail.order.editable" type="success" plain @click="doConfirm(detail.order)">确认订单</el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { partnerApi, type ErpPartner } from '@/api/erp'
import { SALES_STATUS_TAG, salesApi, type SalesBillRow, type SalesOrderHead, type SalesOrderLine, type SalesOrderRow, type SalesStats } from '@/api/sales'
import { useAuthStore } from '@/stores/auth'
import { dayText, dt, money, qty } from '@/utils/format'
import SalesOrderDialog from '@/components/erp/SalesOrderDialog.vue'
import SalesShipDialog from '@/components/erp/SalesShipDialog.vue'

const auth = useAuthStore()
const rows = ref<SalesOrderRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const status = ref('')
const customerId = ref<number | null>(null)
const range = ref<[string, string] | null>(null)
const kw = ref('')
const loading = ref(false)
const stats = ref<SalesStats | null>(null)
const statusOptions = ref<{ value: string; label: string }[]>([])
const customers = ref<ErpPartner[]>([])

const dlgOpen = ref(false)
const editingNo = ref<string | null>(null)
const shipOpen = ref(false)
const shipNo = ref('')
const shipMode = ref<'ship' | 'return'>('ship')

const detailOpen = ref(false)
const detail = ref<{ order: SalesOrderHead; lines: SalesOrderLine[]; bills: SalesBillRow[] } | null>(null)

const tagOf = (s: string) => (SALES_STATUS_TAG[s] || 'info') as any
/** 只有"已确认/部分发货"能发货 */
const canShip = (r: { status: string }) => r.status === 'CONFIRMED' || r.status === 'PARTIAL'

function toTs(day: string, end = false): number {
  const [y, m, d] = day.split('-').map(Number)
  return new Date(y, m - 1, d, end ? 23 : 0, end ? 59 : 0, end ? 59 : 0).getTime()
}

async function loadStats() {
  try {
    stats.value = await salesApi.stats()
    statusOptions.value = stats.value.statusOptions || []
  } catch (e: any) {
    ElMessage.error(e?.message || '销售统计加载失败')
  }
}

async function load() {
  loading.value = true
  try {
    const r = await salesApi.list({
      status: status.value,
      customerId: customerId.value,
      from: range.value ? toTs(range.value[0]) : undefined,
      to: range.value ? toTs(range.value[1], true) : undefined,
      kw: kw.value,
      page: page.value,
      size: size.value
    })
    rows.value = r.rows || []
    total.value = r.total || 0
  } catch (e: any) {
    ElMessage.error(e?.message || '销售订单加载失败')
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
  reload()
}

async function refresh() {
  await Promise.all([load(), loadStats()])
  if (detailOpen.value && detail.value) {
    try {
      detail.value = await salesApi.detail(detail.value.order.orderNo)
    } catch { /* 详情刷新失败不影响列表 */ }
  }
}

function openNew() {
  editingNo.value = null
  dlgOpen.value = true
}

function openEdit(o: { orderNo: string }) {
  editingNo.value = o.orderNo
  dlgOpen.value = true
}

async function openDetail(row: { orderNo: string }) {
  try {
    detail.value = await salesApi.detail(row.orderNo)
    detailOpen.value = true
  } catch (e: any) {
    ElMessage.error(e?.message || '订单详情加载失败')
  }
}

function openShip(row: { orderNo: string }, mode: 'ship' | 'return') {
  shipNo.value = row.orderNo
  shipMode.value = mode
  shipOpen.value = true
}

async function doConfirm(row: { orderNo: string }) {
  try {
    const r = await salesApi.confirm(row.orderNo)
    if (!r.ok) {
      ElMessage.error(r.message || '确认失败')
      return
    }
    ElMessage.success(r.message || '已确认')
    await refresh()
  } catch (e: any) {
    ElMessage.error(e?.message || '确认失败')
  }
}

async function doCancel(row: { orderNo: string }) {
  try {
    await ElMessageBox.confirm('取消后订单不可再发货，确定取消？', '取消订单', {
      type: 'warning', confirmButtonText: '取消订单', cancelButtonText: '返回'
    })
  } catch {
    return
  }
  try {
    const r = await salesApi.cancel(row.orderNo)
    if (!r.ok) {
      ElMessage.error(r.message || '取消失败')
      return
    }
    ElMessage.success(r.message || '已取消')
    await refresh()
  } catch (e: any) {
    ElMessage.error(e?.message || '取消失败')
  }
}

function onCommand(cmd: string, row: SalesOrderRow) {
  if (cmd === 'edit') openEdit(row)
  else if (cmd === 'confirm') doConfirm(row)
  else if (cmd === 'return') openShip(row, 'return')
  else if (cmd === 'cancel') doCancel(row)
}

onMounted(async () => {
  await Promise.all([loadStats(), load()])
  try {
    customers.value = await partnerApi.list(undefined, 'CUSTOMER')
  } catch {
    customers.value = []
  }
})
</script>

<style scoped>
.sales { display: flex; flex-direction: column; gap: var(--space-4); }
.kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--space-3); }
.kpi {
  background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--elev-raised); padding: var(--space-4); display: flex; flex-direction: column; gap: 2px;
}
.kpi > span { font-size: var(--text-xs); color: var(--muted); letter-spacing: .06em; }
.kpi b { font-size: var(--text-xl); font-weight: 600; }
.kpi b i { font-style: normal; font-size: var(--text-xs); color: var(--muted); margin-left: 4px; }
.kpi em { font-style: normal; font-size: var(--text-2xs); color: var(--meta); }
.kpi.hot { border-color: rgba(224, 68, 59, .35); }
.kpi.hot b { color: var(--alarm); }
.tab-toolbar { display: flex; gap: 10px; margin-bottom: var(--space-3); flex-wrap: wrap; align-items: center; }
.tab-toolbar .spacer { flex: 1; }
.order-no { color: var(--accent); cursor: pointer; }
.order-no:hover { text-decoration: underline; }
.prog { display: flex; align-items: center; gap: 8px; }
.prog :deep(.el-progress) { flex: 1; min-width: 0; }
.prog-text { font-size: var(--text-2xs); color: var(--muted); white-space: nowrap; }
.dim { color: var(--muted); }
.plus { color: var(--run); }
.minus { color: var(--alarm); }
.overdue { color: var(--alarm); }
.strike { text-decoration: line-through; color: var(--meta); }
.pager { display: flex; justify-content: flex-end; padding-top: var(--space-3); }
.detail { display: flex; flex-direction: column; gap: var(--space-4); }
.d-head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--space-3); }
.d-no { font-size: var(--text-lg); font-weight: 600; }
.d-sub { display: flex; gap: var(--space-2); align-items: center; margin-top: 6px; font-size: var(--text-xs); }
.d-amount { text-align: right; }
.d-amount span { display: block; font-size: var(--text-2xs); color: var(--muted); }
.d-amount b { font-size: var(--text-xl); }
.d-prog { display: flex; align-items: center; gap: var(--space-3); font-size: var(--text-xs); }
.d-prog :deep(.el-progress) { flex: 1; }
.d-meta { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-2) var(--space-4); }
.d-meta > div { display: flex; gap: 10px; font-size: var(--text-sm); }
.d-meta span { color: var(--muted); min-width: 60px; }
.d-meta b { font-weight: 500; }
.d-meta .span2 { grid-column: span 2; }
.ar-box {
  display: flex; align-items: baseline; gap: var(--space-6); flex-wrap: wrap;
  background: var(--surface-2); border: 1px solid var(--border-soft);
  border-radius: var(--radius-md); padding: var(--space-3) var(--space-4);
}
.ar-item { display: flex; flex-direction: column; gap: 2px; }
.ar-item span { font-size: var(--text-2xs); color: var(--muted); }
.ar-item b { font-size: var(--text-lg); }
.ar-tip { font-size: var(--text-2xs); color: var(--meta); margin-left: auto; }
.lines { width: 100%; border-collapse: collapse; font-size: var(--text-sm); }
.lines th { text-align: left; font-weight: 400; font-size: var(--text-2xs); color: var(--meta); padding: 6px 8px; border-bottom: 1px solid var(--border); }
.lines td { padding: 8px; border-bottom: 1px solid var(--border-soft); }
.lines .r { text-align: right; }
.sec-head { font-size: var(--text-sm); color: var(--fg-2); margin-bottom: var(--space-2); }
.empty { color: var(--muted); font-size: var(--text-sm); padding: var(--space-3); }
.ml4 { margin-left: 4px; }
.d-foot { display: flex; gap: var(--space-2); }
:deep(.op-col .el-button + .el-button) { margin-left: 2px; }
@media (max-width: 1100px) { .kpis { grid-template-columns: repeat(2, 1fr); } }
</style>
