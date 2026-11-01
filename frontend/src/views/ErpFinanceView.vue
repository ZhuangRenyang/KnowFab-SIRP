<template>
  <div class="finance">
    <div class="kpis">
      <div class="kpi">
        <span>{{ t('erpFin.ar') }}</span>
        <b class="num">¥{{ money(summary?.arOutstanding) }}</b>
        <em>{{ t('erpFin.arEm', { v: money(summary?.arOverdue), v2: money(summary?.arPre) }) }}</em>
      </div>
      <div class="kpi">
        <span>{{ t('erpFin.ap') }}</span>
        <b class="num">¥{{ money(summary?.apOutstanding) }}</b>
        <em>{{ t('erpFin.apEm', { v: money(summary?.apOverdue), v2: money(summary?.apPre) }) }}</em>
      </div>
      <div class="kpi">
        <span>{{ t('erpFin.inMonth') }}</span>
        <b class="num up">+¥{{ money(summary?.monthIn) }}</b>
        <em>{{ t('erpFin.inMonthEm', { v: money(summary?.arPaid) }) }}</em>
      </div>
      <div class="kpi">
        <span>{{ t('erpFin.outMonth') }}</span>
        <b class="num down">-¥{{ money(summary?.monthOut) }}</b>
        <em>{{ t('erpFin.outMonthEm', { v: money(summary?.apPaid) }) }}</em>
      </div>
    </div>

    <el-card shadow="never">
      <el-tabs v-model="tab" @tab-change="onTab">
        <el-tab-pane :label="t('erpFin.tabAr')" name="ar">
          <div class="aging">
            <div v-for="(a, i) in summary?.arAging || []" :key="a.bucket" class="aging-cell" :class="'b' + i">
              <span>{{ t('erpFin.bucketDays', { bucket: a.bucket }) }}</span>
              <b class="num">¥{{ money(a.amount) }}</b>
            </div>
            <div class="aging-tip">{{ t('erpFin.agingTip', { n: summary?.creditDays || 30 }) }}</div>
          </div>

          <div class="tab-toolbar">
            <el-select v-model="arPartner" :placeholder="t('erpFin.allCustomers')" clearable filterable style="width:180px" @change="loadAr">
              <el-option v-for="p in customers" :key="p.id" :label="p.name" :value="p.id" />
            </el-select>
            <el-switch v-model="arOnlyOpen" :active-text="t('erpFin.onlyUnpaidIn')" @change="loadAr" />
            <el-input v-model="arKw" :placeholder="t('erpFin.searchAr')" clearable :prefix-icon="Search" style="width:200px"
              @keyup.enter="loadAr" @clear="loadAr" />
            <el-button :icon="Refresh" :loading="arLoading" @click="loadAr">{{ t('erpFin.refresh') }}</el-button>
            <span class="spacer" />
            <el-button v-if="auth.canWrite" type="primary" :icon="Money" @click="openPay('IN')">{{ t('erpFin.recordIn') }}</el-button>
          </div>

          <el-table :data="arRows" v-loading="arLoading" stripe show-summary :summary-method="sumAr">
            <el-table-column prop="orderNo" :label="t('erpFin.colOrder')" min-width="165">
              <template #default="{ row }"><span class="num">{{ row.orderNo }}</span></template>
            </el-table-column>
            <el-table-column prop="partnerName" :label="t('erpFin.colCustomer')" min-width="150" show-overflow-tooltip />
            <el-table-column :label="t('erpFin.colDate')" width="104">
              <template #default="{ row }"><span class="num dim">{{ dayText(row.orderDate) }}</span></template>
            </el-table-column>
            <el-table-column prop="totalAmount" :label="t('erpFin.colOrderAmount')" width="112" align="right">
              <template #default="{ row }"><span class="num dim">{{ money(row.totalAmount) }}</span></template>
            </el-table-column>
            <el-table-column prop="billedAmount" :label="t('erpFin.colArBilled')" width="130" align="right">
              <template #default="{ row }"><span class="num">{{ money(row.billedAmount) }}</span></template>
            </el-table-column>
            <el-table-column prop="paidAmount" :label="t('erpFin.colPaidIn')" width="112" align="right">
              <template #default="{ row }"><span class="num dim">{{ money(row.paidAmount) }}</span></template>
            </el-table-column>
            <el-table-column prop="outstanding" :label="t('erpFin.colOutstandingIn')" width="122" align="right">
              <template #default="{ row }">
                <b class="num" :class="{ al: row.overdue }">{{ money(row.outstanding) }}</b>
              </template>
            </el-table-column>
            <el-table-column :label="t('erpFin.colAging')" width="104">
              <template #default="{ row }">
                <span class="num" :class="row.overdue ? 'al' : 'dim'">{{ t('erpFin.days', { n: row.days }) }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('erpFin.colAction')" width="92" align="right">
              <template #default="{ row }">
                <el-button v-if="auth.canWrite && row.outstanding > 0" size="small" text type="primary"
                  @click="openPay('IN', row)">{{ t('erpFin.payIn') }}</el-button>
                <span v-else class="dim">--</span>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!arRows.length && !arLoading" :description="t('erpFin.arEmpty')" />
          <div class="pager">
            <el-pagination layout="total, prev, pager, next" :total="arTotal" :page-size="pageSize"
              :current-page="arPage" @current-change="(p: number) => { arPage = p; loadAr() }" />
          </div>
        </el-tab-pane>

        <el-tab-pane :label="t('erpFin.tabAp')" name="ap" lazy>
          <div class="aging">
            <div v-for="(a, i) in summary?.apAging || []" :key="a.bucket" class="aging-cell" :class="'b' + i">
              <span>{{ t('erpFin.bucketDays', { bucket: a.bucket }) }}</span>
              <b class="num">¥{{ money(a.amount) }}</b>
            </div>
            <div class="aging-tip">{{ t('erpFin.agingTip', { n: summary?.creditDays || 30 }) }}</div>
          </div>

          <div class="tab-toolbar">
            <el-select v-model="apPartner" :placeholder="t('erpFin.allSuppliers')" clearable filterable style="width:180px" @change="loadAp">
              <el-option v-for="p in suppliers" :key="p.id" :label="p.name" :value="p.id" />
            </el-select>
            <el-switch v-model="apOnlyOpen" :active-text="t('erpFin.onlyUnpaidOut')" @change="loadAp" />
            <el-input v-model="apKw" :placeholder="t('erpFin.searchAp')" clearable :prefix-icon="Search" style="width:200px"
              @keyup.enter="loadAp" @clear="loadAp" />
            <el-button :icon="Refresh" :loading="apLoading" @click="loadAp">{{ t('erpFin.refresh') }}</el-button>
            <span class="spacer" />
            <el-button v-if="auth.canWrite" type="primary" :icon="Money" @click="openPay('OUT')">{{ t('erpFin.recordOut') }}</el-button>
          </div>

          <el-table :data="apRows" v-loading="apLoading" stripe show-summary :summary-method="sumAp">
            <el-table-column prop="orderNo" :label="t('erpFin.colOrder')" min-width="165">
              <template #default="{ row }"><span class="num">{{ row.orderNo }}</span></template>
            </el-table-column>
            <el-table-column prop="partnerName" :label="t('erpFin.colSupplier')" min-width="150" show-overflow-tooltip />
            <el-table-column :label="t('erpFin.colDate')" width="104">
              <template #default="{ row }"><span class="num dim">{{ dayText(row.orderDate) }}</span></template>
            </el-table-column>
            <el-table-column prop="totalAmount" :label="t('erpFin.colOrderAmount')" width="112" align="right">
              <template #default="{ row }"><span class="num dim">{{ money(row.totalAmount) }}</span></template>
            </el-table-column>
            <el-table-column prop="billedAmount" :label="t('erpFin.colApBilled')" width="130" align="right">
              <template #default="{ row }"><span class="num">{{ money(row.billedAmount) }}</span></template>
            </el-table-column>
            <el-table-column prop="paidAmount" :label="t('erpFin.colPaidOut')" width="112" align="right">
              <template #default="{ row }"><span class="num dim">{{ money(row.paidAmount) }}</span></template>
            </el-table-column>
            <el-table-column prop="outstanding" :label="t('erpFin.colOutstandingOut')" width="122" align="right">
              <template #default="{ row }">
                <b class="num" :class="{ al: row.overdue }">{{ money(row.outstanding) }}</b>
              </template>
            </el-table-column>
            <el-table-column :label="t('erpFin.colAging')" width="104">
              <template #default="{ row }">
                <span class="num" :class="row.overdue ? 'al' : 'dim'">{{ t('erpFin.days', { n: row.days }) }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('erpFin.colAction')" width="92" align="right">
              <template #default="{ row }">
                <el-button v-if="auth.canWrite && row.outstanding > 0" size="small" text type="primary"
                  @click="openPay('OUT', row)">{{ t('erpFin.payOut') }}</el-button>
                <span v-else class="dim">--</span>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!apRows.length && !apLoading" :description="t('erpFin.apEmpty')" />
          <div class="pager">
            <el-pagination layout="total, prev, pager, next" :total="apTotal" :page-size="pageSize"
              :current-page="apPage" @current-change="(p: number) => { apPage = p; loadAp() }" />
          </div>
        </el-tab-pane>

        <el-tab-pane :label="t('erpFin.tabPay')" name="pay" lazy>
          <div class="tab-toolbar">
            <el-select v-model="payDir" :placeholder="t('erpFin.allDir')" clearable style="width:120px" @change="reloadPay">
              <el-option :label="t('erpFin.dirIn')" value="IN" />
              <el-option :label="t('erpFin.dirOut')" value="OUT" />
            </el-select>
            <el-select v-model="payPartner" :placeholder="t('erpFin.allPartners')" clearable filterable style="width:180px" @change="reloadPay">
              <el-option v-for="p in allPartners" :key="p.id" :label="p.name" :value="p.id" />
            </el-select>
            <el-date-picker v-model="payRange" type="daterange" unlink-panels range-separator="→"
              :start-placeholder="t('erpFin.startDate')" :end-placeholder="t('erpFin.endDate')" value-format="YYYY-MM-DD"
              style="width:240px" @change="reloadPay" />
            <el-input v-model="payKw" :placeholder="t('erpFin.searchPay')" clearable :prefix-icon="Search"
              style="width:210px" @keyup.enter="reloadPay" @clear="reloadPay" />
            <el-checkbox v-model="withVoided" @change="reloadPay">{{ t('erpFin.withVoided') }}</el-checkbox>
            <el-button :icon="Refresh" :loading="payLoading" @click="reloadPay">{{ t('erpFin.refresh') }}</el-button>
            <span class="spacer" />
            <el-button v-if="auth.canWrite" type="primary" :icon="Plus" @click="openPay('IN')">{{ t('erpFin.newPay') }}</el-button>
          </div>

          <el-table :data="payRows" v-loading="payLoading" stripe>
            <el-table-column :label="t('erpFin.colBill')" min-width="150">
              <template #default="{ row }"><span class="num" :class="{ strike: row.voided }">{{ row.payNo }}</span></template>
            </el-table-column>
            <el-table-column :label="t('erpFin.colDir')" width="80">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="row.direction === 'IN' ? 'success' : 'warning'">
                  {{ row.directionLabel }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('erpFin.colPartner')" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.partnerName || '--' }}</template>
            </el-table-column>
            <el-table-column :label="t('erpFin.colOrderLinked')" min-width="160">
              <template #default="{ row }">
                <span v-if="row.orderNo" class="num">{{ row.orderNo }}</span>
                <span v-else class="dim">{{ row.direction === 'IN' ? t('erpFin.preIn') : t('erpFin.preOut') }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('erpFin.colAmount')" width="132" align="right">
              <template #default="{ row }">
                <b class="num" :class="row.direction === 'IN' ? 'up' : 'down'">
                  {{ row.direction === 'IN' ? '+' : '-' }}{{ money(row.amount) }}
                </b>
              </template>
            </el-table-column>
            <el-table-column :label="t('erpFin.colMethod')" width="104">
              <template #default="{ row }"><span class="dim">{{ row.method || '--' }}</span></template>
            </el-table-column>
            <el-table-column :label="t('erpFin.colTime')" width="104">
              <template #default="{ row }"><span class="num dim">{{ dayText(row.payDate) }}</span></template>
            </el-table-column>
            <el-table-column :label="t('erpFin.colOperator')" width="92">
              <template #default="{ row }"><span class="dim">{{ row.operator || '--' }}</span></template>
            </el-table-column>
            <el-table-column :label="t('erpFin.colStatus')" width="80">
              <template #default="{ row }">
                <el-tag v-if="row.voided" size="small" type="danger" effect="plain">{{ t('erpFin.voided') }}</el-tag>
                <span v-else class="dim">{{ t('erpFin.valid') }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('erpFin.colAction')" width="80" align="right">
              <template #default="{ row }">
                <el-button v-if="auth.canWrite && !row.voided" size="small" text type="danger"
                  @click="doVoid(row)">{{ t('erpFin.voidBtn') }}</el-button>
                <span v-else class="dim">--</span>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!payRows.length && !payLoading" :description="t('erpFin.payEmpty')" />
          <div class="pager">
            <el-pagination layout="total, prev, pager, next" :total="payTotal" :page-size="pageSize"
              :current-page="payPage" @current-change="(p: number) => { payPage = p; loadPay() }" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <PaymentDialog v-model="payOpen" :default-direction="payDirection" :default-partner-id="payPartnerId"
      :default-order-no="payOrderNo" @saved="refresh" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Money, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { partnerApi, type ErpPartner } from '@/api/erp'
import { financeApi, type FinanceRow, type FinanceSummary, type PaymentRow } from '@/api/finance'
import { useAuthStore } from '@/stores/auth'
import { dayText, money } from '@/utils/format'
import PaymentDialog from '@/components/erp/PaymentDialog.vue'

const { t } = useI18n()

const auth = useAuthStore()
const tab = ref('ar')
const summary = ref<FinanceSummary | null>(null)
const pageSize = 20

const customers = ref<ErpPartner[]>([])
const suppliers = ref<ErpPartner[]>([])
const allPartners = ref<ErpPartner[]>([])

/* 应收 */
const arRows = ref<FinanceRow[]>([])
const arTotal = ref(0)
const arPage = ref(1)
const arPartner = ref<number | null>(null)
const arOnlyOpen = ref(true)
const arKw = ref('')
const arLoading = ref(false)

/* 应付 */
const apRows = ref<FinanceRow[]>([])
const apTotal = ref(0)
const apPage = ref(1)
const apPartner = ref<number | null>(null)
const apOnlyOpen = ref(true)
const apKw = ref('')
const apLoading = ref(false)

/* 收付款 */
const payRows = ref<PaymentRow[]>([])
const payTotal = ref(0)
const payPage = ref(1)
const payDir = ref('')
const payPartner = ref<number | null>(null)
const payRange = ref<[string, string] | null>(null)
const payKw = ref('')
const withVoided = ref(false)
const payLoading = ref(false)

/* 弹窗 */
const payOpen = ref(false)
const payDirection = ref<'IN' | 'OUT'>('IN')
const payPartnerId = ref<number | null>(null)
const payOrderNo = ref<string | null>(null)

function toTs(day: string, end = false): number {
  const [y, m, d] = day.split('-').map(Number)
  return new Date(y, m - 1, d, end ? 23 : 0, end ? 59 : 0, end ? 59 : 0).getTime()
}

async function loadSummary() {
  try {
    summary.value = await financeApi.summary()
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpFin.summaryFailed'))
  }
}

async function loadAr() {
  arLoading.value = true
  try {
    const r = await financeApi.receivables({
      partnerId: arPartner.value, onlyOpen: arOnlyOpen.value, kw: arKw.value,
      page: arPage.value, size: pageSize
    })
    arRows.value = r.rows || []
    arTotal.value = r.total || 0
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpFin.arFailed'))
  } finally {
    arLoading.value = false
  }
}

async function loadAp() {
  apLoading.value = true
  try {
    const r = await financeApi.payables({
      partnerId: apPartner.value, onlyOpen: apOnlyOpen.value, kw: apKw.value,
      page: apPage.value, size: pageSize
    })
    apRows.value = r.rows || []
    apTotal.value = r.total || 0
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpFin.apFailed'))
  } finally {
    apLoading.value = false
  }
}

async function loadPay() {
  payLoading.value = true
  try {
    const r = await financeApi.payments({
      direction: payDir.value,
      partnerId: payPartner.value,
      from: payRange.value ? toTs(payRange.value[0]) : undefined,
      to: payRange.value ? toTs(payRange.value[1], true) : undefined,
      kw: payKw.value,
      withVoided: withVoided.value,
      page: payPage.value,
      size: pageSize
    })
    payRows.value = r.rows || []
    payTotal.value = r.total || 0
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpFin.payFailed'))
  } finally {
    payLoading.value = false
  }
}

function reloadPay() {
  payPage.value = 1
  loadPay()
}

function onTab(name: string | number) {
  if (name === 'ap' && !apRows.value.length) loadAp()
  if (name === 'pay' && !payRows.value.length) loadPay()
}

/** 合计行：只合计和钱有关的列 */
function sumOf(rows: FinanceRow[], prop: string) {
  return rows.reduce((a, r) => a + Number((r as any)[prop] || 0), 0)
}
function summaryRow(columns: any[], rows: FinanceRow[], props: string[]) {
  return columns.map((c: any, i: number) => {
    if (i === 0) return t('erpFin.pageTotal')
    if (props.includes(c.property)) return money(sumOf(rows, c.property))
    return ''
  })
}
const sumAr = ({ columns }: any) => summaryRow(columns, arRows.value, ['billedAmount', 'paidAmount', 'outstanding'])
const sumAp = ({ columns }: any) => summaryRow(columns, apRows.value, ['billedAmount', 'paidAmount', 'outstanding'])

function openPay(dir: 'IN' | 'OUT', row?: FinanceRow) {
  payDirection.value = dir
  payPartnerId.value = row?.partnerId ?? null
  payOrderNo.value = row?.orderNo ?? null
  payOpen.value = true
}

async function doVoid(row: PaymentRow) {
  try {
    await ElMessageBox.confirm(t('erpFin.voidConfirm', { kind: row.direction === 'IN' ? t('erpFin.dirIn') : t('erpFin.dirOut') }), t('erpFin.voidTitle'), {
      type: 'warning', confirmButtonText: t('erpFin.voidOk'), cancelButtonText: t('erpFin.cancel')
    })
  } catch {
    return
  }
  try {
    const r = await financeApi.voidPayment(row.payNo)
    if (!r.ok) {
      ElMessage.error(r.message || t('erpFin.voidFailed'))
      return
    }
    ElMessage.success(r.message || t('erpFin.voidedOk'))
    await refresh()
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpFin.voidFailed'))
  }
}

async function refresh() {
  await Promise.all([loadSummary(), loadAr()])
  if (apRows.value.length) await loadAp()
  if (payRows.value.length) await loadPay()
}

onMounted(async () => {
  await Promise.all([loadSummary(), loadAr()])
  try {
    const [c, s] = await Promise.all([partnerApi.list(undefined, 'CUSTOMER'), partnerApi.list(undefined, 'SUPPLIER')])
    customers.value = c
    suppliers.value = s
    allPartners.value = [...c, ...s]
  } catch {
    /* 下拉加载失败不影响主流程 */
  }
})
</script>

<style scoped>
.finance { display: flex; flex-direction: column; gap: var(--space-4); }
.kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--space-3); }
.kpi {
  background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--elev-raised); padding: var(--space-4); display: flex; flex-direction: column; gap: 2px;
}
.kpi > span { font-size: var(--text-xs); color: var(--muted); letter-spacing: .06em; }
.kpi b { font-size: var(--text-xl); font-weight: 600; }
.kpi em { font-style: normal; font-size: var(--text-2xs); color: var(--meta); }
.kpi em i { font-style: normal; }
.kpi b.up { color: var(--run); }
.kpi b.down { color: var(--alarm); }
.al { color: var(--alarm); }
.up { color: var(--run); }
.down { color: var(--alarm); }
.dim { color: var(--muted); }
.strike { text-decoration: line-through; color: var(--meta); }
.aging { display: grid; grid-template-columns: repeat(4, 1fr) auto; gap: var(--space-2); margin-bottom: var(--space-3); }
.aging-cell {
  background: var(--surface-2); border: 1px solid var(--border-soft); border-radius: var(--radius-md);
  padding: var(--space-2) var(--space-3); display: flex; flex-direction: column; gap: 2px;
}
.aging-cell span { font-size: var(--text-2xs); color: var(--muted); }
.aging-cell b { font-size: var(--text-md); }
.aging-cell.b3 b { color: var(--alarm); }
.aging-tip { align-self: end; font-size: var(--text-2xs); color: var(--meta); white-space: nowrap; }
.tab-toolbar { display: flex; gap: 10px; margin-bottom: var(--space-3); flex-wrap: wrap; align-items: center; }
.tab-toolbar .spacer { flex: 1; }
.pager { display: flex; justify-content: flex-end; padding-top: var(--space-3); }
@media (max-width: 1200px) {
  .kpis { grid-template-columns: repeat(2, 1fr); }
  .aging { grid-template-columns: repeat(2, 1fr); }
  .aging-tip { grid-column: span 2; }
}
</style>
