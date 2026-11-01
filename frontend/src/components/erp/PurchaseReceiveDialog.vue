<template>
  <el-dialog
    v-model="open"
    :title="isReturn ? t('erpPurRecv.titleReturn', { no: orderNo }) : t('erpPurRecv.titleReceive', { no: orderNo })"
    width="780px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <div class="bar">
      <label class="fld">
        <span>{{ t('erpPurRecv.warehouse') }}</span>
        <el-select v-model="whId" :placeholder="t('erpPurRecv.whPh')" filterable style="width:190px">
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
      </label>
      <label class="fld grow">
        <span>{{ t('erpPurRecv.remark') }}</span>
        <el-input v-model="remark" :placeholder="isReturn ? t('erpPurRecv.remarkReturnPh') : t('erpPurRecv.remarkPh')" />
      </label>
      <el-button v-if="!isReturn" text type="primary" @click="fillAll">{{ t('erpPurRecv.fillAll') }}</el-button>
    </div>

    <el-alert
      v-if="isReturn"
      type="warning"
      :closable="false"
      show-icon
      class="tip"
      :title="t('erpPurRecv.notice')"
    />

    <el-table :data="rows" v-loading="loading" max-height="380" stripe>
      <el-table-column :label="t('erpPurRecv.colCode')" width="112">
        <template #default="{ row }"><span class="num">{{ row.skuCode }}</span></template>
      </el-table-column>
      <el-table-column :label="t('erpPurRecv.colSku')" min-width="150">
        <template #default="{ row }">
          {{ row.skuName }}<span v-if="row.spec" class="spec">{{ row.spec }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('erpPurRecv.colOrdered')" width="90" align="right">
        <template #default="{ row }"><span class="num dim">{{ qty(row.qty) }}</span></template>
      </el-table-column>
      <el-table-column :label="t('erpPurRecv.colReceived')" width="90" align="right">
        <template #default="{ row }"><span class="num dim">{{ qty(row.receivedQty) }}</span></template>
      </el-table-column>
      <el-table-column :label="isReturn ? t('erpPurRecv.colReturnable') : t('erpPurRecv.colPending')" width="90" align="right">
        <template #default="{ row }">
          <span class="num">{{ qty(isReturn ? row.receivedQty : row.remainQty) }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="isReturn ? t('erpPurRecv.colReturnQty') : t('erpPurRecv.colReceiveQty')" width="132">
        <template #default="{ row }">
          <el-input v-model="row.input" inputmode="decimal" class="w100 inp-r" placeholder="0" />
        </template>
      </el-table-column>
      <el-table-column :label="t('erpPurRecv.colPrice')" width="90" align="right">
        <template #default="{ row }"><span class="num dim">{{ row.unitPrice == null ? '--' : money(row.unitPrice) }}</span></template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!rows.length && !loading" :description="t('erpPurRecv.noRows')" />

    <div class="foot">
      <span>{{ t('erpPurRecv.summary', { n: filledCount, q: qty(sumQty), m: money(sumAmount) }) }}</span>
    </div>

    <template #footer>
      <el-button @click="open = false">{{ t('erpPurRecv.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" :disabled="!filledCount" @click="submit">
        {{ isReturn ? t('erpPurRecv.confirmReturn') : t('erpPurRecv.confirmReceive') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { warehouseApi, type ErpWarehouse } from '@/api/erp'
import { purchaseApi } from '@/api/purchase'
import { money, qty } from '@/utils/format'

const { t, te } = useI18n()

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
    ElMessage.error(e?.message || t('erpPurRecv.orderFailed'))
  } finally {
    loading.value = false
  }
}

function fillAll() {
  rows.value.forEach((r) => { r.input = Number(r.remainQty) })
}

async function submit() {
  if (!whId.value) {
    ElMessage.warning(t('erpPurRecv.needWh'))
    return
  }
  const lines = filled.value.map((r) => ({
    itemId: r.itemId,
    qty: Number(r.input),
    unitPrice: r.unitPrice ?? null
  }))
  if (!lines.length) {
    ElMessage.warning(t('erpPurRecv.needQty'))
    return
  }
  saving.value = true
  try {
    const body = { warehouseId: whId.value, remark: remark.value, lines }
    const res = isReturn.value
      ? await purchaseApi.returnTo(orderNo.value, body)
      : await purchaseApi.receive(orderNo.value, body)
    if (!res.ok) {
      ElMessage.error(res.message || t('erpPurRecv.opFailed'))
      return
    }
    ElMessage.success(res.message || t('erpPurRecv.done'))
    emit('saved')
    open.value = false
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpPurRecv.opFailed'))
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
