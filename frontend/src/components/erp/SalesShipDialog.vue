<template>
  <el-dialog
    v-model="open"
    :title="isReturn ? t('erpSalesShip.titleReturn', { no: orderNo }) : t('erpSalesShip.titleShip', { no: orderNo })"
    width="800px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <div class="bar">
      <label class="fld">
        <span>{{ t('erpSalesShip.warehouse') }}</span>
        <el-select v-model="whId" :placeholder="t('erpSalesShip.whPh')" filterable style="width:190px" @change="loadStock">
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
      </label>
      <label class="fld grow">
        <span>{{ t('erpSalesShip.remark') }}</span>
        <el-input v-model="remark" :placeholder="isReturn ? t('erpSalesShip.remarkReturnPh') : t('erpSalesShip.remarkPh')" />
      </label>
      <el-button v-if="!isReturn" text type="primary" @click="fillAll">{{ t('erpSalesShip.fillAll') }}</el-button>
    </div>

    <el-alert
      v-if="isReturn"
      type="warning"
      :closable="false"
      show-icon
      class="tip"
      :title="t('erpSalesShip.notice')"
    />

    <!-- 退货原因：必选、无默认值。默认选错会系统性漏掉质量退货 -->
    <div v-if="isReturn" class="reason-row">
      <label class="fld">
        <span>{{ t('erpSalesShip.returnReason') }}</span>
        <el-radio-group v-model="returnReason">
          <el-radio-button value="QUALITY">{{ t('erpSalesShip.returnReasonQuality') }}</el-radio-button>
          <el-radio-button value="OTHER">{{ t('erpSalesShip.returnReasonOther') }}</el-radio-button>
        </el-radio-group>
      </label>
      <p class="reason-hint">
        {{ returnReason === 'QUALITY'
          ? t('erpSalesShip.qualityReturnWarn')
          : (returnReason === 'OTHER' ? t('erpSalesShip.otherReturnNote') : t('erpSalesShip.returnReasonRequired')) }}
      </p>
    </div>

    <!-- 禁发仓放行检查：查不到必须拦，不能放行 -->
    <el-alert
      v-if="!isReturn && blockedHits.length"
      type="error"
      :closable="false"
      show-icon
      class="tip"
      :title="t('erpSalesShip.blockedWhTitle')"
    >
      <ul class="blk-list">
        <li v-for="h in blockedHits" :key="h.skuId">
          <span class="num">{{ h.skuCode }}</span> {{ h.skuName }}
          — {{ h.blockReason === 'NO_ACK'
            ? t('erpSalesShip.blockedNoAck')
            : t('erpSalesShip.blockedNoRelease') }}
          <em>{{ actionHintOf(h.actionHint) }}</em>
        </li>
      </ul>
      <!-- 放行依据的匹配口径：后端给代号（英文界面不能蹦中文） -->
      <p v-if="limitText" class="blk-limit">{{ limitText }}</p>
    </el-alert>
    <el-alert
      v-else-if="!isReturn && checkFailed"
      type="warning"
      :closable="false"
      show-icon
      class="tip"
      :title="t('erpSalesShip.blockedCheckFailed')"
    />
    <el-alert
      v-else-if="shortRows.length"
      type="error"
      :closable="false"
      show-icon
      class="tip"
      :title="t('erpSalesShip.shortWarn', { n: shortRows.length })"
    />

    <el-table :data="rows" v-loading="loading" max-height="380" stripe>
      <el-table-column :label="t('erpSalesShip.colCode')" width="108">
        <template #default="{ row }"><span class="num">{{ row.skuCode }}</span></template>
      </el-table-column>
      <el-table-column :label="t('erpSalesShip.colSku')" min-width="140">
        <template #default="{ row }">
          {{ row.skuName }}<span v-if="row.spec" class="spec">{{ row.spec }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('erpSalesShip.colOrdered')" width="84" align="right">
        <template #default="{ row }"><span class="num dim">{{ qty(row.qty) }}</span></template>
      </el-table-column>
      <el-table-column :label="t('erpSalesShip.colShipped')" width="84" align="right">
        <template #default="{ row }"><span class="num dim">{{ qty(row.shippedQty) }}</span></template>
      </el-table-column>
      <el-table-column :label="isReturn ? t('erpSalesShip.colReturnable') : t('erpSalesShip.colPending')" width="84" align="right">
        <template #default="{ row }">
          <span class="num">{{ qty(isReturn ? row.shippedQty : row.remainQty) }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="!isReturn" :label="t('erpSalesShip.colAvailable')" width="92" align="right">
        <template #default="{ row }">
          <span class="num" :class="Number(row.input) > row.available ? 'minus' : ''">{{ qty(row.available) }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="isReturn ? t('erpSalesShip.colReturnQty') : t('erpSalesShip.colShipQty')" width="128">
        <template #default="{ row }">
          <el-input v-model="row.input" inputmode="decimal" class="w100 inp-r" placeholder="0" />
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!rows.length && !loading" :description="t('erpSalesShip.noRows')" />

    <div class="foot">
      <span>{{ t('erpSalesShip.summary', { n: filledCount, q: qty(sumQty), m: money(sumAmount) }) }}</span>
    </div>

    <template #footer>
      <el-button @click="open = false">{{ t('erpSalesShip.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" :disabled="!filledCount || (!isReturn && shortRows.length > 0)" @click="submit">
        {{ isReturn ? t('erpSalesShip.confirmReturn') : t('erpSalesShip.confirmShip') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { stockApi, warehouseApi, type ErpWarehouse } from '@/api/erp'
import { salesApi } from '@/api/sales'
import { qcDefectApi, type QcConcessionHit } from '@/api/qc'
import { money, qty } from '@/utils/format'

const { t, te } = useI18n()

/** 放行依据的匹配口径代号 */
const releaseLimitCode = ref<string | null>(null)

/**
 * 放行拦截的后续动作提示。
 *
 * 后端给代号（REGISTER_ACK / DISPOSE_FIRST），前端给文案——
 * 后端塞中文则英文界面蹦中文。
 * 未知代号退化成中性文案：不显示码本身，也不白屏。
 */
function actionHintOf(code?: string | null): string {
  if (!code) return ''
  const key = 'qc.release.actionHint.' + code
  return te(key) ? t(key) : ''
}

/** 放行依据的匹配口径 */
const limitText = computed(() => {
  const code = releaseLimitCode.value
  if (!code) return ''
  const key = 'qc.release.limit.' + code
  return te(key) ? t(key) : ''
})

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
/** 退货原因。必选、无默认值 —— 打开时留空，逼操作人当场选 */
const returnReason = ref<'QUALITY' | 'OTHER' | ''>('')
/** 被放行规则拦下的 SKU */
const blockedHits = ref<QcConcessionHit[]>([])
/** 放行检查本身失败（查不到 ≠ 放行） */
const checkFailed = ref(false)

const filled = computed(() => rows.value.filter((r) => Number(r.input) > 0))
const filledCount = computed(() => filled.value.length)
const sumQty = computed(() => filled.value.reduce((a, r) => a + Number(r.input), 0))
const sumAmount = computed(() => filled.value.reduce((a, r) => a + Number(r.input) * Number(r.unitPrice || 0), 0))
/** 发货时库存不够的行（前端先提示，最终以后端校验为准） */
const shortRows = computed(() => (isReturn.value ? [] : rows.value.filter((r) => Number(r.input) > r.available)))

async function onOpen() {
  remark.value = ''
  rows.value = []
  returnReason.value = ''
  blockedHits.value = []
  checkFailed.value = false
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
    await checkRelease()
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpSalesShip.orderFailed'))
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

/**
 * 放行检查：源仓是禁发仓时，逐个 SKU 查有没有放行依据。
 *
 * 查不到 ≠ 放行。检查本身失败时置 checkFailed，让界面提示"可能被拒"，
 * 而不是安静地当作没事——那个方向才是事故。
 */
async function checkRelease() {
  blockedHits.value = []
  checkFailed.value = false
  if (isReturn.value || !whId.value) return
  const skuIds = rows.value.map((r) => r.skuId)
  if (!skuIds.length) return
  try {
    // 只传后端认的 whId / skuIds：放行校验按 SKU 查（禁发仓的货有没有未作废的
    // 让步流水 + 客户同意凭证），跟订单号无关。多传参数后端会忽略，
    // 但会让契约表和实际不一致，改了哪一边都不知道。
    const r = await qcDefectApi.concessionRelease({
      whId: whId.value,
      skuIds
    })
    if (!r || !r.ok) {
      checkFailed.value = true
      return
    }
    blockedHits.value = (r.hits || []).filter((h) => h.blocked)
    releaseLimitCode.value = r.limitCode || null
  } catch {
    checkFailed.value = true
  }
}

function fillAll() {
  rows.value.forEach((r) => {
    r.input = Math.min(Number(r.remainQty), r.available)
  })
}

async function submit() {
  if (!whId.value) {
    ElMessage.warning(t('erpSalesShip.needWh'))
    return
  }
  // 退货原因必选：默认选错会系统性漏掉质量退货
  if (isReturn.value && !returnReason.value) {
    ElMessage.warning(t('erpSalesShip.returnReasonRequired'))
    return
  }
  const lines = filled.value.map((r) => ({
    itemId: r.itemId,
    qty: Number(r.input),
    unitPrice: r.unitPrice ?? null
  }))
  if (!lines.length) {
    ElMessage.warning(t('erpSalesShip.needQty'))
    return
  }
  saving.value = true
  try {
    const body: Record<string, unknown> = { warehouseId: whId.value, remark: remark.value, lines }
    if (isReturn.value) body.returnReason = returnReason.value
    const res = isReturn.value
      ? await salesApi.returnFrom(orderNo.value, body as never)
      : await salesApi.ship(orderNo.value, body as never)
    if (!res.ok) {
      ElMessage.error(res.message || t('erpSalesShip.opFailed'))
      return
    }
    ElMessage.success(res.message || t('erpSalesShip.done'))
    emit('saved')
    open.value = false
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpSalesShip.opFailed'))
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
.reason-row { margin-bottom: var(--space-3); }
.reason-hint { margin: 6px 0 0; font-size: var(--text-xs); color: var(--muted); line-height: 1.6; }
.blk-list { margin: 6px 0 0; padding-left: 18px; font-size: var(--text-xs); line-height: 1.9; }
.blk-list em { font-style: normal; color: var(--accent); margin-left: 6px; }
.foot { display: flex; justify-content: flex-end; padding-top: var(--space-2); font-size: var(--text-xs); color: var(--muted); }
.foot b { color: var(--fg); }
:deep(.inp-r .el-input__inner) { text-align: right; font-family: var(--font-mono); }
</style>
