<template>
  <el-dialog
    v-model="open"
    :title="orderNo ? t('erpPurOrder.titleEdit', { no: orderNo }) : t('erpPurOrder.titleNew')"
    width="860px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <div class="head-grid">
      <label class="fld">
        <span>{{ t('erpPurOrder.supplier') }}</span>
        <el-select v-model="supplierId" :placeholder="t('erpPurOrder.supplierPh')" filterable>
          <el-option v-for="p in suppliers" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
      </label>
      <label class="fld">
        <span>{{ t('erpPurOrder.orderDate') }}</span>
        <el-date-picker v-model="orderDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
      </label>
      <label class="fld">
        <span>{{ t('erpPurOrder.expectDate') }}</span>
        <el-date-picker v-model="expectDate" type="date" value-format="YYYY-MM-DD" style="width:100%" :placeholder="t('erpPurOrder.optional')" />
      </label>
    </div>

    <table class="lines">
      <thead>
        <tr>
          <th style="width:34px">#</th>
          <th>{{ t('erpPurOrder.thSku') }}</th>
          <th style="width:118px">{{ t('erpPurOrder.thQty') }}</th>
          <th style="width:126px">{{ t('erpPurOrder.thPrice') }}</th>
          <th style="width:104px" class="r">{{ t('erpPurOrder.thAmount') }}</th>
          <th style="width:40px"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(l, i) in lines" :key="i">
          <td class="idx num">{{ i + 1 }}</td>
          <td>
            <el-select v-model="l.skuId" :placeholder="t('erpPurOrder.skuPh')" filterable class="w100" @change="onSku(l)">
              <el-option v-for="s in skus" :key="s.id" :label="`${s.code} · ${s.name}`" :value="s.id">
                <span class="opt-code num">{{ s.code }}</span>
                <span>{{ s.name }}</span>
                <span v-if="s.spec" class="opt-spec">{{ s.spec }}</span>
              </el-option>
            </el-select>
          </td>
          <td><el-input v-model="l.qty" inputmode="decimal" class="w100 inp-r" placeholder="0" /></td>
          <td><el-input v-model="l.unitPrice" inputmode="decimal" class="w100 inp-r" :placeholder="t('erpPurOrder.optionalShort')" /></td>
          <td class="r num">{{ Number(l.qty) > 0 ? money(Number(l.qty) * Number(l.unitPrice || 0)) : '--' }}</td>
          <td>
            <el-button text :icon="Delete" :disabled="lines.length <= 1" @click="lines.splice(i, 1)" />
          </td>
        </tr>
      </tbody>
    </table>

    <div class="lines-foot">
      <el-button text type="primary" :icon="Plus" @click="addLine">{{ t('erpPurOrder.addLine') }}</el-button>
      <span class="spacer" />
      <span class="sum">{{ t('erpPurOrder.summary', { n: lines.length, q: qty(totalQty), m: money(totalAmount) }) }}</span>
    </div>

    <el-input v-model="remark" type="textarea" :rows="2" :placeholder="t('erpPurOrder.remarkPh')" />

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="tip"
      :title="t('erpPurOrder.notice')"
    />

    <template #footer>
      <el-button @click="open = false">{{ t('erpPurOrder.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ t('erpPurOrder.saveDraft') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import { partnerApi, skuApi, type ErpPartner, type ErpSku } from '@/api/erp'
import { purchaseApi } from '@/api/purchase'
import { money, qty } from '@/utils/format'

const { t, te } = useI18n()

const props = defineProps<{ modelValue: boolean; orderNo?: string | null }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

const open = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

const orderNo = computed(() => props.orderNo || '')
const suppliers = ref<ErpPartner[]>([])
const skus = ref<ErpSku[]>([])
const supplierId = ref<number | null>(null)
const orderDate = ref('')
const expectDate = ref('')
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
  if (s && l.unitPrice == null) l.unitPrice = s.purchasePrice ?? null
}

const today = () => {
  const d = new Date(), p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

async function onOpen() {
  orderDate.value = today()
  expectDate.value = ''
  remark.value = ''
  supplierId.value = null
  lines.value = [{ skuId: null, qty: 1, unitPrice: null }]
  try {
    const [p, s] = await Promise.all([partnerApi.list(undefined, 'SUPPLIER'), skuApi.list()])
    suppliers.value = p
    skus.value = s.filter((x) => x.enabled)
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpPurOrder.baseFailed'))
  }
  if (orderNo.value) {
    try {
      const d = await purchaseApi.detail(orderNo.value)
      supplierId.value = d.order.supplierId ?? null
      orderDate.value = dayStr(d.order.orderDate)
      expectDate.value = d.order.expectDate ? dayStr(d.order.expectDate) : ''
      remark.value = d.order.remark || ''
      lines.value = d.lines.map((l) => ({
        skuId: l.skuId, qty: Number(l.qty), unitPrice: l.unitPrice ?? null
      }))
    } catch (e: any) {
      ElMessage.error(e?.message || t('erpPurOrder.orderFailed'))
    }
  }
}

function dayStr(ts: number) {
  const d = new Date(ts), p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

function validate(): string | null {
  if (!supplierId.value) return t('erpPurOrder.needSupplier')
  const filled = lines.value.filter((l) => l.skuId)
  if (!filled.length) return t('erpPurOrder.needSku')
  if (filled.some((l) => !(Number(l.qty) > 0))) return t('erpPurOrder.needQty')
  const ids = filled.map((l) => l.skuId)
  if (new Set(ids).size !== ids.length) return t('erpPurOrder.dupSku')
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
      supplierId: supplierId.value as number,
      orderDate: orderDate.value,
      expectDate: expectDate.value || undefined,
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
      ? await purchaseApi.update(orderNo.value, body)
      : await purchaseApi.create(body)
    if (!res.ok) {
      ElMessage.error(res.message || t('erpPurOrder.saveFailed'))
      return
    }
    ElMessage.success(res.message || t('erpPurOrder.saved'))
    emit('saved')
    open.value = false
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpPurOrder.saveFailed'))
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
