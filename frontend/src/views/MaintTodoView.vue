<template>
  <div class="page">
    <div class="head">
      <div class="title-row">
        <h2 class="title">{{ t('maint.todoTitle') }}</h2>
        <el-tag size="small" effect="plain">{{ t('maint.todoCount', { n: rows.length }) }}</el-tag>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('maint.refresh') }}</el-button>
    </div>

    <el-card shadow="never" class="card">
      <el-table :data="rows" v-loading="loading" size="small" row-key="orderNo">
        <el-table-column prop="orderNo" :label="t('maint.colOrder')" width="150" />
        <el-table-column :label="t('maint.colDevice')" min-width="150">
          <template #default="{ row }">
            <div>{{ row.deviceName || row.deviceCode }}</div>
            <div class="sub">{{ row.deviceCode }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('maint.colAlarm')" min-width="220">
          <template #default="{ row }">
            <span v-if="row.alarmCode" class="alarm-code">{{ row.alarmCode }}</span>
            <span class="alarm-text">{{ row.alarmText || t('maint.noAlarmRef') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('maint.colStatus')" width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="row.status === 'IN_PROGRESS' ? 'warning' : 'info'">
              {{ t('maint.st_' + row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('maint.colAssignedAt')" width="160">
          <template #default="{ row }">{{ dt(row.assignedAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('maint.actions')" width="250" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'ANALYZED'"
              size="small" type="primary" @click="start(row)"
            >{{ t('maint.startBtn') }}</el-button>
            <el-button size="small" @click="openParts(row)">{{ t('maint.partsBtn') }}</el-button>
            <el-button size="small" @click="openResolve(row)">{{ t('maint.resolveTitle') }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && !rows.length" :description="t('maint.todoEmpty')">
        <div class="empty-hint">{{ t('maint.todoEmptyHint') }}</div>
      </el-empty>
    </el-card>

    <!-- 结案：只问「下次同类报警能参考」的三个必填项，其余可选 -->
    <el-dialog v-model="dlg" :title="t('maint.resolveTitle')" width="560px" :close-on-click-modal="false">
      <div class="dlg-order">{{ cur?.orderNo }} · {{ cur?.deviceName || cur?.deviceCode }}</div>

      <el-form label-position="top" class="fm">
        <el-form-item :label="t('maint.causeCategory')" required>
          <el-select v-model="form.causeCategory" :placeholder="t('maint.pickCategory')" class="w-full">
            <el-option v-for="c in categories" :key="c.value" :value="c.value" :label="c.text" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('maint.rootCause')" required>
          <el-input v-model="form.rootCause" type="textarea" :rows="2" :placeholder="t('maint.rootCausePh')" />
        </el-form-item>
        <el-form-item :label="t('maint.solution')">
          <el-input v-model="form.solution" type="textarea" :rows="2" :placeholder="t('maint.solutionPh')" />
        </el-form-item>
        <div class="two-col">
          <el-form-item :label="t('maint.parts')">
            <el-input v-model="form.partsReplaced" :placeholder="t('maint.partsPh')" />
          </el-form-item>
          <el-form-item :label="t('maint.downtime')">
            <el-input-number v-model="form.downtimeMin" :min="0" :controls="false" class="w-full" />
          </el-form-item>
        </div>
      </el-form>
      <p class="hint">{{ t('maint.fillHint') }}</p>

      <template #footer>
        <el-button @click="dlg = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="submitResolve">{{ t('maint.resolveBtn') }}</el-button>
      </template>
    </el-dialog>

    <!-- 领用备件：生成维修领料单（MAINT_OUT）并真实扣库存 -->
    <el-dialog v-model="partsDlg" :title="t('maint.partsTitle')" width="660px" :close-on-click-modal="false">
      <div class="parts-hint">{{ t('maint.partsHint') }}</div>

      <div class="parts-wh">
        <span class="lbl">{{ t('maint.partsWh') }}</span>
        <el-select v-model="partsForm.warehouseId" style="width:220px">
          <el-option v-for="w in warehouses" :key="w.id" :label="`${w.code} · ${w.name}`" :value="w.id" />
        </el-select>
      </div>

      <div v-for="(l, i) in partsForm.lines" :key="i" class="parts-line">
        <el-select v-model="l.skuId" filterable :placeholder="t('maint.partsSkuPh')" class="sku-sel">
          <el-option v-for="s in skus" :key="s.id" :label="`${s.code} · ${s.name}`" :value="s.id" />
        </el-select>
        <el-input-number v-model="l.qty" :min="0" :precision="2" :step="1" style="width:150px" />
        <span class="unit">{{ unitOf(l.skuId) }}</span>
        <el-button link type="danger" @click="partsForm.lines.splice(i, 1)">{{ t('common.delete') }}</el-button>
      </div>

      <el-button size="small" @click="addPartsLine">+ {{ t('maint.partsAdd') }}</el-button>

      <div v-if="partsInfo && partsInfo.billCount" class="parts-sum">
        {{ t('maint.partsIssued', { n: partsInfo.billCount, amount: partsInfo.totalAmount }) }}
        <div v-for="l in partsInfo.lines" :key="l.billNo + l.skuId" class="parts-sum-line">
          {{ l.skuCode }} · {{ l.skuName }} × {{ l.qty }}{{ l.unit || '' }}
        </div>
      </div>

      <template #footer>
        <el-button @click="partsDlg = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="partsSaving" @click="submitParts">{{ t('maint.partsSubmit') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { maintApi, type MaintOrder, type MaintParts } from '@/api/maint'
import { skuApi, warehouseApi, type ErpSku, type ErpWarehouse } from '@/api/erp'
import { dt } from '@/utils/format'

const { t } = useI18n()

const rows = ref<MaintOrder[]>([])
const loading = ref(false)

const dlg = ref(false)
const saving = ref(false)
const cur = ref<MaintOrder | null>(null)
const categories = ref<{ value: string; text: string }[]>([])
const form = ref({
  causeCategory: '',
  rootCause: '',
  solution: '',
  partsReplaced: '',
  downtimeMin: null as number | null
})

/** 待办 = 派给我且未完成（ANALYZED / IN_PROGRESS）。后端直接查工单表，不建待办表 */
async function load() {
  loading.value = true
  try {
    rows.value = await maintApi.my()
  } catch (e: unknown) {
    ElMessage.error((e as Error)?.message || t('common.saveFailed'))
  } finally {
    loading.value = false
  }
}

// ---------- 领用备件（维修 ↔ 库存联动）----------

const partsDlg = ref(false)
const partsSaving = ref(false)
const partsInfo = ref<MaintParts | null>(null)
const skus = ref<ErpSku[]>([])
const warehouses = ref<ErpWarehouse[]>([])
const partsForm = ref<{ warehouseId: number | null; lines: { skuId: number; qty: number }[] }>({
  warehouseId: null,
  lines: []
})

function unitOf(skuId: number): string {
  return skus.value.find((s) => s.id === skuId)?.unit || ''
}

function addPartsLine() {
  partsForm.value.lines.push({ skuId: 0, qty: 1 })
}

async function openParts(row: MaintOrder) {
  cur.value = row
  partsDlg.value = true
  partsInfo.value = null
  // 主数据按需拉一次；拉不到不挡操作（下拉为空时用户能看出来，比整页报错好）
  if (!warehouses.value.length) {
    try { warehouses.value = await warehouseApi.list() } catch { /* 忽略 */ }
  }
  if (!skus.value.length) {
    try { skus.value = (await skuApi.list()).filter((s) => s.enabled) } catch { /* 忽略 */ }
  }
  if (partsForm.value.warehouseId == null) {
    // 默认选「原料仓」——备件平时就放那儿；没有就选第一个
    const pref = warehouses.value.find((w) => w.code?.startsWith('WH-03')) || warehouses.value[0]
    partsForm.value.warehouseId = pref ? pref.id : null
  }
  partsForm.value.lines = [{ skuId: 0, qty: 1 }]
  try {
    partsInfo.value = await maintApi.parts(row.orderNo)
  } catch { /* 没领过就当作空 */ }
}

async function submitParts() {
  if (!cur.value) return
  if (partsForm.value.warehouseId == null) {
    ElMessage.warning(t('maint.partsNeedWh'))
    return
  }
  const lines = partsForm.value.lines.filter((l) => l.skuId && l.qty > 0)
  if (!lines.length) {
    ElMessage.warning(t('maint.partsNeedLine'))
    return
  }
  partsSaving.value = true
  try {
    const r = await maintApi.issueParts(cur.value.orderNo, {
      warehouseId: partsForm.value.warehouseId,
      lines: lines.map((l) => ({ skuId: l.skuId, qty: l.qty }))
    })
    if (r.ok) {
      ElMessage.success(r.message || t('maint.partsOk'))
      partsInfo.value = r.parts || null
      partsForm.value.lines = [{ skuId: 0, qty: 1 }]
    } else {
      // 库存不足这类业务失败必须原样显示后端那句话（带「现有 N」才有用）
      ElMessage.error(r.message || t('maint.partsFail'))
    }
  } catch (e: unknown) {
    ElMessage.error((e as Error)?.message || t('maint.partsFail'))
  } finally {
    partsSaving.value = false
  }
}

async function start(row: MaintOrder) {
  const r = await maintApi.start(row.orderNo)
  if (r.ok) {
    ElMessage.success(t('maint.startOk'))
    load()
  } else {
    ElMessage.error(r.message || t('maint.startBtn'))
  }
}

function openResolve(row: MaintOrder) {
  cur.value = row
  form.value = { causeCategory: '', rootCause: '', solution: '', partsReplaced: '', downtimeMin: null }
  dlg.value = true
  if (!categories.value.length) {
    maintApi.categories()
      .then((r) => { categories.value = r.categories || [] })
      .catch(() => { /* 分类拉不到就只影响下拉框，不挡结案 */ })
  }
}

async function submitResolve() {
  if (!form.value.causeCategory) {
    ElMessage.warning(t('maint.needCategory'))
    return
  }
  if (!form.value.rootCause.trim()) {
    ElMessage.warning(t('maint.needCause'))
    return
  }
  saving.value = true
  try {
    const r = await maintApi.resolve(cur.value!.orderNo, { ...form.value })
    if (r.ok) {
      ElMessage.success(t('maint.resolvedOk'))
      dlg.value = false
      load()
    } else {
      ElMessage.error(r.message || t('common.saveFailed'))
    }
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.title {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}
.sub {
  font-size: 12px;
  color: var(--muted);
  margin-top: 2px;
}
.alarm-code {
  font-family: monospace;
  margin-right: 6px;
}
.alarm-text {
  font-size: 12px;
  color: var(--muted);
}
.empty-hint {
  font-size: 12px;
  color: var(--muted);
  margin-top: 6px;
}
.dlg-order {
  font-size: 13px;
  color: var(--muted);
  margin-bottom: 10px;
}
.fm {
  margin-bottom: 0;
}
.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.w-full {
  width: 100%;
}
.hint {
  margin: 0;
  font-size: 12px;
  color: var(--muted);
  line-height: 1.6;
}

/* 领用备件（维修 ↔ 库存联动） */
.parts-hint {
  font-size: var(--text-xs); color: var(--muted); line-height: 1.6;
  margin-bottom: var(--space-3);
}
.parts-wh { display: flex; align-items: center; gap: 10px; margin-bottom: var(--space-3); }
.parts-wh .lbl { font-size: var(--text-xs); color: var(--muted); }
.parts-line { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.parts-line .sku-sel { flex: 1; min-width: 220px; }
.parts-line .unit { width: 34px; font-size: var(--text-xs); color: var(--muted); }
.parts-sum {
  margin-top: var(--space-3); padding: 8px 12px; border-radius: var(--radius-md);
  background: var(--surface-2); font-size: var(--text-xs); color: var(--fg-2);
}
.parts-sum-line { margin-top: 4px; color: var(--muted); }
</style>
