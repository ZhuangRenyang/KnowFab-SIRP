<template>
  <div class="sales-q">
    <el-alert type="info" :closable="false" show-icon class="tip">
      <template #title>
        可承诺量 = 现存量 − 已确认订单未发货数量。答应客户前先看这一列，别只看现存。
      </template>
    </el-alert>

    <div class="kpis">
      <div class="kpi">
        <span>可查商品</span>
        <b class="num">{{ data?.totals.skuCount || 0 }}<i>种</i></b>
        <em>含库存的商品</em>
      </div>
      <div class="kpi">
        <span>可承诺品种</span>
        <b class="num up">{{ okCount }}<i>种</i></b>
        <em>可承诺 &gt; 0，能接单</em>
      </div>
      <div class="kpi">
        <span>库存紧张</span>
        <b class="num warn">{{ lowCount }}<i>种</i></b>
        <em>可承诺低于安全库存</em>
      </div>
      <div class="kpi">
        <span>已无货</span>
        <b class="num down">{{ outCount }}<i>种</i></b>
        <em>可承诺为 0，需排产/采购</em>
      </div>
    </div>

    <el-card shadow="never">
      <div class="tab-toolbar">
        <el-input v-model="kw" placeholder="商品编码 / 名称 / 规格" clearable :prefix-icon="Search"
          style="width:230px" @keyup.enter="load" @clear="load" />
        <el-select v-model="category" placeholder="全部类别" clearable style="width:140px" @change="load">
          <el-option v-for="c in categoryOptions" :key="c" :label="c" :value="c" />
        </el-select>
        <el-select v-model="whId" placeholder="全部仓库" clearable style="width:150px" @change="load">
          <el-option v-for="w in data?.warehouses || []" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
        <el-switch v-model="onlyAvailable" active-text="只看有货" @change="load" />
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <span class="spacer" />
        <el-button type="primary" :icon="Download" :loading="exporting" @click="doExport">导出 Excel</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column label="商品" min-width="230">
          <template #default="{ row }">
            <div class="pname">{{ row.name }}</div>
            <div class="dim sub">
              <span class="num">{{ row.code }}</span>
              <span v-if="row.spec"> · {{ row.spec }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="category" label="类别" width="100">
          <template #default="{ row }"><span class="dim">{{ row.category || '--' }}</span></template>
        </el-table-column>
        <el-table-column label="现存量" width="110" align="right">
          <template #default="{ row }">
            <span class="num">{{ qty(row.onHand) }}</span>
            <span class="dim unit">{{ row.unit || '' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="订单占用" width="110" align="right">
          <template #default="{ row }">
            <span class="num dim">{{ qty(row.allocated) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="可承诺" width="120" align="right">
          <template #default="{ row }">
            <b class="num" :class="stateClass(row.state)">{{ qty(row.available) }}</b>
            <span class="dim unit">{{ row.unit || '' }}</span>
            <div v-if="Number(row.shortage) > 0" class="shortage">欠 {{ qty(row.shortage) }}</div>
            <div v-if="Number(row.shortage) > 0" class="shortage">欠 {{ qty(row.shortage) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="92">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.state)" effect="plain">{{ stateText(row.state) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="所在仓库" min-width="200" show-overflow-tooltip>
          <template #default="{ row }"><span class="dim">{{ row.warehouseText }}</span></template>
        </el-table-column>
        <el-table-column label="销售价" width="110" align="right">
          <template #default="{ row }">
            <span v-if="row.salePrice" class="num">{{ money(row.salePrice) }}</span>
            <span v-else class="dim">--</span>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!loading && !rows.length" class="empty">
        没有符合条件的商品。换个关键词，或关掉「只看有货」。
      </div>
      <div class="foot-note">{{ data?.note }}</div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Refresh, Search } from '@element-plus/icons-vue'
import { exportSalesStock, salesQueryApi, type SalesStockResult, type SalesStockRow } from '@/api/salesQuery'
import { money, qty } from '@/utils/format'

const data = ref<SalesStockResult | null>(null)
const loading = ref(false)
const exporting = ref(false)
const kw = ref('')
const category = ref('')
const whId = ref<number | null>(null)
const onlyAvailable = ref(false)

const rows = computed<SalesStockRow[]>(() => data.value?.rows || [])
/** 类别下拉的选项固定用"未筛选"时的全集，否则选了类别后其他类别会从下拉里消失 */
const categoryOptions = ref<string[]>([])
const okCount = computed(() => rows.value.filter((r) => r.state === 'OK').length)
const lowCount = computed(() => rows.value.filter((r) => r.state === 'LOW').length)
const outCount = computed(() => rows.value.filter((r) => r.state === 'OUT').length)

function stateText(s: string) {
  return s === 'OUT' ? '已无货' : s === 'LOW' ? '紧张' : '充足'
}
function tagType(s: string) {
  return s === 'OUT' ? 'danger' : s === 'LOW' ? 'warning' : 'success'
}
function stateClass(s: string) {
  return s === 'OUT' ? 'down' : s === 'LOW' ? 'warn' : 'up'
}

async function load() {
  loading.value = true
  try {
    data.value = await salesQueryApi.stock({
      kw: kw.value,
      category: category.value,
      warehouseId: whId.value,
      onlyAvailable: onlyAvailable.value
    })
    if (!category.value) {
      const set = new Set<string>()
      for (const r of data.value.rows) {
        if (r.category) set.add(r.category)
      }
      categoryOptions.value = Array.from(set)
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '查询失败')
  } finally {
    loading.value = false
  }
}

async function doExport() {
  exporting.value = true
  try {
    await exportSalesStock({
      kw: kw.value,
      category: category.value,
      warehouseId: whId.value,
      onlyAvailable: onlyAvailable.value
    })
    ElMessage.success('已导出 Excel')
  } catch (e: any) {
    ElMessage.error(e?.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.sales-q { display: flex; flex-direction: column; gap: var(--space-4); }
.tip { border-radius: var(--radius-md); }
.tip :deep(.el-alert__title) { font-size: var(--text-xs); }
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
.pname { font-weight: 600; }
.sub { font-size: var(--text-2xs); }
.unit { font-size: var(--text-2xs); margin-left: 2px; }
.dim { color: var(--muted); }
.up { color: var(--run); }
.warn { color: var(--idle); }
.down { color: var(--alarm); }
.empty { padding: var(--space-4) 0; text-align: center; font-size: var(--text-xs); color: var(--meta); }
.foot-note { padding-top: var(--space-3); font-size: var(--text-2xs); color: var(--meta); }
.shortage { font-size: var(--text-2xs); color: var(--alarm); }
.shortage { font-size: var(--text-2xs); color: var(--alarm); }
@media (max-width: 1200px) {
  .kpis { grid-template-columns: repeat(2, 1fr); }
}
</style>
