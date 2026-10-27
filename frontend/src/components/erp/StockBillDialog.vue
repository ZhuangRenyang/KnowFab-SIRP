<template>
  <el-dialog
    v-model="open"
    :title="'新建' + typeLabel"
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
        <span>{{ isTransfer ? '源仓库（出）' : '仓库' }}</span>
        <el-select v-model="srcWhId" v-if="isTransfer" placeholder="选择源仓库" filterable>
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" :disabled="w.id === whId" />
        </el-select>
        <el-select v-else v-model="whId" placeholder="选择仓库" filterable>
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
      </label>

      <label v-if="isTransfer" class="fld">
        <span>目标仓库（入）</span>
        <el-select v-model="whId" placeholder="选择目标仓库" filterable>
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" :disabled="w.id === srcWhId" />
        </el-select>
      </label>

      <label v-if="needPartner" class="fld">
        <span>{{ type === 'PO_IN' ? '供应商' : '客户' }}</span>
        <el-select v-model="partnerId" placeholder="可不选" clearable filterable>
          <el-option v-for="p in partners" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
      </label>

      <label class="fld">
        <span>备注</span>
        <el-input v-model="remark" placeholder="选填，如送货单号 / 用途" />
      </label>
    </div>

    <table class="lines">
      <thead>
        <tr>
          <th style="width:36px">#</th>
          <th>商品</th>
          <th style="width:120px">数量</th>
          <th style="width:130px">单价（元）</th>
          <th style="width:110px" class="r">金额</th>
          <th style="width:44px"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(l, i) in lines" :key="i">
          <td class="idx num">{{ i + 1 }}</td>
          <td>
            <el-select v-model="l.skuId" placeholder="选商品" filterable class="sku-sel" @change="onSku(l)">
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
            <el-input v-model="l.unitPrice" inputmode="decimal" placeholder="选填" class="w100 inp-r" />
          </td>
          <td class="r num">{{ l.skuId && Number(l.qty) > 0 ? money(Number(l.qty) * Number(l.unitPrice || 0)) : '--' }}</td>
          <td>
            <el-button text :icon="Delete" :disabled="lines.length <= 1" @click="lines.splice(i, 1)" />
          </td>
        </tr>
      </tbody>
    </table>

    <div class="lines-foot">
      <el-button text type="primary" :icon="Plus" @click="addLine">加一行</el-button>
      <span class="spacer" />
      <span class="sum">共 <b class="num">{{ lines.length }}</b> 行 · 数量 <b class="num">{{ qty(totalQty) }}</b> · 金额 <b class="num">¥{{ money(totalAmount) }}</b></span>
    </div>

    <el-alert
      v-if="isTransfer"
      type="info"
      :closable="false"
      show-icon
      class="tip"
      title="调拨只移动货位，不改变全厂总库存：源仓出多少，目标仓就入多少。"
    />

    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">生成单据</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import { partnerApi, skuApi, stockApi, warehouseApi, type ErpPartner, type ErpSku, type ErpWarehouse } from '@/api/erp'
import { money, qty } from '@/utils/format'

const props = defineProps<{ modelValue: boolean; defaultType?: string }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

const open = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

const TYPE_LABELS: Record<string, string> = {
  PO_IN: '采购入库', SO_OUT: '销售出库', OTHER_IN: '其他入库', OTHER_OUT: '其他出库', TRANSFER: '调拨'
}
const manualTypes = Object.entries(TYPE_LABELS).map(([value, label]) => ({ value, label }))

const type = ref('PO_IN')
const whId = ref<number | null>(null)
const srcWhId = ref<number | null>(null)
const partnerId = ref<number | null>(null)
const remark = ref('')
const saving = ref(false)

const skus = ref<ErpSku[]>([])
const warehouses = ref<ErpWarehouse[]>([])
const partners = ref<ErpPartner[]>([])

interface Line { skuId: number | null; qty: number | string; unitPrice: number | string | null }
const lines = ref<Line[]>([{ skuId: null, qty: 1, unitPrice: null }])

const isTransfer = computed(() => type.value === 'TRANSFER')
const needPartner = computed(() => type.value === 'PO_IN' || type.value === 'SO_OUT')
const typeLabel = computed(() => TYPE_LABELS[type.value] || '单据')
const hint = computed(() => {
  switch (type.value) {
    case 'PO_IN': return '供应商送货进仓，库存增加'
    case 'SO_OUT': return '发货给客户，库存减少（不能超过现有库存）'
    case 'OTHER_IN': return '退料、盘盈、赠品等无采购单的入库'
    case 'OTHER_OUT': return '领用、报废等无销售单的出库'
    default: return '厂内仓库之间移动货位'
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
  type.value = props.defaultType && TYPE_LABELS[props.defaultType] ? props.defaultType : 'PO_IN'
  lines.value = [{ skuId: null, qty: 1, unitPrice: null }]
  remark.value = ''
  partnerId.value = null
  try {
    const [s, w] = await Promise.all([skuApi.list(), warehouseApi.list()])
    skus.value = s.filter((x) => x.enabled)
    warehouses.value = w.filter((x) => x.enabled)
    whId.value = warehouses.value.find((x) => x.default)?.id ?? warehouses.value[0]?.id ?? null
    srcWhId.value = null
  } catch (e: any) {
    ElMessage.error(e?.message || '基础资料加载失败')
  }
  onPartnerScope()
}

function validate(): string | null {
  if (!whId.value) return isTransfer.value ? '请选择目标仓库' : '请选择仓库'
  if (isTransfer.value) {
    if (!srcWhId.value) return '请选择源仓库'
    if (srcWhId.value === whId.value) return '源仓库和目标仓库不能相同'
  }
  const filled = lines.value.filter((l) => l.skuId)
  if (!filled.length) return '至少选一个商品'
  if (filled.some((l) => !(Number(l.qty) > 0))) return '数量必须大于 0'
  const ids = filled.map((l) => l.skuId)
  if (new Set(ids).size !== ids.length) return '同一张单里商品不能重复'
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
      lines: lines.value
        .filter((l) => l.skuId && Number(l.qty) > 0)
        .map((l) => ({
          skuId: l.skuId as number,
          qty: Number(l.qty),
          unitPrice: l.unitPrice === null || l.unitPrice === '' ? null : Number(l.unitPrice)
        }))
    })
    if (!res.ok) {
      ElMessage.error(res.message || '生成失败')
      return
    }
    ElMessage.success(res.message || '单据已生成')
    emit('saved')
    open.value = false
  } catch (e: any) {
    ElMessage.error(e?.message || '生成失败')
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
