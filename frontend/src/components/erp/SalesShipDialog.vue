<template>
  <el-dialog
    v-model="open"
    :title="(isReturn ? '客户退货 · ' : '销售发货 · ') + orderNo"
    width="800px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <div class="bar">
      <label class="fld">
        <span>发货仓库</span>
        <el-select v-model="whId" placeholder="选择仓库" filterable style="width:190px" @change="loadStock">
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
      </label>
      <label class="fld grow">
        <span>备注</span>
        <el-input v-model="remark" :placeholder="isReturn ? '选填，如 客户退回不良品' : '选填，如 物流单号'" />
      </label>
      <el-button v-if="!isReturn" text type="primary" @click="fillAll">全部发货</el-button>
    </div>

    <el-alert
      v-if="isReturn"
      type="warning"
      :closable="false"
      show-icon
      class="tip"
      title="退货会生成一张正数的销售退货单，货重新进仓；退货数量不能超过已净发货量。"
    />
    <el-alert
      v-else-if="shortRows.length"
      type="error"
      :closable="false"
      show-icon
      class="tip"
      :title="`有 ${shortRows.length} 行库存不够，发货会被拒绝：请先补货、换仓或减少数量`"
    />

    <el-table :data="rows" v-loading="loading" max-height="380" stripe>
      <el-table-column label="编码" width="108">
        <template #default="{ row }"><span class="num">{{ row.skuCode }}</span></template>
      </el-table-column>
      <el-table-column label="商品" min-width="140">
        <template #default="{ row }">
          {{ row.skuName }}<span v-if="row.spec" class="spec">{{ row.spec }}</span>
        </template>
      </el-table-column>
      <el-table-column label="订货" width="84" align="right">
        <template #default="{ row }"><span class="num dim">{{ qty(row.qty) }}</span></template>
      </el-table-column>
      <el-table-column label="已发货" width="84" align="right">
        <template #default="{ row }"><span class="num dim">{{ qty(row.shippedQty) }}</span></template>
      </el-table-column>
      <el-table-column :label="isReturn ? '可退' : '待发货'" width="84" align="right">
        <template #default="{ row }">
          <span class="num">{{ qty(isReturn ? row.shippedQty : row.remainQty) }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="!isReturn" label="可用库存" width="92" align="right">
        <template #default="{ row }">
          <span class="num" :class="Number(row.input) > row.available ? 'minus' : ''">{{ qty(row.available) }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="isReturn ? '退货数量' : '本次发货'" width="128">
        <template #default="{ row }">
          <el-input v-model="row.input" inputmode="decimal" class="w100 inp-r" placeholder="0" />
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!rows.length && !loading" description="订单没有可操作的行" />

    <div class="foot">
      <span>本次 <b class="num">{{ filledCount }}</b> 项 · 数量 <b class="num">{{ qty(sumQty) }}</b>
        · 金额 <b class="num">¥{{ money(sumAmount) }}</b></span>
    </div>

    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!filledCount || (!isReturn && shortRows.length > 0)" @click="submit">
        {{ isReturn ? '确认退货' : '确认发货' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { stockApi, warehouseApi, type ErpWarehouse } from '@/api/erp'
import { salesApi } from '@/api/sales'
import { money, qty } from '@/utils/format'

const props = defineProps<{ modelValue: boolean; orderNo: string; mode: 'ship' | 'return' }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

const open = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})
const isReturn = computed(() => props.mode === 'return')
const orderNo = computed(() => props.orderNo)

interface Row {
  itemId: number
  skuId: number
  skuCode: string
  skuName: string
  spec?: string | null
  qty: number
  shippedQty: number
  remainQty: number
  available: number
  unitPrice?: number | null
  input: number | string
}

const rows = ref<Row[]>([])
const warehouses = ref<ErpWarehouse[]>([])
const whId = ref<number | null>(null)
const remark = ref('')
const loading = ref(false)
const saving = ref(false)

const filled = computed(() => rows.value.filter((r) => Number(r.input) > 0))
const filledCount = computed(() => filled.value.length)
const sumQty = computed(() => filled.value.reduce((a, r) => a + Number(r.input), 0))
const sumAmount = computed(() => filled.value.reduce((a, r) => a + Number(r.input) * Number(r.unitPrice || 0), 0))
/** 发货时库存不够的行（前端先提示，最终以后端校验为准） */
const shortRows = computed(() => (isReturn.value ? [] : rows.value.filter((r) => Number(r.input) > r.available)))

async function onOpen() {
  remark.value = ''
  rows.value = []
  try {
    warehouses.value = (await warehouseApi.list()).filter((w) => w.enabled)
    whId.value = warehouses.value.find((w) => w.default)?.id ?? warehouses.value[0]?.id ?? null
  } catch {
    warehouses.value = []
  }
  loading.value = true
  try {
    const d = await salesApi.detail(orderNo.value)
    rows.value = d.lines.map((l) => ({
      itemId: l.itemId,
      skuId: l.skuId,
      skuCode: l.skuCode,
      skuName: l.skuName,
      spec: l.spec,
      qty: Number(l.qty),
      shippedQty: Number(l.shippedQty),
      remainQty: Number(l.remainQty),
      available: 0,
      unitPrice: l.unitPrice,
      input: isReturn.value ? 0 : Number(l.remainQty)
    }))
    await loadStock()
  } catch (e: any) {
    ElMessage.error(e?.message || '订单加载失败')
  } finally {
    loading.value = false
  }
}

/** 取目标仓可用库存：发货前先让用户看见"这个仓够不够"，而不是提交后才报错 */
async function loadStock() {
  if (!whId.value || isReturn.value) return
  try {
    const list = await stockApi.balances({ warehouseId: whId.value })
    const map = new Map<number, number>()
    list.forEach((b) => map.set(b.skuId, Number(b.qty)))
    rows.value.forEach((r) => {
      r.available = map.get(r.skuId) ?? 0
      // 默认发货量不超过可用库存，避免一打开就红一片
      if (Number(r.input) > r.available) r.input = r.available > 0 ? r.available : 0
    })
  } catch {
    /* 取不到库存就按 0 显示，提交时后端还会校验 */
  }
}

function fillAll() {
  rows.value.forEach((r) => {
    r.input = Math.min(Number(r.remainQty), r.available)
  })
}

async function submit() {
  if (!whId.value) {
    ElMessage.warning('请选择发货仓库')
    return
  }
  const lines = filled.value.map((r) => ({
    itemId: r.itemId,
    qty: Number(r.input),
    unitPrice: r.unitPrice ?? null
  }))
  if (!lines.length) {
    ElMessage.warning('请填写数量')
    return
  }
  saving.value = true
  try {
    const body = { warehouseId: whId.value, remark: remark.value, lines }
    const res = isReturn.value
      ? await salesApi.returnFrom(orderNo.value, body)
      : await salesApi.ship(orderNo.value, body)
    if (!res.ok) {
      ElMessage.error(res.message || '操作失败')
      return
    }
    ElMessage.success(res.message || '已完成')
    emit('saved')
    open.value = false
  } catch (e: any) {
    ElMessage.error(e?.message || '操作失败')
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
.minus { color: var(--alarm); }
.foot { display: flex; justify-content: flex-end; padding-top: var(--space-2); font-size: var(--text-xs); color: var(--muted); }
.foot b { color: var(--fg); }
:deep(.inp-r .el-input__inner) { text-align: right; font-family: var(--font-mono); }
</style>
