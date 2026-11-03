<template>
  <div class="bom-wrap">
    <!-- 左侧：产品清单 -->
    <aside class="bom-side">
      <el-input
        v-model="pq"
        :placeholder="t('bom.searchProduct')"
        clearable
        :prefix-icon="Search"
        class="mb8"
      />
      <div class="bom-side-list" v-loading="pLoading">
        <button
          v-for="p in filteredProducts"
          :key="p.id"
          class="bom-side-item"
          :class="{ on: p.id === selectedId }"
          type="button"
          @click="select(p.id)"
        >
          <span class="code">{{ p.code }}</span>
          <span class="name">{{ p.name }}</span>
          <span class="badge" :class="{ empty: !p.materialCount }">
            {{ p.materialCount ? t('bom.nMaterials', { n: p.materialCount }) : t('bom.notSet') }}
          </span>
        </button>
        <el-empty v-if="!filteredProducts.length && !pLoading" :description="t('bom.noProduct')" :image-size="60" />
      </div>
    </aside>

    <!-- 右侧：用料明细 -->
    <section class="bom-main" v-loading="loading">
      <template v-if="selectedId">
        <header class="bom-head">
          <div>
            <div class="bom-title">
              {{ detail?.productName }}
              <span class="code">{{ detail?.productCode }}</span>
            </div>
            <div class="bom-sub">
              <template v-if="detail?.hasBom">
                {{ t('bom.materialCount', { n: rows.length }) }}
                <span class="dot">·</span>
                {{ t('bom.unitCost') }}
                <strong v-if="detail.materialCost != null" class="num">{{ money(detail.materialCost) }}</strong>
                <span v-else class="muted">{{ t('bom.noPrice') }}</span>
              </template>
              <template v-else>{{ t('bom.notConfigured') }}</template>
            </div>
          </div>
          <div class="bom-actions">
            <el-button v-if="auth.canWrite" :icon="Plus" @click="addRow">{{ t('bom.addMaterial') }}</el-button>
            <el-button v-if="auth.canWrite" type="primary" :loading="saving" :disabled="!dirty" @click="save">
              {{ t('bom.save') }}
            </el-button>
          </div>
        </header>

        <el-table :data="rows" stripe class="bom-table" :empty-text="t('bom.emptyRows')">
          <el-table-column :label="t('bom.colMaterial')" min-width="230">
            <template #default="{ row, $index }">
              <el-select
                v-if="row._new || auth.canWrite"
                v-model="row.materialSkuId"
                filterable
                clearable
                :placeholder="t('bom.pickMaterial')"
                class="w-full"
                @change="(v: number) => onMaterialChange(row, v)"
              >
                <el-option
                  v-for="s in materialOptions(row.materialSkuId)"
                  :key="s.id"
                  :value="s.id"
                  :label="`${s.code} ${s.name}`"
                >
                  <span class="opt-code">{{ s.code }}</span>
                  <span class="opt-name">{{ s.name }}</span>
                  <span class="opt-unit">{{ s.unit }}</span>
                </el-option>
              </el-select>
              <span v-else>
                <span class="num">{{ row.materialCode }}</span>
                {{ row.materialName }}
              </span>
            </template>
          </el-table-column>

          <el-table-column :label="t('bom.colSpec')" min-width="110">
            <template #default="{ row }">{{ row.spec || '--' }}</template>
          </el-table-column>

          <el-table-column :label="t('bom.colQty')" width="140" align="right">
            <template #default="{ row }">
              <el-input-number
                v-if="auth.canWrite"
                v-model="row.qty"
                :min="0.0001"
                :precision="4"
                :step="1"
                controls-position="right"
                size="small"
                class="w-full"
                @change="markDirty"
              />
              <span v-else class="num">{{ qty(row.qty, 4) }}</span>
            </template>
          </el-table-column>

          <el-table-column :label="t('bom.colUnit')" width="70">
            <template #default="{ row }">{{ row.unit || '--' }}</template>
          </el-table-column>

          <el-table-column :label="t('bom.colLoss')" width="120" align="right">
            <template #default="{ row }">
              <el-input-number
                v-if="auth.canWrite"
                v-model="row.lossRate"
                :min="0"
                :max="100"
                :precision="2"
                :step="1"
                controls-position="right"
                size="small"
                class="w-full"
                @change="markDirty"
              />
              <span v-else class="num">{{ qty(row.lossRate, 2) }}%</span>
            </template>
          </el-table-column>

          <el-table-column :label="t('bom.colPrice')" width="110" align="right">
            <template #default="{ row }">
              <span class="num" v-if="row.purchasePrice != null">{{ money(row.purchasePrice) }}</span>
              <span class="muted" v-else>--</span>
            </template>
          </el-table-column>

          <el-table-column :label="t('bom.colLineCost')" width="120" align="right">
            <template #default="{ row }">
              <span class="num strong" v-if="row.purchasePrice != null">{{ money(lineCost(row)) }}</span>
              <span class="muted" v-else>--</span>
            </template>
          </el-table-column>

          <el-table-column :label="t('base.remark')" min-width="140">
            <template #default="{ row }">
              <el-input
                v-if="auth.canWrite"
                v-model="row.remark"
                size="small"
                :placeholder="t('bom.remarkPh')"
                @input="markDirty"
              />
              <span v-else>{{ row.remark || '--' }}</span>
            </template>
          </el-table-column>

          <el-table-column v-if="auth.canWrite" :label="t('base.action')" width="80" align="right">
            <template #default="{ $index }">
              <el-button size="small" type="danger" plain @click="removeRow($index)">{{ t('base.del') }}</el-button>
            </template>
          </el-table-column>
        </el-table>

        <p class="bom-tip">{{ t('bom.tip') }}</p>
      </template>
      <el-empty v-else :description="t('bom.pickHint')" />
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import { bomApi, type BomProduct, type BomRow } from '../../api/bom'
import { skuApi, type ErpSku } from '../../api/erp'
import { useAuthStore } from '../../stores/auth'
import { money, qty } from '../../utils/format'

