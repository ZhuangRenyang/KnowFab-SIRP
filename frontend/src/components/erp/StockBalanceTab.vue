<template>
  <div>
    <div class="tab-toolbar">
      <el-select v-model="whId" placeholder="全部仓库" clearable style="width:170px" @change="load">
        <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
      </el-select>
      <el-input
        v-model="q"
        placeholder="搜编码 / 名称 / 规格"
        clearable
        :prefix-icon="Search"
        style="width:220px"
        @keyup.enter="load"
        @clear="load"
      />
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <span class="spacer" />
      <el-button v-if="auth.canWrite" :icon="Files" @click="emit('stocktake')">盘点</el-button>
      <el-button v-if="auth.canWrite" type="primary" :icon="Plus" @click="emit('new-bill')">新建单据</el-button>
    </div>

    <el-table
      :data="rows"
      v-loading="loading"
      stripe
      show-summary
      :summary-method="summary"
      @row-dblclick="openFlow"
    >
      <el-table-column prop="code" label="编码" width="120">
        <template #default="{ row }"><span class="num">{{ row.code }}</span></template>
      </el-table-column>
      <el-table-column prop="name" label="商品" min-width="160">
        <template #default="{ row }">
          {{ row.name }}<span v-if="row.spec" class="spec">{{ row.spec }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="warehouseName" label="仓库" width="130" />
      <el-table-column label="单位" width="70">
        <template #default="{ row }">{{ row.unit || '--' }}</template>
      </el-table-column>
      <el-table-column prop="qty" label="库存数量" width="130" align="right">
        <template #default="{ row }">
          <b class="num">{{ qty(row.qty) }}</b>
        </template>
      </el-table-column>
      <el-table-column prop="unitCost" label="成本价" width="110" align="right">
        <template #default="{ row }"><span class="num dim">{{ row.unitCost ? money(row.unitCost) : '--' }}</span></template>
      </el-table-column>
      <el-table-column prop="amount" label="库存金额" width="130" align="right">
        <template #default="{ row }"><span class="num">{{ money(row.amount) }}</span></template>
      </el-table-column>
      <el-table-column label="操作" width="100" align="right">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="openFlow(row)">流水</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty
      v-if="!rows.length && !loading"
      description="还没有库存。先在「基础资料」建商品和仓库，再用「新建单据」做一笔采购入库"
    />

    <el-drawer v-model="flowOpen" :title="flowTitle" size="540px">
      <div class="flow-head">
        <span>当前库存 <b class="num">{{ qty(flowStock) }}</b></span>
        <span class="dim">最近 {{ flowRows.length }} 笔</span>
      </div>
      <el-table :data="flowRows" v-loading="flowLoading" size="small">
        <el-table-column label="时间" width="130">
          <template #default="{ row }"><span class="num dim">{{ dt(row.createdAt) }}</span></template>
        </el-table-column>
        <el-table-column label="单号" min-width="150">
          <template #default="{ row }"><span class="num">{{ row.billNo }}</span></template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }"><el-tag size="small" effect="plain">{{ row.typeLabel }}</el-tag></template>
        </el-table-column>
        <el-table-column label="数量" width="100" align="right">
          <template #default="{ row }">
            <span class="num" :class="row.qty >= 0 ? 'plus' : 'minus'">{{ row.qty > 0 ? '+' : '' }}{{ qty(row.qty) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结存" width="100" align="right">
          <template #default="{ row }"><span class="num">{{ qty(row.balance) }}</span></template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!flowRows.length && !flowLoading" description="没有流水记录" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Files, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { stockApi, warehouseApi, type ErpWarehouse, type StockBalance, type StockFlowRow } from '@/api/erp'
import { useAuthStore } from '@/stores/auth'
import { dt, money, qty } from '@/utils/format'

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

async function load() {
  loading.value = true
  try {
    rows.value = await stockApi.balances({ warehouseId: whId.value, q: q.value })
  } catch (e: any) {
    ElMessage.error(e?.message || '加载库存失败')
  } finally {
    loading.value = false
  }
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
    if (i === 0) return '合计'
    if (c.property === 'qty') return qty(totalQty)
    if (c.property === 'amount') return money(totalAmount)
    return ''
  })
}

interface SkuRef { skuId: number; code: string; name: string; warehouseId: number; qty: number }

async function openFlow(row: SkuRef) {
  flowTitle.value = `${row.code} · ${row.name}`
  flowStock.value = Number(row.qty || 0)
  flowOpen.value = true
  flowLoading.value = true
  try {
    flowRows.value = await stockApi.flow(row.skuId, row.warehouseId, 80)
  } catch (e: any) {
    ElMessage.error(e?.message || '流水加载失败')
    flowRows.value = []
  } finally {
    flowLoading.value = false
  }
}

onMounted(async () => {
  await loadWarehouses()
  await load()
})

defineExpose({ load })
</script>

<style scoped>
.tab-toolbar { display: flex; gap: 10px; margin-bottom: var(--space-3); flex-wrap: wrap; align-items: center; }
.tab-toolbar .spacer { flex: 1; }
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
