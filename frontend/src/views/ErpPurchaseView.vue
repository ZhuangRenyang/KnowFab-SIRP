<template>
  <div class="purchase">
    <div class="kpis">
      <div class="kpi">
        <span>{{ t('erpPur.monthAmount') }}</span>
        <b class="num">¥{{ money(stats?.monthAmount) }}</b>
        <em>{{ t('erpPur.monthNote') }}</em>
      </div>
      <div class="kpi">
        <span>{{ t('erpPur.openOrders') }}</span>
        <b class="num">{{ stats?.openCount || 0 }}<i>{{ t('erpPur.unitOrder') }}</i></b>
        <em>{{ t('erpPur.openNote') }}</em>
      </div>
      <div class="kpi">
        <span>{{ t('erpPur.partial') }}</span>
        <b class="num">{{ stats?.partialCount || 0 }}<i>{{ t('erpPur.unitOrder') }}</i></b>
        <em>{{ t('erpPur.partialNote') }}</em>
      </div>
      <div class="kpi" :class="{ hot: (stats?.overdueCount || 0) > 0 }">
        <span>{{ t('erpPur.overdue') }}</span>
        <b class="num">{{ stats?.overdueCount || 0 }}<i>{{ t('erpPur.unitOrder') }}</i></b>
        <em>{{ (stats?.overdueCount || 0) > 0 ? t('erpPur.overdueNote') : t('erpPur.noOverdue') }}</em>
      </div>
    </div>

    <el-card shadow="never">
      <div class="tab-toolbar">
        <el-select v-model="status" :placeholder="t('erpPur.allStatus')" clearable style="width:130px" @change="reload">
          <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-select v-model="supplierId" :placeholder="t('erpPur.allSuppliers')" clearable filterable style="width:170px" @change="reload">
          <el-option v-for="p in suppliers" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <el-date-picker
          v-model="range" type="daterange" unlink-panels range-separator="→"
          :start-placeholder="t('erpPur.dateFrom')" :end-placeholder="t('erpPur.dateTo')"
          value-format="YYYY-MM-DD" style="width:240px" @change="reload"
        />
        <el-input
          v-model="kw" :placeholder="t('erpPur.searchPh')" clearable :prefix-icon="Search"
          style="width:200px" @keyup.enter="reload" @clear="reload"
        />
        <el-button :icon="Refresh" :loading="loading" @click="reload">{{ t('erpPur.refresh') }}</el-button>
        <span class="spacer" />
        <el-button v-if="auth.canWrite" type="primary" :icon="Plus" @click="openNew">{{ t('erpPur.newOrder') }}</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" stripe @row-dblclick="openDetail">
        <el-table-column :label="t('erpPur.colOrder')" min-width="165">
          <template #default="{ row }">
            <a class="order-no num" @click="openDetail(row)">{{ row.orderNo }}</a>
          </template>
        </el-table-column>
        <el-table-column :label="t('erpPur.colSupplier')" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.supplierName || '--' }}</template>
        </el-table-column>
        <el-table-column :label="t('erpPur.colStatus')" width="96">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="tagOf(row.status)">{{ purStatusText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('erpPur.colQty')" width="100" align="right">
          <template #default="{ row }"><span class="num">{{ qty(row.totalQty) }}</span></template>
        </el-table-column>
        <el-table-column :label="t('erpPur.colAmount')" width="112" align="right">
          <template #default="{ row }"><span class="num">{{ row.totalAmount ? money(row.totalAmount) : '--' }}</span></template>
        </el-table-column>
        <el-table-column :label="t('erpPur.colProgress')" width="168">
          <template #default="{ row }">
            <div class="prog">
              <el-progress :percentage="Math.min(100, Math.max(0, row.progress))" :stroke-width="6" :show-text="false" />
              <span class="num prog-text">{{ qty(row.receivedQty) }}/{{ qty(row.totalQty) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('erpPur.colOrderDate')" width="100">
          <template #default="{ row }"><span class="num dim">{{ dayText(row.orderDate) }}</span></template>
        </el-table-column>
        <el-table-column :label="t('erpPur.colExpect')" width="104">
          <template #default="{ row }">
            <span class="num" :class="row.overdue ? 'overdue' : 'dim'">{{ row.expectDate ? dayText(row.expectDate) : '--' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('erpPur.colAction')" width="196" align="right" class-name="op-col">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openDetail(row)">{{ t('erpPur.detail') }}</el-button>
            <el-button v-if="auth.canWrite && canReceive(row)" size="small" text
              @click="openReceive(row, 'receive')">{{ t('erpPur.receive') }}</el-button>
            <el-dropdown v-if="auth.canWrite && row.status !== 'CANCELLED'" trigger="click" @command="(c: string) => onCommand(c, row)">
              <el-button size="small" text>{{ t('erpPur.more') }}<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-if="row.status === 'DRAFT'" command="edit">{{ t('erpPur.edit') }}</el-dropdown-item>
                  <el-dropdown-item v-if="row.status === 'DRAFT'" command="confirm">{{ t('erpPur.confirm') }}</el-dropdown-item>
                  <el-dropdown-item v-if="row.receivedQty > 0" command="return">{{ t('erpPur.returnGoods') }}</el-dropdown-item>
                  <el-dropdown-item
                    v-if="row.status === 'DRAFT' || (row.status === 'CONFIRMED' && row.receivedQty <= 0)"
                    command="cancel" divided
                  >{{ t('erpPur.cancelOrder') }}</el-dropdown-item>
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

    <PurchaseOrderDialog v-model="dlgOpen" :order-no="editingNo" @saved="refresh" />
    <PurchaseReceiveDialog v-model="rcvOpen" :order-no="rcvNo" :mode="rcvMode" @saved="refresh" />

    <el-drawer v-model="detailOpen" :title="t('erpPur.drawerTitle')" size="660px">
      <div v-if="detail" class="detail">
        <div class="d-head">
          <div>
            <div class="d-no num">{{ detail.order.orderNo }}</div>
            <div class="d-sub">
              <el-tag size="small" effect="plain" :type="tagOf(detail.order.status)">{{ detail.order.statusLabel }}</el-tag>
              <span class="dim">{{ detail.order.supplierName }}</span>
            </div>
          </div>
          <div class="d-amount">
            <span>{{ t('erpPur.orderAmount') }}</span>
            <b class="num">¥{{ money(detail.order.totalAmount) }}</b>
          </div>
        </div>

        <div class="d-prog">
          <el-progress :percentage="Math.min(100, Math.max(0, detail.order.progress))" :stroke-width="8" />
          <span class="dim">{{ t('erpPur.receivedOf', { a: qty(detail.order.receivedQty), b: qty(detail.order.totalQty) }) }}</span>
        </div>

        <div class="d-meta">
          <div><span>{{ t('erpPur.fOrderDate') }}</span><b class="num">{{ dayText(detail.order.orderDate) }}</b></div>
          <div><span>{{ t('erpPur.fExpect') }}</span><b class="num">{{ detail.order.expectDate ? dayText(detail.order.expectDate) : '--' }}</b></div>
          <div><span>{{ t('erpPur.fOperator') }}</span><b>{{ detail.order.operator || '--' }}</b></div>
          <div><span>{{ t('erpPur.fUpdated') }}</span><b class="num">{{ dt(detail.order.updatedAt) }}</b></div>
          <div class="span2"><span>{{ t('erpPur.fRemark') }}</span><b>{{ detail.order.remark || '--' }}</b></div>
        </div>

        <table class="lines">
          <thead>
            <tr><th>#</th><th>{{ t('erpPur.thSku') }}</th><th class="r">{{ t('erpPur.thOrdered') }}</th><th class="r">{{ t('erpPur.thReceived') }}</th><th class="r">{{ t('erpPur.thRemaining') }}</th><th class="r">{{ t('erpPur.thPrice') }}</th><th class="r">{{ t('erpPur.thAmount') }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="l in detail.lines" :key="l.itemId">
              <td class="dim">{{ l.lineNo }}</td>
              <td><span class="num">{{ l.skuCode }}</span> {{ l.skuName }}<span v-if="l.spec" class="dim"> {{ l.spec }}</span></td>
              <td class="r num">{{ qty(l.qty) }}</td>
              <td class="r num plus">{{ qty(l.receivedQty) }}</td>
              <td class="r num dim">{{ qty(l.remainQty) }}</td>
              <td class="r num dim">{{ l.unitPrice == null ? '--' : money(l.unitPrice) }}</td>
              <td class="r num">{{ l.amount == null ? '--' : money(l.amount) }}</td>
            </tr>
          </tbody>
        </table>

        <div class="sec">
          <div class="sec-head">{{ t('erpPur.receiptRecords') }}</div>
          <table v-if="detail.bills.length" class="lines">
            <thead><tr><th>{{ t('erpPur.thBill') }}</th><th>{{ t('erpPur.thType') }}</th><th class="r">{{ t('erpPur.thQty') }}</th><th class="r">{{ t('erpPur.thAmount') }}</th><th>{{ t('erpPur.thTime') }}</th><th>{{ t('erpPur.thOperator') }}</th></tr></thead>
            <tbody>
              <tr v-for="b in detail.bills" :key="b.billNo">
                <td class="num" :class="{ strike: b.voided }">{{ b.billNo }}</td>
                <td>
                  <el-tag size="small" effect="plain" :type="b.type === 'PO_RETURN' ? 'danger' : 'success'">{{ b.typeLabel }}</el-tag>
                  <el-tag v-if="b.voided" size="small" type="info" effect="plain" class="ml4">{{ t('erpPur.voided') }}</el-tag>
                </td>
                <td class="r num" :class="b.qty >= 0 ? 'plus' : 'minus'">{{ b.qty > 0 ? '+' : '' }}{{ qty(b.qty) }}</td>
                <td class="r num dim">{{ b.amount ? money(b.amount) : '--' }}</td>
                <td class="num dim">{{ dt(b.createdAt) }}</td>
                <td class="dim">{{ b.operator || '--' }}</td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty">{{ t('erpPur.noReceipts') }}</div>
        </div>

        <div v-if="auth.canWrite" class="d-foot">
          <el-button v-if="detail.order.receivable" type="primary" @click="openReceive(detail.order, 'receive')">{{ t('erpPur.receive') }}</el-button>
          <el-button v-if="detail.order.receivedQty > 0" @click="openReceive(detail.order, 'return')">{{ t('erpPur.returnGoods') }}</el-button>
          <el-button v-if="detail.order.editable" @click="openEdit(detail.order)">{{ t('erpPur.edit') }}</el-button>
          <el-button v-if="detail.order.editable" type="success" plain @click="doConfirm(detail.order)">{{ t('erpPur.confirm') }}</el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { partnerApi, type ErpPartner } from '@/api/erp'
import { PURCHASE_STATUS_TAG, purchaseApi, type PurchaseBillRow, type PurchaseOrderHead, type PurchaseOrderLine, type PurchaseOrderRow, type PurchaseStats } from '@/api/purchase'
import { useAuthStore } from '@/stores/auth'
import { dayText, dt, money, qty } from '@/utils/format'
import PurchaseOrderDialog from '@/components/erp/PurchaseOrderDialog.vue'
import PurchaseReceiveDialog from '@/components/erp/PurchaseReceiveDialog.vue'

const { t, te } = useI18n()

/* 状态码由后端给出，文案在前端按码翻译；查不到就用后端给的中文 */
function purStatusText(row: any): string {
  const k = 'erpPurStatus.' + row?.status
  return row?.status && te(k) ? t(k) : (row?.statusLabel || '')
}

const auth = useAuthStore()
const rows = ref<PurchaseOrderRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const status = ref('')
const supplierId = ref<number | null>(null)
const range = ref<[string, string] | null>(null)
const kw = ref('')
const loading = ref(false)
const stats = ref<PurchaseStats | null>(null)
const statusOptions = ref<{ value: string; label: string }[]>([])
const suppliers = ref<ErpPartner[]>([])

const dlgOpen = ref(false)
const editingNo = ref<string | null>(null)
const rcvOpen = ref(false)
const rcvNo = ref('')
const rcvMode = ref<'receive' | 'return'>('receive')

const detailOpen = ref(false)
const detail = ref<{ order: PurchaseOrderHead; lines: PurchaseOrderLine[]; bills: PurchaseBillRow[] } | null>(null)

const tagOf = (s: string) => (PURCHASE_STATUS_TAG[s] || 'info') as any
/** 只有"已确认/部分入库"能收货；草稿和已完成都不该出现入库按钮（后端也会拦，但按钮不该骗人） */
const canReceive = (r: { status: string }) => r.status === 'CONFIRMED' || r.status === 'PARTIAL'

function toTs(day: string, end = false): number {
  const [y, m, d] = day.split('-').map(Number)
  return new Date(y, m - 1, d, end ? 23 : 0, end ? 59 : 0, end ? 59 : 0).getTime()
}

async function loadStats() {
  try {
    stats.value = await purchaseApi.stats()
    statusOptions.value = stats.value.statusOptions || []
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpPur.statsFailed'))
  }
}

async function load() {
  loading.value = true
  try {
    const r = await purchaseApi.list({
      status: status.value,
      supplierId: supplierId.value,
      from: range.value ? toTs(range.value[0]) : undefined,
      to: range.value ? toTs(range.value[1], true) : undefined,
      kw: kw.value,
      page: page.value,
      size: size.value
    })
    rows.value = r.rows || []
    total.value = r.total || 0
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpPur.ordersFailed'))
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
      detail.value = await purchaseApi.detail(detail.value.order.orderNo)
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
    detail.value = await purchaseApi.detail(row.orderNo)
    detailOpen.value = true
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpPur.detailFailed'))
  }
}

function openReceive(row: { orderNo: string }, mode: 'receive' | 'return') {
  rcvNo.value = row.orderNo
  rcvMode.value = mode
  rcvOpen.value = true
}

async function doConfirm(row: { orderNo: string }) {
  try {
    const r = await purchaseApi.confirm(row.orderNo)
    if (!r.ok) {
      ElMessage.error(r.message || t('erpPur.confirmFailed'))
      return
    }
    ElMessage.success(r.message || t('erpPur.confirmed'))
    await refresh()
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpPur.confirmFailed'))
  }
}

async function doCancel(row: { orderNo: string }) {
  try {
    await ElMessageBox.confirm(t('erpPur.cancelConfirm'), t('erpPur.cancelTitle'), {
      type: 'warning', confirmButtonText: t('erpPur.cancelOk'), cancelButtonText: t('erpPur.back')
    })
  } catch {
    return
  }
  try {
    const r = await purchaseApi.cancel(row.orderNo)
    if (!r.ok) {
      ElMessage.error(r.message || t('erpPur.cancelFailed'))
      return
    }
    ElMessage.success(r.message || t('erpPur.cancelled'))
    await refresh()
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpPur.cancelFailed'))
  }
}

function onCommand(cmd: string, row: PurchaseOrderRow) {
  if (cmd === 'edit') openEdit(row)
  else if (cmd === 'confirm') doConfirm(row)
  else if (cmd === 'return') openReceive(row, 'return')
  else if (cmd === 'cancel') doCancel(row)
}

onMounted(async () => {
  await Promise.all([loadStats(), load()])
  try {
    suppliers.value = await partnerApi.list(undefined, 'SUPPLIER')
  } catch {
    suppliers.value = []
  }
})
</script>

<style scoped>
.purchase { display: flex; flex-direction: column; gap: var(--space-4); }
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
