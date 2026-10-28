<template>
  <div class="stock">
    <div class="kpis">
      <div class="kpi">
        <span>库存品种</span>
        <b class="num">{{ summary?.skuCount || 0 }}<i>种</i></b>
        <em>{{ summary?.stockRowCount || 0 }} 个库位 · {{ summary?.whCount || 0 }} 个仓库</em>
      </div>
      <div class="kpi">
        <span>库存成本</span>
        <b class="num">¥{{ money(summary?.totalValue) }}</b>
        <em>按商品采购价估值</em>
      </div>
      <div class="kpi">
        <span>今日入库</span>
        <b class="num up">+{{ qty(summary?.todayIn) }}</b>
        <em>本月累计 +{{ qty(summary?.monthIn) }}</em>
      </div>
      <div class="kpi">
        <span>今日出库</span>
        <b class="num down">-{{ qty(summary?.todayOut) }}</b>
        <em>本月累计 -{{ qty(summary?.monthOut) }}</em>
      </div>
      <div class="kpi alert" :class="{ hot: (summary?.alertCount || 0) > 0 }" @click="tab = 'alert'">
        <span>库存预警</span>
        <b class="num">{{ summary?.alertCount || 0 }}<i>项</i></b>
        <em>{{ (summary?.alertCount || 0) > 0 ? '点此查看低于下限的商品' : '库存都在上下限之间' }}</em>
      </div>
    </div>

    <el-card shadow="never">
      <el-tabs v-model="tab">
        <el-tab-pane label="库存余额" name="balance">
          <StockBalanceTab ref="balanceRef" @new-bill="openBill()" @stocktake="takeOpen = true" />
        </el-tab-pane>
        <el-tab-pane name="bills" lazy>
          <template #label>
            出入库单据
            <span v-if="summary?.todayBills" class="tab-badge">{{ summary.todayBills }}</span>
          </template>
          <StockBillTab ref="billRef" @new-bill="openBill()" @changed="refresh" />
        </el-tab-pane>
        <el-tab-pane name="alert" lazy>
          <template #label>
            库存预警
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
import { ElMessage } from 'element-plus'
import StockBalanceTab from '@/components/erp/StockBalanceTab.vue'
import StockBillTab from '@/components/erp/StockBillTab.vue'
import StockAlertTab from '@/components/erp/StockAlertTab.vue'
import StockBillDialog from '@/components/erp/StockBillDialog.vue'
import StocktakeDialog from '@/components/erp/StocktakeDialog.vue'
import { stockApi, type StockSummary } from '@/api/erp'
import { money, qty } from '@/utils/format'

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
    ElMessage.error(e?.message || '库存汇总加载失败')
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
