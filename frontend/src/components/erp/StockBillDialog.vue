<template>
  <el-dialog
    v-model="open"
    :title="t('erpBillDlg.title', { type: typeLabel })"
    width="820px"
    :close-on-click-modal="false"
    class="bill-dlg"
    @open="onOpen"
  >
    <div class="type-row">
      <el-radio-group v-model="type" @change="onTypeChange">
        <el-radio-button v-for="t in manualTypes" :key="t.value" :value="t.value">{{ t.label }}</el-radio-button>
      </el-radio-group>
      <span class="hint">{{ hint }}</span>
    </div>

    <div class="head-grid">
      <label class="fld">
        <span>{{ isTransfer ? t('erpBillDlg.srcWh') : t('erpBillDlg.wh') }}</span>
        <el-select v-model="srcWhId" v-if="isTransfer" :placeholder="t('erpBillDlg.srcWhPh')" filterable>
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" :disabled="w.id === whId" />
        </el-select>
        <el-select v-else v-model="whId" :placeholder="t('erpBillDlg.whPh')" filterable>
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
      </label>

      <label v-if="isTransfer" class="fld">
        <span>{{ t('erpBillDlg.dstWh') }}</span>
        <el-select v-model="whId" :placeholder="t('erpBillDlg.dstWhPh')" filterable>
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" :disabled="w.id === srcWhId" />
        </el-select>
      </label>

      <label v-if="needPartner" class="fld">
        <span>{{ type === 'PO_IN' ? t('erpBillDlg.supplier') : t('erpBillDlg.customer') }}</span>
        <el-select v-model="partnerId" :placeholder="t('erpBillDlg.partnerPh')" clearable filterable>
          <el-option v-for="p in partners" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
      </label>

      <label class="fld">
        <span>{{ t('erpBillDlg.date') }}</span>
        <el-date-picker v-model="bizDate" type="date" value-format="YYYY-MM-DD" :clearable="false"
          style="width:100%" />
      </label>

      <label class="fld">
        <span>{{ t('erpBillDlg.remark') }}</span>
        <el-input v-model="remark" :placeholder="t('erpBillDlg.remarkPh')" />
      </label>
    </div>

    <table class="lines">
      <thead>
        <tr>
          <th style="width:36px">#</th>
          <th>{{ t('erpBillDlg.thSku') }}</th>
          <th style="width:120px">{{ t('erpBillDlg.thQty') }}</th>
          <th style="width:130px">{{ t('erpBillDlg.thPrice') }}</th>
          <th style="width:110px" class="r">{{ t('erpBillDlg.thAmount') }}</th>
          <th style="width:44px"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(l, i) in lines" :key="i">
          <td class="idx num">{{ i + 1 }}</td>
          <td>
            <el-select v-model="l.skuId" :placeholder="t('erpBillDlg.skuPh')" filterable class="sku-sel" @change="onSku(l)">
              <el-option v-for="s in skus" :key="s.id" :label="`${s.code} · ${s.name}`" :value="s.id">
                <span class="opt-code num">{{ s.code }}</span>
                <span>{{ s.name }}</span>
                <span v-if="s.spec" class="opt-spec">{{ s.spec }}</span>
              </el-option>
            </el-select>
          </td>
          <td>
            <el-input v-model="l.qty" inputmode="decimal" placeholder="0" class="w100 inp-r" />
          </td>
          <td>
            <el-input v-model="l.unitPrice" inputmode="decimal" :placeholder="t('erpBillDlg.optionalShort')" class="w100 inp-r" />
          </td>
          <td class="r num">{{ l.skuId && Number(l.qty) > 0 ? money(Number(l.qty) * Number(l.unitPrice || 0)) : '--' }}</td>
          <td>
            <el-button text :icon="Delete" :disabled="lines.length <= 1" @click="lines.splice(i, 1)" />
          </td>
        </tr>
      </tbody>
    </table>

    <div class="lines-foot">
      <el-button text type="primary" :icon="Plus" @click="addLine">{{ t('erpBillDlg.addLine') }}</el-button>
      <span class="spacer" />
      <span class="sum">{{ t('erpBillDlg.summary', { n: lines.length, q: qty(totalQty), m: money(totalAmount) }) }}</span>
    </div>

    <el-alert
      v-if="isTransfer"
      type="info"
      :closable="false"
      show-icon
      class="tip"
      :title="t('erpBillDlg.transferTip')"
    />

    <template #footer>
      <el-button @click="open = false">{{ t('erpBillDlg.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ t('erpBillDlg.submit') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import { partnerApi, skuApi, stockApi, warehouseApi, type ErpPartner, type ErpSku, type ErpWarehouse } from '@/api/erp'
import { money, qty } from '@/utils/format'

const { t } = useI18n()

const props = defineProps<{ modelValue: boolean; defaultType?: string }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

const open = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

const TYPE_KEY: Record<string, string> = {
  PO_IN: 'erpBillType.poIn', SO_OUT: 'erpBillType.soOut',
  OTHER_IN: 'erpBillType.otherIn', OTHER_OUT: 'erpBillType.otherOut', TRANSFER: 'erpBillType.transfer'
}
/* 值仍是类型码，只有显示走翻译，切语言即时生效 */
const manualTypes = computed(() => Object.keys(TYPE_KEY).map((value) => ({ value, label: t(TYPE_KEY[value]) })))

const type = ref('PO_IN')
const whId = ref<number | null>(null)
const srcWhId = ref<number | null>(null)
const partnerId = ref<number | null>(null)
const remark = ref('')
const bizDate = ref(today())
const saving = ref(false)

const skus = ref<ErpSku[]>([])
const warehouses = ref<ErpWarehouse[]>([])
const partners = ref<ErpPartner[]>([])

interface Line { skuId: number | null; qty: number | string; unitPrice: number | string | null }
const lines = ref<Line[]>([{ skuId: null, qty: 1, unitPrice: null }])

function today(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

const isTransfer = computed(() => type.value === 'TRANSFER')
const needPartner = computed(() => type.value === 'PO_IN' || type.value === 'SO_OUT')
const typeLabel = computed(() => (TYPE_KEY[type.value] ? t(TYPE_KEY[type.value]) : t('erpBillDlg.fallbackType')))
const hint = computed(() => {
  switch (type.value) {
    case 'PO_IN': return t('erpBillDlg.hintPoIn')
    case 'SO_OUT': return t('erpBillDlg.hintSoOut')
    case 'OTHER_IN': return t('erpBillDlg.hintOtherIn')
    case 'OTHER_OUT': return t('erpBillDlg.hintOtherOut')
    default: return t('erpBillDlg.hintTransfer')
  }
})

const totalQty = computed(() => lines.value.reduce((a, l) => a + (l.skuId ? Number(l.qty || 0) : 0), 0))
const totalAmount = computed(() => lines.value.reduce((a, l) => a + (l.skuId ? Number(l.qty || 0) * Number(l.unitPrice || 0) : 0), 0))

function addLine() {
  lines.value.push({ skuId: null, qty: 1, unitPrice: null })
}

function onTypeChange() {
  partnerId.value = null
  onPartnerScope()
}

/** 选商品后带出默认单价：入库用采购价、出库用销售价，可手改 */
function onSku(l: Line) {
  const s = skus.value.find((x) => x.id === l.skuId)
  if (!s) return
  const out = type.value === 'SO_OUT'
  l.unitPrice = out ? (s.salePrice ?? s.purchasePrice ?? null) : (s.purchasePrice ?? s.salePrice ?? null)
}

async function onPartnerScope() {
  if (!needPartner.value) {
    partners.value = []
    return
  }
  try {
    partners.value = await partnerApi.list(undefined, type.value === 'PO_IN' ? 'SUPPLIER' : 'CUSTOMER')
  } catch {
    partners.value = []
  }
}

async function onOpen() {
  type.value = props.defaultType && TYPE_KEY[props.defaultType] ? props.defaultType : 'PO_IN'
  lines.value = [{ skuId: null, qty: 1, unitPrice: null }]
  remark.value = ''
  bizDate.value = today()
  partnerId.value = null
  try {
    const [s, w] = await Promise.all([skuApi.list(), warehouseApi.list()])
    skus.value = s.filter((x) => x.enabled)
    warehouses.value = w.filter((x) => x.enabled)
    whId.value = warehouses.value.find((x) => x.default)?.id ?? warehouses.value[0]?.id ?? null
    srcWhId.value = null
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpBillDlg.baseFailed'))
  }
  onPartnerScope()
}

function validate(): string | null {
  if (!whId.value) return isTransfer.value ? t('erpBillDlg.needDstWh') : t('erpBillDlg.needWh')
  if (isTransfer.value) {
    if (!srcWhId.value) return t('erpBillDlg.needSrcWh')
    if (srcWhId.value === whId.value) return t('erpBillDlg.sameWh')
  }
  const filled = lines.value.filter((l) => l.skuId)
  if (!filled.length) return t('erpBillDlg.needSku')
  if (filled.some((l) => !(Number(l.qty) > 0))) return t('erpBillDlg.needQty')
  const ids = filled.map((l) => l.skuId)
  if (new Set(ids).size !== ids.length) return t('erpBillDlg.dupSku')
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
    const res = await stockApi.createBill({
      type: type.value,
      warehouseId: whId.value as number,
      srcWarehouseId: isTransfer.value ? srcWhId.value : null,
      partnerId: needPartner.value ? partnerId.value : null,
      remark: remark.value,
      date: bizDate.value,
      lines: lines.value
        .filter((l) => l.skuId && Number(l.qty) > 0)
        .map((l) => ({
          skuId: l.skuId as number,
          qty: Number(l.qty),
          unitPrice: l.unitPrice === null || l.unitPrice === '' ? null : Number(l.unitPrice)
        }))
    })
    if (!res.ok) {
      ElMessage.error(res.message || t('erpBillDlg.saveFailed'))
      return
    }
    ElMessage.success(res.message || t('erpBillDlg.saved'))
    emit('saved')
    open.value = false
  } catch (e: any) {
    ElMessage.error(e?.message || t('erpBillDlg.saveFailed'))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.type-row { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; margin-bottom: var(--space-4); }
.hint { font-size: var(--text-xs); color: var(--muted); }
.head-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: var(--space-3); margin-bottom: var(--space-4); }
.fld { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.fld > span { font-size: var(--text-xs); color: var(--muted); }
.lines { width: 100%; border-collapse: collapse; }
.lines th {
  text-align: left; font-weight: 400; font-size: var(--text-2xs); color: var(--meta);
  padding: 6px 8px; border-bottom: 1px solid var(--border);
}
.lines td { padding: 4px 8px; border-bottom: 1px solid var(--border-soft); vertical-align: middle; }
.lines .r { text-align: right; }
.lines .idx { color: var(--meta); font-size: var(--text-xs); }
.lines-foot { display: flex; align-items: center; gap: var(--space-2); padding: var(--space-2) 4px; }
.lines-foot .spacer { flex: 1; }
.sum { font-size: var(--text-xs); color: var(--muted); }
.sum b { color: var(--fg); font-weight: 600; }
.tip { margin-top: var(--space-2); border-radius: var(--radius-md); }
.w100 { width: 100%; }
.sku-sel { width: 100%; }
.opt-code { font-family: var(--font-mono); color: var(--muted); margin-right: 8px; font-size: var(--text-xs); }
.opt-spec { color: var(--meta); margin-left: 8px; font-size: var(--text-2xs); }
:deep(.inp-r .el-input__inner) { text-align: right; font-family: var(--font-mono); }
</style>
