<template>
  <el-dialog
    v-model="open"
    title="库存盘点"
    width="760px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <div class="bar">
      <label class="fld">
        <span>盘点仓库</span>
        <el-select v-model="whId" placeholder="选择仓库" filterable style="width:200px" @change="load">
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
      </label>
      <label class="fld grow">
        <span>盘点说明</span>
        <el-input v-model="remark" placeholder="选填，如 10 月末全仓盘点" />
      </label>
      <el-button :icon="Refresh" :loading="loading" @click="load">重新取账面</el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="tip"
      title="填“实盘数量”，系统只对差异生成盘点调整单：盘盈入账、盘亏出账，账面一致的行不会落单。"
    />

    <el-table :data="rows" v-loading="loading" max-height="380" stripe>
      <el-table-column label="编码" width="110">
        <template #default="{ row }"><span class="num">{{ row.code }}</span></template>
      </el-table-column>
      <el-table-column label="商品" min-width="150">
        <template #default="{ row }">
          {{ row.name }}<span v-if="row.spec" class="spec">{{ row.spec }}</span>
        </template>
      </el-table-column>
      <el-table-column label="单位" width="60">
        <template #default="{ row }">{{ row.unit || '--' }}</template>
      </el-table-column>
      <el-table-column label="账面" width="100" align="right">
        <template #default="{ row }"><span class="num dim">{{ qty(row.book) }}</span></template>
      </el-table-column>
      <el-table-column label="实盘" width="130">
        <template #default="{ row }">
          <el-input v-model="row.counted" inputmode="decimal" class="w100 inp-r" />
        </template>
      </el-table-column>
      <el-table-column label="差异" width="110" align="right">
        <template #default="{ row }">
          <span class="num" :class="diffClass(row)">{{ diffText(row) }}</span>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!rows.length && !loading" description="这个仓库还没有库存流水，没有可盘的商品" />

    <div class="foot">
      <span>共 <b class="num">{{ rows.length }}</b> 项，差异 <b class="num" :class="{ warn: diffCount > 0 }">{{ diffCount }}</b> 项</span>
    </div>

    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!diffCount" @click="submit">生成盘点单</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { stockApi, warehouseApi, type ErpWarehouse, type StockBalance } from '@/api/erp'
import { qty } from '@/utils/format'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

const open = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

interface Row {
  skuId: number
  code: string
  name: string
  spec?: string | null
  unit?: string | null
  book: number
  counted: number | string
  touched?: boolean
}

const whId = ref<number | null>(null)
const warehouses = ref<ErpWarehouse[]>([])
const rows = ref<Row[]>([])
const loading = ref(false)
const saving = ref(false)
const remark = ref('')

const diffCount = computed(() => rows.value.filter((r) => Math.abs(Number(r.counted) - r.book) > 0.00005).length)

function diffText(r: Row) {
  const d = Number(r.counted) - r.book
  if (Math.abs(d) <= 0.00005) return '0'
  return (d > 0 ? '+' : '') + qty(d)
}

function diffClass(r: Row) {
  const d = Number(r.counted) - r.book
  if (Math.abs(d) <= 0.00005) return 'dim'
  return d > 0 ? 'plus' : 'minus'
}

async function onOpen() {
  remark.value = ''
  rows.value = []
  try {
    warehouses.value = (await warehouseApi.list()).filter((w) => w.enabled)
    whId.value = warehouses.value.find((w) => w.default)?.id ?? warehouses.value[0]?.id ?? null
    if (whId.value) await load()
  } catch (e: any) {
    ElMessage.error(e?.message || '加载失败')
  }
}

async function load() {
  if (!whId.value) return
  loading.value = true
  try {
    const list: StockBalance[] = await stockApi.balances({ warehouseId: whId.value })
    rows.value = list.map((b) => ({
      skuId: b.skuId,
      code: b.code,
      name: b.name,
      spec: b.spec,
      unit: b.unit,
      book: Number(b.qty),
      counted: Number(b.qty),
      touched: false
    }))
  } catch (e: any) {
    ElMessage.error(e?.message || '取账面库存失败')
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!whId.value) return
  const lines = rows.value
    .filter((r) => Math.abs(Number(r.counted) - r.book) > 0.00005)
    .map((r) => ({ skuId: r.skuId, qty: Number(r.counted) }))
  if (!lines.length) {
    ElMessage.warning('没有差异，不需要生成盘点单')
    return
  }
  saving.value = true
  try {
    const res = await stockApi.stocktake({ warehouseId: whId.value, remark: remark.value, lines })
    if (!res.ok) {
      ElMessage.error(res.message || '盘点失败')
      return
    }
    ElMessage.success(res.message || '盘点单已生成')
    emit('saved')
    open.value = false
  } catch (e: any) {
    ElMessage.error(e?.message || '盘点失败')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.bar { display: flex; gap: var(--space-3); align-items: flex-end; margin-bottom: var(--space-3); flex-wrap: wrap; }
.fld { display: flex; flex-direction: column; gap: 6px; }
.fld > span { font-size: var(--text-xs); color: var(--muted); }
.grow { flex: 1; min-width: 200px; }
.tip { margin-bottom: var(--space-3); border-radius: var(--radius-md); }
.w100 { width: 100%; }
.spec { color: var(--meta); font-size: var(--text-2xs); margin-left: 6px; }
.dim { color: var(--muted); }
.plus { color: var(--run); }
.minus { color: var(--alarm); }
.foot { display: flex; justify-content: flex-end; gap: var(--space-2); font-size: var(--text-xs); color: var(--muted); padding-top: var(--space-2); }
.foot b { color: var(--fg); }
.foot .warn { color: var(--alarm); }
:deep(.inp-r .el-input__inner) { text-align: right; font-family: var(--font-mono); }
</style>
