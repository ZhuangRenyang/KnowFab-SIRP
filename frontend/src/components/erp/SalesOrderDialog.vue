<template>
  <el-dialog
    v-model="open"
    :title="orderNo ? t('erpSalesOrder.titleEdit', { no: orderNo }) : t('erpSalesOrder.titleNew')"
    width="860px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <div class="head-grid">
      <label class="fld">
        <span>{{ t('erpSalesOrder.customer') }}</span>
        <el-select v-model="customerId" :placeholder="t('erpSalesOrder.customerPh')" filterable>
          <el-option v-for="p in customers" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
      </label>
      <label class="fld">
        <span>{{ t('erpSalesOrder.orderDate') }}</span>
        <el-date-picker v-model="orderDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
      </label>
      <label class="fld">
        <span>{{ t('erpSalesOrder.deliverDate') }}</span>
        <el-date-picker v-model="deliverDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :placeholder="t('erpSalesOrder.optional')" />
      </label>
    </div>

    <table class="lines">
      <thead>
        <tr>
          <th style="width:34px">#</th>
          <th>{{ t('erpSalesOrder.thSku') }}</th>
          <th style="width:118px">{{ t('erpSalesOrder.thQty') }}</th>
          <th style="width:126px">{{ t('erpSalesOrder.thPrice') }}</th>
          <th style="width:104px" class="r">{{ t('erpSalesOrder.thAmount') }}</th>
          <th style="width:40px"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(l, i) in lines" :key="i">
          <td class="idx num">{{ i + 1 }}</td>
          <td>
            <el-select v-model="l.skuId" :placeholder="t('erpSalesOrder.skuPh')" filterable class="w100" @change="onSku(l)">
              <el-option v-for="s in skus" :key="s.id" :label="`${s.code} · ${s.name}`" :value="s.id">
                <span class="opt-code num">{{ s.code }}</span>
                <span>{{ s.name }}</span>
                <span v-if="s.spec" class="opt-spec">{{ s.spec }}</span>
              </el-option>
            </el-select>
          </td>
          <td><el-input v-model="l.qty" inputmode="decimal" class="w100 inp-r" placeholder="0" /></td>
          <td><el-input v-model="l.unitPrice" inputmode="decimal" class="w100 inp-r" :placeholder="t('erpSalesOrder.pricePh')" /></td>
          <td class="r num">{{ Number(l.qty) > 0 ? money(Number(l.qty) * Number(l.unitPrice || 0)) : '--' }}</td>
          <td>
            <el-button text :icon="Delete" :disabled="lines.length <= 1" @click="lines.splice(i, 1)" />
          </td>
        </tr>
      </tbody>
    </table>

    <div class="lines-foot">
      <el-button text type="primary" :icon="Plus" @click="addLine">{{ t('erpSalesOrder.addLine') }}</el-button>
      <span class="spacer" />
      <span class="sum">{{ t('erpSalesOrder.summary', { n: lines.length, q: qty(totalQty), m: money(totalAmount) }) }}</span>
    </div>

    <el-input v-model="remark" type="textarea" :rows="2" :placeholder="t('erpSalesOrder.remarkPh')" />

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="tip"
      :title="t('erpSalesOrder.notice')"
    />

    <template #footer>
      <el-button @click="open = false">{{ t('erpSalesOrder.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ t('erpSalesOrder.saveDraft') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import { partnerApi, skuApi, type ErpPartner, type ErpSku } from '@/api/erp'
import { salesApi } from '@/api/sales'
import { money, qty } from '@/utils/format'

const { t } = useI18n()

const props = defineProps<{ modelValue: boolean; orderNo?: string | null }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

const open = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})
const orderNo = computed(() => props.orderNo || '')

const customers = ref<ErpPartner[]>([])
const skus = ref<ErpSku[]>([])
const customerId = ref<number | null>(null)
const orderDate = ref('')
const deliverDate = ref('')
const remark = ref('')
const saving = ref(false)

interface Line { skuId: number | null; qty: number | string; unitPrice: number | string | null }
const lines = ref<Line[]>([{ skuId: null, qty: 1, unitPrice: null }])

const totalQty = computed(() => lines.value.reduce((a, l) => a + (l.skuId ? Number(l.qty || 0) : 0), 0))
const totalAmount = computed(() => lines.value.reduce(
  (a, l) => a + (l.skuId ? Number(l.qty || 0) * Number(l.unitPrice || 0) : 0), 0))

function addLine() {
  lines.value.push({ skuId: null, qty: 1, unitPrice: null })
}

function onSku(l: Line) {
  const s = skus.value.find((x) => x.id === l.skuId)
  if (s && l.unitPrice == null) l.unitPrice = s.salePrice ?? s.purchasePrice ?? null
}

const today = () => {
  const d = new Date(), p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
function dayStr(ts: number) {
  const d = new Date(ts), p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

async function onOpen() {
  orderDate.value = today()
  deliverDate.value = ''
  remark.value = ''
  customerId.value = null
  lines.value = [{ skuId: null, qty: 1, unitPrice: null }]
  try {
    const [p, s] = await Promise.all([partnerApi.list(undefined, 'CUSTOMER'), skuApi.list()])
    customers.value = p
    skus.value = s.filter((x) => x.enabled)
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpSalesOrder.baseFailed'))
  }
  if (orderNo.value) {
    try {
      const d = await salesApi.detail(orderNo.value)
      customerId.value = d.order.customerId ?? null
      orderDate.value = dayStr(d.order.orderDate)
      deliverDate.value = d.order.deliverDate ? dayStr(d.order.deliverDate) : ''
      remark.value = d.order.remark || ''
      lines.value = d.lines.map((l) => ({
        skuId: l.skuId, qty: Number(l.qty), unitPrice: l.unitPrice ?? null
      }))
    } catch (e: any) {
      ElMessage.error(e?.message || t('erpSalesOrder.orderFailed'))
    }
  }
}

function validate(): string | null {
  if (!customerId.value) return t('erpSalesOrder.needCustomer')
  const filled = lines.value.filter((l) => l.skuId)
  if (!filled.length) return t('erpSalesOrder.needSku')
  if (filled.some((l) => !(Number(l.qty) > 0))) return t('erpSalesOrder.needQty')
  const ids = filled.map((l) => l.skuId)
  if (new Set(ids).size !== ids.length) return t('erpSalesOrder.dupSku')
  return null
}

async function submit() {
  const err = validate()
  if (err) {
    ElMessage.warning(err)
    return
  }
  saving.value = true
  try {
    const body = {
      customerId: customerId.value as number,
      orderDate: orderDate.value,
      deliverDate: deliverDate.value || undefined,
      remark: remark.value,
      lines: lines.value
        .filter((l) => l.skuId && Number(l.qty) > 0)
        .map((l) => ({
          skuId: l.skuId as number,
          qty: Number(l.qty),
          unitPrice: l.unitPrice === null || l.unitPrice === '' ? null : Number(l.unitPrice)
        }))
    }
    const res = orderNo.value
      ? await salesApi.update(orderNo.value, body)
      : await salesApi.create(body)
    if (!res.ok) {
      ElMessage.error(res.message || t('erpSalesOrder.saveFailed'))
      return
    }
    ElMessage.success(res.message || t('erpSalesOrder.saved'))
    emit('saved')
    open.value = false
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpSalesOrder.saveFailed'))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.head-grid { display: grid; grid-template-columns: 1fr 180px 180px; gap: var(--space-3); margin-bottom: var(--space-4); }
.fld { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.fld > span { font-size: var(--text-xs); color: var(--muted); }
.lines { width: 100%; border-collapse: collapse; margin-bottom: 4px; }
.lines th {
  text-align: left; font-weight: 400; font-size: var(--text-2xs); color: var(--meta);
  padding: 6px 8px; border-bottom: 1px solid var(--border);
}
.lines td { padding: 4px 8px; border-bottom: 1px solid var(--border-soft); vertical-align: middle; }
.lines .r { text-align: right; }
.lines .idx { color: var(--meta); font-size: var(--text-xs); }
.lines-foot { display: flex; align-items: center; gap: var(--space-2); padding: var(--space-2) 4px var(--space-3); }
.lines-foot .spacer { flex: 1; }
.sum { font-size: var(--text-xs); color: var(--muted); }
.sum b { color: var(--fg); font-weight: 600; }
.tip { margin-top: var(--space-2); border-radius: var(--radius-md); }
.w100 { width: 100%; }
.opt-code { font-family: var(--font-mono); color: var(--muted); margin-right: 8px; font-size: var(--text-xs); }
.opt-spec { color: var(--meta); margin-left: 8px; font-size: var(--text-2xs); }
:deep(.inp-r .el-input__inner) { text-align: right; font-family: var(--font-mono); }
</style>
