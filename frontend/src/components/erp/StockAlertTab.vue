<template>
  <div>
    <div class="tab-toolbar">
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <span class="hint">按商品全厂合计比上下限（多仓企业不会每个仓各报一次）</span>
      <span class="spacer" />
      <el-button v-if="auth.canWrite" type="primary" :icon="ShoppingCart" @click="emit('new-bill', 'PO_IN')">
        补货入库
      </el-button>
    </div>

    <el-alert
      v-if="!loading && !rows.length"
      type="success"
      :closable="false"
      show-icon
      title="没有预警：所有商品的库存都在上下限之间"
      description="上下限在「基础资料 → 商品」里维护；没填上下限的商品不参与预警。"
      class="ok-tip"
    />

    <el-table v-else :data="rows" v-loading="loading" stripe row-key="skuId">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="wh-list">
            <div v-for="w in row.byWarehouse" :key="w.warehouseId" class="wh-item">
              <span>{{ w.warehouseName }}</span>
              <b class="num">{{ qty(w.qty) }}</b>
            </div>
            <div v-if="!row.byWarehouse.length" class="dim">没有库存流水</div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="编码" width="130">
        <template #default="{ row }"><span class="num">{{ row.code }}</span></template>
      </el-table-column>
      <el-table-column label="商品" min-width="170">
        <template #default="{ row }">
          {{ row.name }}<span v-if="row.spec" class="spec">{{ row.spec }}</span>
        </template>
      </el-table-column>
      <el-table-column label="单位" width="70">
        <template #default="{ row }">{{ row.unit || '--' }}</template>
      </el-table-column>
      <el-table-column label="全厂库存" width="120" align="right">
        <template #default="{ row }"><b class="num" :class="row.status === 'LOW' ? 'minus' : 'plus'">{{ qty(row.total) }}</b></template>
      </el-table-column>
      <el-table-column label="下限" width="100" align="right">
        <template #default="{ row }"><span class="num dim">{{ row.minStock == null ? '--' : qty(row.minStock) }}</span></template>
      </el-table-column>
      <el-table-column label="上限" width="100" align="right">
        <template #default="{ row }"><span class="num dim">{{ row.maxStock == null ? '--' : qty(row.maxStock) }}</span></template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" :type="row.status === 'LOW' ? 'danger' : 'warning'">
            {{ row.status === 'LOW' ? '低于下限' : '超过上限' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="缺口 / 超储" width="130" align="right">
        <template #default="{ row }">
          <span class="num" :class="row.status === 'LOW' ? 'minus' : 'plus'">{{ qty(row.gap) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="建议" min-width="150">
        <template #default="{ row }">
          <span class="dim">{{ row.status === 'LOW' ? '安排采购或调拨补货' : '暂停生产或安排出货清库' }}</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, ShoppingCart } from '@element-plus/icons-vue'
import { stockApi, type StockAlert } from '@/api/erp'
import { useAuthStore } from '@/stores/auth'
import { qty } from '@/utils/format'

const emit = defineEmits<{ 'new-bill': [type: string] }>()

const auth = useAuthStore()
const rows = ref<StockAlert[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    rows.value = await stockApi.alerts()
  } catch (e: any) {
    ElMessage.error(e?.message || '预警加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
defineExpose({ load })
</script>

<style scoped>
.tab-toolbar { display: flex; gap: 10px; margin-bottom: var(--space-3); flex-wrap: wrap; align-items: center; }
.tab-toolbar .spacer { flex: 1; }
.hint { font-size: var(--text-xs); color: var(--meta); }
.ok-tip { border-radius: var(--radius-md); }
.spec { color: var(--meta); font-size: var(--text-2xs); margin-left: 6px; }
.dim { color: var(--muted); }
.plus { color: var(--run); }
.minus { color: var(--alarm); }
.wh-list { display: flex; gap: var(--space-6); flex-wrap: wrap; padding: 4px 48px; }
.wh-item { display: flex; gap: 8px; align-items: baseline; font-size: var(--text-sm); color: var(--muted); }
.wh-item b { color: var(--fg); }
</style>