const { t } = useI18n()
const auth = useAuthStore()

const products = ref<BomProduct[]>([])
const skus = ref<ErpSku[]>([])
const pLoading = ref(false)
const loading = ref(false)
const saving = ref(false)
const dirty = ref(false)

const pq = ref('')
const selectedId = ref<number | null>(null)
const detail = ref<Awaited<ReturnType<typeof bomApi.detail>> | null>(null)
/** 表格里的行就是「提交上来的最终样子」：保存时整表提交 */
const rows = ref<(Partial<BomRow> & { _new?: boolean })[]>([])

const filteredProducts = computed(() => {
  const k = pq.value.trim().toLowerCase()
  if (!k) return products.value
  return products.value.filter(
    (p) => p.code.toLowerCase().includes(k) || p.name.toLowerCase().includes(k)
  )
})

/** 下拉候选：排除产品自己，已选过的也排除（同一材料不能出现两次） */
function materialOptions(current?: number | null) {
  const used = new Set(rows.value.map((r) => r.materialSkuId).filter((x): x is number => !!x))
  return skus.value.filter(
    (s) => s.id !== selectedId.value && (!used.has(s.id) || s.id === current)
  )
}

function lineCost(r: Partial<BomRow>) {
  if (r.purchasePrice == null || r.qty == null) return null
  const loss = (r.lossRate || 0) / 100
  return r.qty * (1 + loss) * r.purchasePrice
}

function markDirty() {
  dirty.value = true
}

function onMaterialChange(row: Partial<BomRow> & { _new?: boolean }, id: number) {
  const s = skus.value.find((x) => x.id === id)
  row.materialCode = s?.code ?? null
  row.materialName = s?.name ?? ''
  row.unit = s?.unit ?? null
  row.spec = s?.spec ?? null
  row.purchasePrice = s?.purchasePrice ?? null
  if (!row.qty) row.qty = 1
  if (row.lossRate == null) row.lossRate = 0
  markDirty()
}

function addRow() {
  rows.value.push({
    materialSkuId: undefined as unknown as number,
    qty: 1,
    lossRate: 0,
    remark: null,
    purchasePrice: null,
    _new: true
  })
  markDirty()
}

