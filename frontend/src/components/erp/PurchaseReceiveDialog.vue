<template>
  <el-dialog
    v-model="open"
    :title="(isReturn ? '退货给供应商 · ' : '采购入库 · ') + orderNo"
    width="780px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <div class="bar">
      <label class="fld">
        <span>收货仓库</span>
        <el-select v-model="whId" placeholder="选择仓库" filterable style="width:190px">
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
      </label>
      <label class="fld grow">
        <span>备注</span>
        <el-input v-model="remark" :placeholder="isReturn ? '选填，如 尺寸不符退回' : '选填，如 送货单号'" />
      </label>
      <el-button v-if="!isReturn" text type="primary" @click="fillAll">全部入库</el-button>
    </div>

    <el-alert
      v-if="isReturn"
      type="warning"
      :closable="false"
      show-icon
      class="tip"
      title="退货会生成一张负数的采购退货单，库存相应减少；退货数量不能超过已净入库量。"
    />

    <el-table :data="rows" v-loading="loading" max-height="380" stripe>
      <el-table-column label="编码" width="112">
        <template #default="{ row }"><span class="num">{{ row.skuCode }}</span></template>
      </el-table-column>
      <el-table-column label="商品" min-width="150">
        <template #default="{ row }">
          {{ row.skuName }}<span v-if="row.spec" class="spec">{{ row.spec }}</span>
        </template>
      </el-table-column>
      <el-table-column label="订货" width="90" align="right">
        <template #default="{ row }"><span class="num dim">{{ qty(row.qty) }}</span></template>
      </el-table-column>
      <el-table-column label="已入库" width="90" align="right">
        <template #default="{ row }"><span class="num dim">{{ qty(row.receivedQty) }}</span></template>
      </el-table-column>
      <el-table-column :label="isReturn ? '可退' : '待入库'" width="90" align="right">
        <template #default="{ row }">
          <span class="num">{{ qty(isReturn ? row.receivedQty : row.remainQty) }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="isReturn ? '退货数量' : '本次入库'" width="132">
        <template #default="{ row }">
          <el-input v-model="row.input" inputmode="decimal" class="w100 inp-r" placeholder="0" />
        </template>
      </el-table-column>
      <el-table-column label="单价" width="90" align="right">
        <template #default="{ row }"><span class="num dim">{{ row.unitPrice == null ? '--' : money(row.unitPrice) }}</span></template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!rows.length && !loading" description="订单没有可操作的行" />

    <div class="foot">
      <span>本次 <b class="num">{{ filledCount }}</b> 项 · 数量 <b class="num">{{ qty(sumQty) }}</b> · 金额 <b class="num">¥{{ money(sumAmount) }}</b></span>
    </div>

    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!filledCount" @click="submit">
        {{ isReturn ? '确认退货' : '确认入库' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { warehouseApi, type ErpWarehouse } from '@/api/erp'
import { purchaseApi } from '@/api/purchase'
import { money, qty } from '@/utils/format'

const props = defineProps<{
  modelValue: boolean
  orderNo: string
  mode: 'receive' | 'return'
}>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

const open = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})
const isReturn = computed(() => props.mode === 'return')
const orderNo = computed(() => props.orderNo)

interface Row {
  itemId: number
  skuCode: string
  skuName: string
  spec?: string | null
  unit?: string | null
  qty: number
  receivedQty: number
  remainQty: number
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
    const d = await purchaseApi.detail(orderNo.value)
    rows.value = d.lines.map((l) => ({
      itemId: l.itemId,
      skuCode: l.skuCode,
      skuName: l.skuName,
      spec: l.spec,
      unit: l.unit,
      qty: Number(l.qty),
      receivedQty: Number(l.receivedQty),
      remainQty: Number(l.remainQty),
      unitPrice: l.unitPrice,
      // 入库默认填满待入库量（现场最常见就是整单收），退货默认 0 防手滑
      input: isReturn.value ? 0 : Number(l.remainQty)
    }))
  } catch (e: any) {
    ElMessage.error(e?.message || '订单加载失败')
  } finally {
    loading.value = false
  }
}

function fillAll() {
  rows.value.forEach((r) => { r.input = Number(r.remainQty) })
}

async function submit() {
  if (!whId.value) {
    ElMessage.warning('请选择收货仓库')
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
      ? await purchaseApi.returnTo(orderNo.value, body)
      : await purchaseApi.receive(orderNo.value, body)
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
.foot { display: flex; justify-content: flex-end; padding-top: var(--space-2); font-size: var(--text-xs); color: var(--muted); }
.foot b { color: var(--fg); }
:deep(.inp-r .el-input__inner) { text-align: right; font-family: var(--font-mono); }
</style>
