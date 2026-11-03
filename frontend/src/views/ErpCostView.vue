<template>
  <div class="page">
    <!-- 概览：先回答"这个月花了多少、钱花在哪" -->
    <div class="kpi-row">
      <div class="kpi">
        <div class="kpi-label">{{ t('cost.kpiTotal') }}</div>
        <div class="kpi-value num">{{ money(ov.totalCost) }}</div>
        <div class="kpi-sub">{{ t('cost.kpiOrders', { n: ov.orders }) }}<span class="dot">·</span>{{ qty(ov.outputQty, 0) }} {{ t('cost.pcs') }}</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">{{ t('cost.kpiUnit') }}</div>
        <div class="kpi-value num">{{ ov.unitCost == null ? '--' : money(ov.unitCost) }}</div>
        <div class="kpi-sub">{{ t('cost.kpiUnitHint') }}</div>
      </div>
      <div class="kpi kpi-wide">
        <div class="kpi-label">{{ t('cost.kpiMix') }}</div>
        <div class="mix">
          <div class="mix-bar">
            <span class="seg seg-mat" :style="{ width: pctW(ov.materialPct) }" />
            <span class="seg seg-lab" :style="{ width: pctW(ov.laborPct) }" />
            <span class="seg seg-oh" :style="{ width: pctW(ov.overheadPct) }" />
          </div>
          <div class="mix-legend">
            <span><i class="dotc seg-mat" />{{ t('cost.material') }} <b class="num">{{ money(ov.materialCost) }}</b> <em>{{ ov.materialPct == null ? '--' : ov.materialPct + '%' }}</em></span>
            <span><i class="dotc seg-lab" />{{ t('cost.labor') }} <b class="num">{{ ov.laborCost ? money(ov.laborCost) : '--' }}</b> <em>{{ ov.laborPct == null ? '--' : ov.laborPct + '%' }}</em></span>
            <span><i class="dotc seg-oh" />{{ t('cost.overhead') }} <b class="num">{{ money(ov.overheadCost) }}</b> <em>{{ ov.overheadPct == null ? '--' : ov.overheadPct + '%' }}</em></span>
          </div>
        </div>
      </div>
    </div>

    <el-alert v-if="ov.partial" type="warning" :closable="false" show-icon class="mb12"
              :title="t('cost.partialWarn')" :description="ov.note" />

    <el-card shadow="never" class="card">
      <div class="tab-toolbar">
        <el-date-picker
          v-model="range"
          type="daterange"
          :range-separator="t('cost.to')"
          :start-placeholder="t('cost.start')"
          :end-placeholder="t('cost.end')"
          value-format="YYYY-MM-DD"
          class="w260"
          @change="load"
        />
        <el-radio-group v-model="quick" size="small" @change="onQuick">
          <el-radio-button value="month">{{ t('cost.thisMonth') }}</el-radio-button>
          <el-radio-button value="last">{{ t('cost.lastMonth') }}</el-radio-button>
          <el-radio-button value="90">{{ t('cost.last90') }}</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('base.refresh') }}</el-button>
        <div class="grow" />
        <span class="hint-inline">{{ t('cost.periodHint') }}</span>
      </div>

      <div v-if="rows.length" class="open-tip" v-show="data.openCount">
        {{ t('cost.openTip', { n: data.openCount, amt: money(data.openCost) }) }}
      </div>

      <el-table :data="rows" stripe v-loading="loading" :empty-text="t('cost.empty')">
        <el-table-column :label="t('cost.colProduct')" min-width="200">
          <template #default="{ row }">
            <div class="cell-main">{{ row.name }}</div>
            <div class="cell-sub num">{{ row.code }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('cost.colOrders')" width="76" align="right">
          <template #default="{ row }"><span class="num">{{ row.orders }}</span></template>
        </el-table-column>
        <el-table-column :label="t('cost.colQty')" width="100" align="right">
          <template #default="{ row }">
            <span class="num">{{ qty(row.qty, 0) }}</span>
            <span class="unit">{{ row.unit }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('cost.colMaterialUnit')" width="112" align="right">
          <template #default="{ row }">
            <span class="num">{{ row.materialUnit == null ? '--' : money(row.materialUnit) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('cost.colLaborUnit')" width="112" align="right">
          <template #default="{ row }">
            <span class="num" :class="{ muted: row.laborUnit == null }">{{ row.laborUnit == null ? '--' : money(row.laborUnit) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('cost.colOhUnit')" width="112" align="right">
          <template #default="{ row }">
            <span class="num">{{ row.overheadUnit == null ? '--' : money(row.overheadUnit) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('cost.colUnitCost')" width="118" align="right">
          <template #default="{ row }">
            <span class="num strong">{{ row.unitCost == null ? '--' : money(row.unitCost) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('cost.colSale')" width="106" align="right">
          <template #default="{ row }">
            <span class="num" :class="{ muted: row.salePrice == null }">{{ row.salePrice == null ? '--' : money(row.salePrice) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('cost.colGross')" width="120" align="right">
          <template #default="{ row }">
            <span v-if="row.grossProfit == null" class="muted">--</span>
            <span v-else class="num" :class="row.grossProfit >= 0 ? 'up' : 'down'">{{ money(row.grossProfit) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('cost.colGrossPct')" width="106" align="right">
          <template #default="{ row }">
            <span v-if="row.grossPct == null" class="muted">--</span>
            <span v-else class="num" :class="row.grossPct >= 0 ? 'up' : 'down'">{{ row.grossPct }}%</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('cost.colTotal')" width="120" align="right">
          <template #default="{ row }"><span class="num">{{ money(row.totalCost) }}</span></template>
        </el-table-column>
      </el-table>

      <p class="foot-note">{{ data.note }}</p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { costApi, type CostOverview, type ProductCost, type ProductCostResult } from '../api/cost'
import { money, qty } from '../utils/format'

const { t } = useI18n()

const loading = ref(false)
const rows = ref<ProductCost[]>([])
const data = ref<ProductCostResult>({ ok: true, from: '', to: '', rows: [], total: 0, openCost: 0, openCount: 0, note: '' })
const ov = ref<CostOverview>({
  ok: true, from: '', to: '', orders: 0, outputQty: 0,
  materialCost: 0, laborCost: 0, overheadCost: 0, totalCost: 0, unitCost: null,
  materialPct: null, laborPct: null, overheadPct: null, partial: false, note: ''
})

const range = ref<[string, string] | null>(null)
const quick = ref<'month' | 'last' | '90'>('month')

function pad(n: number) {
  return String(n).padStart(2, '0')
}
function fmt(d: Date) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 快捷区间：月末那天要含进去，所以结束日就是月末当天（后端会 +1 天转半开区间） */
function onQuick() {
  const now = new Date()
  if (quick.value === 'month') {
    range.value = [fmt(new Date(now.getFullYear(), now.getMonth(), 1)), fmt(now)]
  } else if (quick.value === 'last') {
    const first = new Date(now.getFullYear(), now.getMonth() - 1, 1)
    const last = new Date(now.getFullYear(), now.getMonth(), 0)
    range.value = [fmt(first), fmt(last)]
  } else {
    const from = new Date(now.getTime() - 89 * 86400000)
    range.value = [fmt(from), fmt(now)]
  }
  load()
}

function load() {
  onLoad()
}

async function onLoad() {
  loading.value = true
  try {
    const [f, to] = range.value || []
    const [a, b] = await Promise.all([costApi.overview(f, to), costApi.products(f, to)])
    if (!a.ok) {
      ElMessage.error((a as unknown as { message?: string }).message || t('common.reqFail'))
      return
    }
    ov.value = a
    if (b.ok) {
      data.value = b
      rows.value = b.rows || []
    }
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  } finally {
    loading.value = false
  }
}

const pctW = (v: number | null) => `${Math.max(0, Math.min(100, v || 0))}%`

onMounted(() => {
  onQuick()
})
</script>

<style scoped>
.kpi-row {
  display: grid;
  grid-template-columns: 220px 220px minmax(0, 1fr);
  gap: 10px;
  margin-bottom: 12px;
}
.kpi {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--surface-2);
  padding: 10px 14px;
}
.kpi-label {
  font-size: 12px;
  color: var(--muted);
}
.kpi-value {
  margin-top: 2px;
  font-size: 22px;
  font-weight: 600;
  color: var(--fg);
  line-height: 1.25;
}
.kpi-sub {
  margin-top: 2px;
  font-size: 11px;
  color: var(--muted);
}
.kpi-sub .dot {
  margin: 0 5px;
}
.mix {
  margin-top: 6px;
}
.mix-bar {
  display: flex;
  height: 10px;
  border-radius: 5px;
  overflow: hidden;
  background: var(--surface-3);
}
.seg {
  display: block;
  height: 100%;
  transition: width 0.3s;
}
.seg-mat { background: var(--accent); }
.seg-lab { background: var(--run); }
.seg-oh { background: var(--idle); }
.mix-legend {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
  margin-top: 7px;
  font-size: 12px;
  color: var(--fg-2);
}
.mix-legend b {
  color: var(--fg);
  margin: 0 3px;
}
.mix-legend em {
  font-style: normal;
  color: var(--muted);
}
.dotc {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 2px;
  margin-right: 5px;
  vertical-align: middle;
}
.card {
  border-radius: var(--radius-md);
}
.tab-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.grow { flex: 1; }
.w260 { width: 260px; }
.mb12 { margin-bottom: 12px; }
.cell-main {
  font-size: 13px;
  color: var(--fg);
}
.cell-sub {
  font-size: 11px;
  color: var(--muted);
}
.unit {
  margin-left: 3px;
  font-size: 11px;
  color: var(--muted);
}
.num { font-family: var(--font-mono); }
.strong { font-weight: 600; }
.muted { color: var(--muted); }
.up { color: var(--danger); }
.down { color: var(--success); }
.open-tip {
  margin-bottom: 10px;
  padding: 7px 12px;
  border-radius: var(--radius-sm);
  background: var(--idle-soft);
  color: var(--idle);
  font-size: 12px;
}
.foot-note {
  margin: 10px 2px 0;
  font-size: 12px;
  color: var(--muted);
  line-height: 1.6;
}
.hint-inline {
  font-size: 12px;
  color: var(--muted);
}
</style>
