<template>
  <el-dialog
    v-model="open"
    :title="isIn ? '登记收款' : '登记付款'"
    width="620px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <div class="grid">
      <label class="fld">
        <span>类型</span>
        <el-radio-group v-model="direction" @change="onDirectionChange">
          <el-radio-button value="IN">收款</el-radio-button>
          <el-radio-button value="OUT">付款</el-radio-button>
        </el-radio-group>
      </label>
      <label class="fld">
        <span>{{ isIn ? '客户' : '供应商' }}</span>
        <el-select v-model="partnerId" placeholder="选择往来单位" filterable @change="onPartnerChange">
          <el-option v-for="p in partners" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
      </label>
      <label class="fld span2">
        <span>关联订单（不选 = {{ isIn ? '预收' : '预付' }}）</span>
        <el-select v-model="orderNo" placeholder="不关联订单" clearable filterable @change="onOrderChange">
          <el-option
            v-for="o in orders"
            :key="o.orderNo"
            :label="`${o.orderNo} · 未${isIn ? '收' : '付'} ${money(o.outstanding)}`"
            :value="o.orderNo"
          />
        </el-select>
      </label>
      <label class="fld">
        <span>金额（元）</span>
        <el-input v-model="amount" inputmode="decimal" class="inp-r" placeholder="0.00" />
      </label>
      <label class="fld">
        <span>{{ isIn ? '收款' : '付款' }}方式</span>
        <el-select v-model="method">
          <el-option v-for="m in methods" :key="m" :label="m" :value="m" />
        </el-select>
      </label>
      <label class="fld">
        <span>日期</span>
        <el-date-picker v-model="payDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
      </label>
      <label class="fld">
        <span>备注</span>
        <el-input v-model="remark" placeholder="选填，如 转账流水号" />
      </label>
    </div>

    <div v-if="orderNo && current" class="hint">
      该订单{{ isIn ? '应收' : '应付' }} <b class="num">{{ money(current.billedAmount) }}</b>，
      已{{ isIn ? '收' : '付' }} <b class="num">{{ money(current.paidAmount) }}</b>，
      还剩 <b class="num">{{ money(current.outstanding) }}</b>
    </div>
    <el-alert
      v-else
      type="info"
      :closable="false"
      show-icon
      class="tip"
      :title="isIn ? '不关联订单就是预收：客户先打款、我们还没发货，等有单再核销。' : '不关联订单就是预付：先付定金、货还没到，等到货再核销。'"
    />

    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">确认{{ isIn ? '收款' : '付款' }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { partnerApi, type ErpPartner } from '@/api/erp'
import { financeApi, type FinanceRow } from '@/api/finance'
import { money } from '@/utils/format'

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
    ElMessage.warning(isIn.value ? '请选择客户' : '请选择供应商')
    return
  }
  const amt = Number(amount.value)
  if (!(amt > 0)) {
    ElMessage.warning('金额必须大于 0')
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
      ElMessage.error(res.message || '登记失败')
      return
    }
    ElMessage.success(res.message || '已登记')
    emit('saved')
    open.value = false
  } catch (e: any) {
    ElMessage.error(e?.message || '登记失败')
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
