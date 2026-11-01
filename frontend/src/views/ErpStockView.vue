<template>
  <div class="stock">
    <div class="kpis">
      <div class="kpi">
        <span>{{ t('erpStock.skuCount') }}</span>
        <b class="num">{{ summary?.skuCount || 0 }}<i>{{ t('erpStock.unitKind') }}</i></b>
        <em>{{ t('erpStock.rowsInfo', { n: summary?.stockRowCount || 0, w: summary?.whCount || 0 }) }}</em>
      </div>
      <div class="kpi">
        <span>{{ t('erpStock.cost') }}</span>
        <b class="num">¥{{ money(summary?.totalValue) }}</b>
        <em>{{ t('erpStock.costHint') }}</em>
      </div>
      <div class="kpi">
        <span>{{ t('erpStock.todayIn') }}</span>
        <b class="num up">+{{ qty(summary?.todayIn) }}</b>
        <em>{{ t('erpStock.monthIn', { v: qty(summary?.monthIn) }) }}</em>
      </div>
      <div class="kpi">
        <span>{{ t('erpStock.todayOut') }}</span>
        <b class="num down">-{{ qty(summary?.todayOut) }}</b>
        <em>{{ t('erpStock.monthOut', { v: qty(summary?.monthOut) }) }}</em>
      </div>
      <div class="kpi alert" :class="{ hot: (summary?.alertCount || 0) > 0 }" @click="tab = 'alert'">
        <span>{{ t('erpStock.alert') }}</span>
        <b class="num">{{ summary?.alertCount || 0 }}<i>{{ t('erpStock.unitItem') }}</i></b>
        <em>{{ (summary?.alertCount || 0) > 0 ? t('erpStock.alertHas') : t('erpStock.alertNone') }}</em>
      </div>
    </div>

    <el-card shadow="never">
      <el-tabs v-model="tab">
        <el-tab-pane :label="t('erpStock.tabBalance')" name="balance">
          <StockBalanceTab ref="balanceRef" @new-bill="openBill()" @stocktake="takeOpen = true" />
        </el-tab-pane>
        <el-tab-pane name="bills" lazy>
          <template #label>
            {{ t('erpStock.tabBills') }}
            <span v-if="summary?.todayBills" class="tab-badge">{{ summary.todayBills }}</span>
          </template>
          <StockBillTab ref="billRef" @new-bill="openBill()" @changed="refresh" />
        </el-tab-pane>
        <el-tab-pane name="alert" lazy>
          <template #label>
            {{ t('erpStock.tabAlert') }}
            <span v-if="summary?.alertCount" class="tab-badge warn">{{ summary.alertCount }}</span>
          </template>
          <StockAlertTab ref="alertRef" @new-bill="openBill" />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <StockBillDialog v-model="billOpen" :default-type="billType" @saved="refresh" />
    <StocktakeDialog v-model="takeOpen" @saved="refresh" />
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import StockBalanceTab from '@/components/erp/StockBalanceTab.vue'
import StockBillTab from '@/components/erp/StockBillTab.vue'
import StockAlertTab from '@/components/erp/StockAlertTab.vue'
import StockBillDialog from '@/components/erp/StockBillDialog.vue'
import StocktakeDialog from '@/components/erp/StocktakeDialog.vue'
import { stockApi, type StockSummary } from '@/api/erp'
import { money, qty } from '@/utils/format'

const { t } = useI18n()

/* 库存管理（ROADMAP DH-43b）：三个页签共用一个 KPI 头，任何建单/作废/盘点都会回来刷新 */
const tab = ref('balance')
const summary = ref<StockSummary | null>(null)
const billOpen = ref(false)
const billType = ref('PO_IN')
const takeOpen = ref(false)

const balanceRef = ref<InstanceType<typeof StockBalanceTab>>()
const billRef = ref<InstanceType<typeof StockBillTab>>()
const alertRef = ref<InstanceType<typeof StockAlertTab>>()

async function loadSummary() {
  try {
    summary.value = await stockApi.summary()
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpStock.loadSummaryFailed'))
  }
}

async function refresh() {
  await loadSummary()
  await balanceRef.value?.load()
  await billRef.value?.load()
  await alertRef.value?.load()
}

function openBill(type = 'PO_IN') {
  billType.value = type
  billOpen.value = true
}

onMounted(async () => {
  await nextTick()
  await loadSummary()
})
</script>

<style scoped>
.stock { display: flex; flex-direction: column; gap: var(--space-4); }
.kpis { display: grid; grid-template-columns: repeat(5, 1fr); gap: var(--space-3); }
.kpi {
  background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--elev-raised); padding: var(--space-4); display: flex; flex-direction: column; gap: 2px;
  cursor: default;
}
.kpi.alert { cursor: pointer; transition: border-color var(--motion-fast) var(--ease-standard); }
.kpi.alert:hover { border-color: var(--border-strong); }
.kpi > span { font-size: var(--text-xs); color: var(--muted); letter-spacing: .06em; }
.kpi b { font-size: var(--text-xl); font-weight: 600; }
.kpi b i { font-style: normal; font-size: var(--text-xs); color: var(--muted); margin-left: 4px; }
.kpi em { font-style: normal; font-size: var(--text-2xs); color: var(--meta); }
.kpi b.up { color: var(--run); }
.kpi b.down { color: var(--alarm); }
.kpi.alert.hot { border-color: rgba(224, 68, 59, .35); }
.kpi.alert.hot b { color: var(--alarm); }
.tab-badge {
  display: inline-block; min-width: 18px; padding: 0 5px; margin-left: 6px;
  font-size: var(--text-2xs); line-height: 16px; text-align: center;
  border-radius: 9px; background: var(--surface-3); color: var(--fg-2);
}
.tab-badge.warn { background: var(--alarm-soft); color: var(--alarm); }
@media (max-width: 1280px) {
  .kpis { grid-template-columns: repeat(3, 1fr); }
}
@media (max-width: 820px) {
  .kpis { grid-template-columns: repeat(2, 1fr); }
}
</style>