function removeRow(i: number) {
  rows.value.splice(i, 1)
  markDirty()
}

async function loadProducts() {
  pLoading.value = true
  try {
    products.value = await bomApi.products()
  } catch {
    products.value = []
  } finally {
    pLoading.value = false
  }
}

async function loadSkus() {
  try {
    skus.value = await skuApi.list()
  } catch {
    skus.value = []
  }
}

async function select(id: number) {
  if (dirty.value && !window.confirm(t('bom.unsaved'))) return
  selectedId.value = id
  await loadDetail()
}

async function loadDetail() {
  if (!selectedId.value) return
  loading.value = true
  try {
    const d = await bomApi.detail(selectedId.value)
    if (!d.ok) {
      ElMessage.error((d as unknown as { message?: string }).message || t('common.reqFail'))
      return
    }
    detail.value = d
    rows.value = (d.rows || []).map((r) => ({ ...r }))
    dirty.value = false
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!selectedId.value) return
  const invalid = rows.value.find((r) => !r.materialSkuId || !r.qty || r.qty <= 0)
  if (invalid) {
    ElMessage.warning(t('bom.invalid'))
    return
  }
  saving.value = true
  try {
    const res = await bomApi.save(
      selectedId.value,
      rows.value.map((r) => ({
        materialSkuId: r.materialSkuId as number,
        qty: r.qty as number,
        lossRate: r.lossRate || 0,
        remark: r.remark || null
      }))
    )
    if (!res.ok) {
      ElMessage.error(res.message || t('common.reqFail'))
      return
    }
    ElMessage.success(res.message || t('bom.saved'))
    dirty.value = false
    await Promise.all([loadDetail(), loadProducts()])
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadProducts(), loadSkus()])
  if (products.value.length) await select(products.value[0].id)
})
</script>

<style scoped>
.bom-wrap {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}
.bom-side {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--surface-2);
  padding: 10px;
  max-height: calc(100vh - 250px);
  display: flex;
  flex-direction: column;
}
.bom-side-list {
  overflow-y: auto;
  flex: 1;
  min-height: 120px;
}
.bom-side-item {
  display: block;
  width: 100%;
  text-align: left;
  border: 1px solid transparent;
  background: transparent;
  border-radius: var(--radius-sm);
  padding: 7px 9px;
  cursor: pointer;
  line-height: 1.35;
  transition: background 0.15s, border-color 0.15s;
}
.bom-side-item:hover {
  background: var(--surface-3);
}
.bom-side-item.on {
  background: var(--accent-soft);
  border-color: var(--accent);
}
.bom-side-item .code {
  display: block;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--muted);
}
.bom-side-item .name {
  display: block;
  font-size: 13px;
  color: var(--fg);
}
.bom-side-item .badge {
  display: inline-block;
  margin-top: 3px;
  font-size: 11px;
  color: var(--accent);
}
.bom-side-item .badge.empty {
  color: var(--muted);
}
.bom-main {
  min-width: 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--surface-2);
  padding: 12px 14px 4px;
}
.bom-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.bom-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--fg);
}
.bom-title .code {
  margin-left: 8px;
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 400;
  color: var(--muted);
}
.bom-sub {
  margin-top: 3px;
  font-size: 12px;
  color: var(--fg-2);
}
.bom-sub .dot {
  margin: 0 5px;
  color: var(--muted);
}
.bom-sub .muted {
  color: var(--muted);
}
.bom-actions {
  display: flex;
  gap: 8px;
}
.bom-table {
  margin-top: 4px;
}
.bom-tip {
  margin: 8px 2px 10px;
  font-size: 12px;
  color: var(--muted);
  line-height: 1.6;
}
.opt-code {
  display: inline-block;
  min-width: 78px;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--muted);
}
.opt-unit {
  margin-left: 8px;
  font-size: 12px;
  color: var(--muted);
}
.w-full {
  width: 100%;
}
.mb8 {
  margin-bottom: 8px;
}
.strong {
  font-weight: 600;
  color: var(--fg);
}
.muted {
  color: var(--muted);
}
</style>
