<template>
  <el-dialog
    v-model="open"
    :title="isIn ? t('erpPay.titleIn') : t('erpPay.titleOut')"
    width="620px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <div class="grid">
      <label class="fld">
        <span>{{ t('erpPay.type') }}</span>
        <el-radio-group v-model="direction" @change="onDirectionChange">
          <el-radio-button value="IN">{{ t('erpPay.in') }}</el-radio-button>
          <el-radio-button value="OUT">{{ t('erpPay.out') }}</el-radio-button>
        </el-radio-group>
      </label>
      <label class="fld">
        <span>{{ isIn ? t('erpPay.customer') : t('erpPay.supplier') }}</span>
        <el-select v-model="partnerId" :placeholder="t('erpPay.partnerPh')" filterable @change="onPartnerChange">
          <el-option v-for="p in partners" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
      </label>
      <label class="fld span2">
        <span>{{ t('erpPay.orderLabel', { pre: isIn ? t('erpPay.preIn') : t('erpPay.preOut') }) }}</span>
        <el-select v-model="orderNo" :placeholder="t('erpPay.orderPh')" clearable filterable @change="onOrderChange">
          <el-option
            v-for="o in orders"
            :key="o.orderNo"
            :label="t('erpPay.orderOption', { no: o.orderNo, verb: isIn ? t('erpPay.verbIn') : t('erpPay.verbOut'), amount: money(o.outstanding) })"
            :value="o.orderNo"
          />
        </el-select>
      </label>
      <label class="fld">
        <span>{{ t('erpPay.amount') }}</span>
        <el-input v-model="amount" inputmode="decimal" class="inp-r" placeholder="0.00" />
      </label>
      <label class="fld">
        <span>{{ t('erpPay.methodLabel', { verb: isIn ? t('erpPay.in') : t('erpPay.out') }) }}</span>
        <el-select v-model="method">
          <el-option v-for="m in methods" :key="m" :label="methodText(m)" :value="m" />
        </el-select>
      </label>
      <label class="fld">
        <span>{{ t('erpPay.date') }}</span>
        <el-date-picker v-model="payDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
      </label>
      <label class="fld">
        <span>{{ t('erpPay.remark') }}</span>
        <el-input v-model="remark" :placeholder="t('erpPay.remarkPh')" />
      </label>
    </div>

    <div v-if="orderNo && current" class="hint">
      {{ t('erpPay.summaryBilled', { kind: isIn ? t('erpPay.kindIn') : t('erpPay.kindOut'), v: money(current.billedAmount) }) }}
      {{ t('erpPay.summaryPaid', { verb: isIn ? t('erpPay.verbIn') : t('erpPay.verbOut'), v: money(current.paidAmount) }) }}
      {{ t('erpPay.summaryLeft', { v: money(current.outstanding) }) }}
    </div>
    <el-alert
      v-else
      type="info"
      :closable="false"
      show-icon
      class="tip"
      :title="isIn ? t('erpPay.tipIn') : t('erpPay.tipOut')"
    />

    <template #footer>
      <el-button @click="open = false">{{ t('erpPay.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ isIn ? t('erpPay.submitIn') : t('erpPay.submitOut') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { partnerApi, type ErpPartner } from '@/api/erp'
import { financeApi, type FinanceRow } from '@/api/finance'
import { money } from '@/utils/format'

const { t } = useI18n()

/* 付款方式要落库，值保持中文原样；只把显示翻译掉，历史数据才不会被切成两半 */
const METHOD_KEY: Record<string, string> = {
  '银行转账': 'erpPay.mBank',
  '现金': 'erpPay.mCash',
  '承兑汇票': 'erpPay.mAcceptance',
  '微信': 'erpPay.mWechat',
  '支付宝': 'erpPay.mAlipay',
  '其他': 'erpPay.mOther'
}
const methodText = (m: string) => (METHOD_KEY[m] ? t(METHOD_KEY[m]) : m)

const props = defineProps<{
  modelValue: boolean
  defaultDirection?: 'IN' | 'OUT'
  defaultPartnerId?: number | null
  defaultOrderNo?: string | null
}>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

const open = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

const direction = ref<'IN' | 'OUT'>('IN')
const isIn = computed(() => direction.value === 'IN')
const partners = ref<ErpPartner[]>([])
const orders = ref<FinanceRow[]>([])
const partnerId = ref<number | null>(null)
const orderNo = ref('')
const amount = ref<number | string>('')
const method = ref('银行转账')
const payDate = ref('')
const remark = ref('')
const saving = ref(false)
const methods = ref<string[]>(['银行转账', '现金', '承兑汇票', '微信', '支付宝', '其他'])

const current = computed(() => orders.value.find((o) => o.orderNo === orderNo.value) || null)

const today = () => {
  const d = new Date(), p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

async function onOpen() {
  direction.value = props.defaultDirection || 'IN'
  partnerId.value = props.defaultPartnerId ?? null
  orderNo.value = props.defaultOrderNo || ''
  amount.value = ''
  remark.value = ''
  payDate.value = today()
  await loadPartners()
  if (orderNo.value && partnerId.value) await loadOrders()
}

async function loadPartners() {
  try {
    partners.value = await partnerApi.list(undefined, isIn.value ? 'CUSTOMER' : 'SUPPLIER')
  } catch {
    partners.value = []
  }
}

/** 拉了该单位未收/未付的订单，供选择核销 */
async function loadOrders() {
  orders.value = []
  if (!partnerId.value) return
  try {
    const r = isIn.value
      ? await financeApi.receivables({ partnerId: partnerId.value, onlyOpen: true, size: 100 })
      : await financeApi.payables({ partnerId: partnerId.value, onlyOpen: true, size: 100 })
    orders.value = r.rows || []
    if (orderNo.value) {
      const hit = orders.value.find((o) => o.orderNo === orderNo.value)
      if (hit) amount.value = Number(hit.outstanding)
    }
  } catch {
    orders.value = []
  }
}

function onDirectionChange() {
  partnerId.value = null
  orderNo.value = ''
  orders.value = []
  amount.value = ''
  loadPartners()
}

async function onPartnerChange() {
  orderNo.value = ''
  amount.value = ''
  await loadOrders()
}

function onOrderChange() {
  amount.value = current.value ? Number(current.value.outstanding) : ''
}

async function submit() {
  if (!partnerId.value) {
    ElMessage.warning(isIn.value ? t('erpPay.needCustomer') : t('erpPay.needSupplier'))
    return
  }
  const amt = Number(amount.value)
  if (!(amt > 0)) {
    ElMessage.warning(t('erpPay.needAmount'))
    return
  }
  saving.value = true
  try {
    const res = await financeApi.createPayment({
      direction: direction.value,
      partnerId: partnerId.value,
      orderNo: orderNo.value || null,
      amount: amt,
      method: method.value,
      payDate: payDate.value,
      remark: remark.value
    })
    if (!res.ok) {
      ElMessage.error(res.message || t('erpPay.failed'))
      return
    }
    ElMessage.success(res.message || t('erpPay.done'))
    emit('saved')
    open.value = false
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpPay.failed'))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); }
.fld { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.fld > span { font-size: var(--text-xs); color: var(--muted); }
.span2 { grid-column: span 2; }
.inp-r :deep(.el-input__inner) { text-align: right; font-family: var(--font-mono); }
.hint {
  margin-top: var(--space-3); padding: var(--space-2) var(--space-3);
  background: var(--surface-2); border-radius: var(--radius-md);
  font-size: var(--text-xs); color: var(--muted);
}
.hint b { color: var(--fg); }
.tip { margin-top: var(--space-3); border-radius: var(--radius-md); }
</style>
