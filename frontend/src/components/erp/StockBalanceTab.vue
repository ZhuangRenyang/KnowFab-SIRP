<template>
  <div>
    <div class="tab-toolbar">
      <el-select v-model="whId" :placeholder="t('erpBal.allWarehouses')" clearable style="width:170px" @change="load()">
        <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
      </el-select>
      <el-input
        v-model="q"
        :placeholder="t('erpBal.searchPh')"
        clearable
        :prefix-icon="Search"
        style="width:220px"
        @keyup.enter="load()"
        @clear="load()"
      />
      <el-button :icon="Refresh" :loading="loading" @click="load()">{{ t('erpBal.refresh') }}</el-button>
      <span class="auto-wrap" :title="t('erpBal.autoTip')">
        <el-switch v-model="autoRefresh" size="small" @change="onAutoChange" />
        <span class="auto-label">{{ t('erpBal.auto') }}</span>
      </span>
      <span class="spacer" />
      <el-button v-if="auth.canWrite" :icon="Files" @click="emit('stocktake')">{{ t('erpBal.stocktake') }}</el-button>
      <el-button v-if="auth.canWrite" type="primary" :icon="Plus" @click="emit('new-bill')">{{ t('erpBal.newBill') }}</el-button>
    </div>

    <el-table
      :data="rows"
      v-loading="loading"
      stripe
      show-summary
      :summary-method="summary"
      @row-dblclick="openFlow"
    >
      <el-table-column prop="code" :label="t('erpBal.colCode')" width="120">
        <template #default="{ row }"><span class="num">{{ row.code }}</span></template>
      </el-table-column>
      <el-table-column prop="name" :label="t('erpBal.colSku')" min-width="160">
        <template #default="{ row }">
          {{ row.name }}<span v-if="row.spec" class="spec">{{ row.spec }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="warehouseName" :label="t('erpBal.colWarehouse')" width="130" />
      <el-table-column :label="t('erpBal.colUnit')" width="70">
        <template #default="{ row }">{{ row.unit || '--' }}</template>
      </el-table-column>
      <el-table-column prop="qty" :label="t('erpBal.colQty')" width="130" align="right">
        <template #default="{ row }">
          <b class="num">{{ qty(row.qty) }}</b>
        </template>
      </el-table-column>
      <el-table-column prop="unitCost" :label="t('erpBal.colCost')" width="110" align="right">
        <template #default="{ row }"><span class="num dim">{{ row.unitCost ? money(row.unitCost) : '--' }}</span></template>
      </el-table-column>
      <el-table-column prop="amount" :label="t('erpBal.colAmount')" width="130" align="right">
        <template #default="{ row }"><span class="num">{{ money(row.amount) }}</span></template>
      </el-table-column>
      <el-table-column :label="t('erpBal.colAction')" width="100" align="right">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="openFlow(row)">{{ t('erpBal.flow') }}</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty
      v-if="!rows.length && !loading"
      :description="t('erpBal.empty')"
    />

    <el-drawer v-model="flowOpen" :title="flowTitle" size="540px">
      <div class="flow-head">
        <span>{{ t('erpBal.currentStock', { v: qty(flowStock) }) }}</span>
        <span class="dim">{{ t('erpBal.latestN', { n: flowRows.length }) }}</span>
      </div>
      <el-table :data="flowRows" v-loading="flowLoading" size="small">
        <el-table-column :label="t('erpBal.colTime')" width="130">
          <template #default="{ row }"><span class="num dim">{{ dt(row.createdAt) }}</span></template>
        </el-table-column>
        <el-table-column :label="t('erpBal.colBill')" min-width="150">
          <template #default="{ row }"><span class="num">{{ row.billNo }}</span></template>
        </el-table-column>
        <el-table-column :label="t('erpBal.colType')" width="90">
          <template #default="{ row }"><el-tag size="small" effect="plain">{{ row.typeLabel }}</el-tag></template>
        </el-table-column>
        <el-table-column :label="t('erpBal.colQtyShort')" width="100" align="right">
          <template #default="{ row }">
            <span class="num" :class="row.qty >= 0 ? 'plus' : 'minus'">{{ row.qty > 0 ? '+' : '' }}{{ qty(row.qty) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('erpBal.colBalance')" width="100" align="right">
          <template #default="{ row }"><span class="num">{{ qty(row.balance) }}</span></template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!flowRows.length && !flowLoading" :description="t('erpBal.noFlow')" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Files, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { stockApi, warehouseApi, type ErpWarehouse, type StockBalance, type StockFlowRow } from '@/api/erp'
import { useAuthStore } from '@/stores/auth'
import { dt, money, qty } from '@/utils/format'

const { t } = useI18n()

const emit = defineEmits<{ 'new-bill': []; stocktake: []; changed: [] }>()

const auth = useAuthStore()
const rows = ref<StockBalance[]>([])
const warehouses = ref<ErpWarehouse[]>([])
const whId = ref<number | null>(null)
const q = ref('')
const loading = ref(false)

const flowOpen = ref(false)
const flowLoading = ref(false)
const flowRows = ref<StockFlowRow[]>([])
const flowTitle = ref('')
const flowStock = ref(0)
const flowSku = ref<SkuRef | null>(null)

const autoRefresh = ref(true)
let timer: number | null = null

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    rows.value = await stockApi.balances({ warehouseId: whId.value, q: q.value })
    if (flowOpen.value && flowSku.value) {
      const live = rows.value.find(
        (r) => r.skuId === flowSku.value!.skuId && r.warehouseId === flowSku.value!.warehouseId
      )
      if (live) flowStock.value = Number(live.qty || 0)
    }
  } catch (e: any) {
    if (!silent) ElMessage.error(e?.message || t('erpBal.loadFailed'))
  } finally {
    if (!silent) loading.value = false
  }
}

function stopTimer() {
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
}

function startTimer() {
  stopTimer()
  timer = window.setInterval(() => {
    if (document.hidden) return
    load(true)
  }, 2000)
}

function onAutoChange(on: any) {
  if (on) startTimer()
  else stopTimer()
}

async function loadWarehouses() {
  try {
    warehouses.value = await warehouseApi.list()
  } catch {
    warehouses.value = []
  }
}

function summary({ columns }: any) {
  const totalQty = rows.value.reduce((a, r) => a + Number(r.qty || 0), 0)
  const totalAmount = rows.value.reduce((a, r) => a + Number(r.amount || 0), 0)
  return columns.map((c: any, i: number) => {
    if (i === 0) return t('erpBal.total')
    if (c.property === 'qty') return qty(totalQty)
    if (c.property === 'amount') return money(totalAmount)
    return ''
  })
}

interface SkuRef { skuId: number; code: string; name: string; warehouseId: number; qty: number }

async function openFlow(row: SkuRef) {
  flowTitle.value = `${row.code} · ${row.name}`
  flowStock.value = Number(row.qty || 0)
  flowSku.value = row
  flowOpen.value = true
  flowLoading.value = true
  try {
    flowRows.value = await stockApi.flow(row.skuId, row.warehouseId, 80)
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpBal.loadFlowFailed'))
    flowRows.value = []
  } finally {
    flowLoading.value = false
  }
}

onMounted(async () => {
  await loadWarehouses()
  await load()
  startTimer()
})

onUnmounted(stopTimer)

defineExpose({ load })
</script>

<style scoped>
.tab-toolbar { display: flex; gap: 10px; margin-bottom: var(--space-3); flex-wrap: wrap; align-items: center; }
.tab-toolbar .spacer { flex: 1; }
.auto-wrap { display: inline-flex; align-items: center; gap: 6px; }
.auto-label { font-size: var(--text-xs); color: var(--muted); }
.spec { color: var(--meta); font-size: var(--text-2xs); margin-left: 6px; }
.dim { color: var(--muted); }
.plus { color: var(--run); }
.minus { color: var(--alarm); }
.flow-head {
  display: flex; justify-content: space-between; align-items: baseline;
  padding: 0 2px var(--space-3); font-size: var(--text-sm); color: var(--muted);
}
.flow-head b { color: var(--fg); font-size: var(--text-lg); }
</style>
