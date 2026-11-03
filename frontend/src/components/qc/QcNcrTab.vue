<template>
  <div>
    <!-- 双通道对账：设备采到的 vs 人工补录的 -->
    <div v-if="recon" class="recon-wrap">
      <QcReconBar :data="recon" variant="full" />
    </div>

    <!-- 视图分段：待处置 / 部分处置 / 已处置 / 已作废 / 全部 -->
    <div class="tab-toolbar">
      <el-radio-group v-model="view" size="small" @change="reload">
        <el-radio-button value="PENDING">{{ t('qc.ncr.viewPending') }}</el-radio-button>
        <el-radio-button value="PARTIAL">{{ t('qc.ncr.viewPartial') }}</el-radio-button>
        <el-radio-button value="DISPOSED">{{ t('qc.ncr.viewDone') }}</el-radio-button>
        <el-radio-button value="CANCELLED">{{ t('qc.ncr.viewCancelled') }}</el-radio-button>
        <el-radio-button value="ALL">{{ t('qc.ncr.viewAll') }}</el-radio-button>
      </el-radio-group>
      <el-select v-model="source" clearable :placeholder="t('qc.ncr.filterSource')" class="w140" @change="reload">
        <el-option v-for="s in QC_SOURCES" :key="s" :value="s" :label="t('qc.source.' + s)" />
      </el-select>
      <el-select v-model="severity" clearable :placeholder="t('qc.ncr.filterSeverity')" class="w120" @change="reload">
        <el-option v-for="s in SEVERITIES" :key="s" :value="s" :label="t('qc.severity.' + s)" />
      </el-select>
      <el-input
        v-model="kw"
        :placeholder="t('qc.ncr.searchPh')"
        clearable
        :prefix-icon="Search"
        class="w200"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-button :icon="Refresh" :loading="loading" @click="reload">{{ t('base.refresh') }}</el-button>
      <div class="grow" />
      <el-button v-if="auth.canOperate" type="primary" :icon="Plus" @click="registerOpen = true">
        {{ t('qc.ncr.registerBtn') }}
      </el-button>
    </div>

    <!-- 批量操作条：选中才出现 -->
    <div v-if="selected.length" class="batch-bar">
      <span class="bb-count">{{ t('qc.ncr.selected', { n: selected.length }) }}</span>
      <el-button size="small" :disabled="!auth.canWrite" @click="triageOpen = true">
        {{ t('qc.ncr.batchTriage') }}
      </el-button>
      <el-button size="small" type="primary" :disabled="!auth.canWrite" @click="disposeOpen = true">
        {{ t('qc.ncr.batchDispose') }}
      </el-button>
      <el-button size="small" text @click="clearSelection">{{ t('qc.ncr.clearSel') }}</el-button>
    </div>

    <el-alert
      v-if="errMsg"
      type="error"
      :closable="false"
      show-icon
      class="mb12"
      :title="t('qc.ncr.loadFail')"
      :description="errMsg"
    >
      <template #default>
        <el-button size="small" :icon="Refresh" @click="reload">{{ t('qc.ncr.retry') }}</el-button>
      </template>
    </el-alert>

    <el-table
      :data="rows"
      stripe
      v-loading="loading"
      :empty-text="t('qc.ncr.empty')"
      @selection-change="onSelect"
    >
      <el-table-column type="selection" width="42" :selectable="(r: QcDefectRow) => r.status === 'PENDING'" />
      <el-table-column :label="t('qc.ncr.colNo')" width="150">
        <template #default="{ row }"><span class="num">{{ row.defectNo }}</span></template>
      </el-table-column>
      <el-table-column :label="t('qc.ncr.colProduct')" min-width="140">
        <template #default="{ row }">
          <div class="cell-main">{{ row.skuName || '--' }}</div>
          <div class="cell-sub num">{{ row.skuCode }}</div>
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.ncr.colDefect')" min-width="130">
        <template #default="{ row }">
          <div class="cell-main">
            <span v-if="row.defectCode" class="num">{{ row.defectCode }}</span>
            <span v-if="row.defectName"> {{ row.defectName }}</span>
            <span v-if="!row.defectCode" class="muted">{{ t('qc.ncr.untriaged') }}</span>
          </div>
          <el-tag
            v-if="row.severity"
            size="small"
            effect="plain"
            :type="gradeType(row.severity)"
          >{{ t('qc.severity.' + row.severity) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.ncr.colQty')" width="104" align="right">
        <template #default="{ row }">
          <span class="num strong">{{ qty(row.qty, 0) }}</span>
          <div v-if="row.disposedQty > 0" class="cell-sub num">
            {{ t('qc.ncr.disposedOf', { d: qty(row.disposedQty, 0), p: qty(row.pendingQty, 0) }) }}
          </div>
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.ncr.colSource')" width="96">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" :type="sourceType(row.source)">
            {{ t('qc.source.' + row.source) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.ncr.colStatus')" width="106">
        <template #default="{ row }">
          <el-tag size="small" effect="light" :type="displayStatusType(row.displayStatus)">
            {{ t('qc.displayStatus.' + row.displayStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.ncr.colFoundAt')" width="96">
        <template #default="{ row }"><span class="num muted">{{ dayText(row.foundAt) }}</span></template>
      </el-table-column>
      <el-table-column :label="t('base.action')" width="92" align="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'PENDING' && auth.canWrite"
            size="small"
            type="primary"
            @click="openDispose(row)"
          >{{ t('qc.ncr.dispose') }}</el-button>
          <span v-else class="muted">{{ t('qc.ncr.noAction') }}</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="prev, pager, next, total"
        background
        @current-change="load"
      />
    </div>

    <QcRegisterDialog v-model="registerOpen" @saved="onSaved" />
    <QcDispositionDialog
      v-model="disposeOpen"
      :defects="disposeTargets"
      :batch="disposeTargets.length > 1"
      @saved="onSaved"
    />
    <QcTriageDialog v-model="triageOpen" :defect-nos="selectedNos" @saved="onSaved" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import {
  deriveDisplayStatus, displayStatusType, gradeType, qcDefectApi, qcReportApi,
  QC_SOURCES, SEVERITIES, sourceType, type QcDefectRow, type QcReconView
} from '@/api/qc'
import { useAuthStore } from '@/stores/auth'
import { dayText, qty } from '@/utils/format'
import QcReconBar from './QcReconBar.vue'
import QcRegisterDialog from './QcRegisterDialog.vue'
import QcDispositionDialog from './QcDispositionDialog.vue'
import QcTriageDialog from './QcTriageDialog.vue'

const { t } = useI18n()
const auth = useAuthStore()

const rows = ref<QcDefectRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const errMsg = ref('')
const view = ref<'PENDING' | 'PARTIAL' | 'DISPOSED' | 'CANCELLED' | 'ALL'>('PENDING')
const source = ref('')
const severity = ref('')
const kw = ref('')
const selected = ref<QcDefectRow[]>([])
const recon = ref<QcReconView | null>(null)

const registerOpen = ref(false)
const disposeOpen = ref(false)
const triageOpen = ref(false)
const disposeTargets = ref<QcDefectRow[]>([])

function reload() {
  page.value = 1
  load()
}

async function load() {
  loading.value = true
  errMsg.value = ''
  try {
    const p: Parameters<typeof qcDefectApi.list>[0] = {
      source: source.value,
      severity: severity.value,
      kw: kw.value,
      page: page.value,
      size: size.value
    }
    // 「部分处置」不是独立状态，后端用 partial=true 翻译成
    // disposed_qty > 0 AND disposed_qty < qty
    if (view.value === 'PARTIAL') {
      p.partial = true
    } else if (view.value !== 'ALL') {
      p.status = view.value
    }
    const r = await qcDefectApi.list(p)
    if (!r.ok) {
      errMsg.value = (r as unknown as { message?: string }).message || t('common.reqFail')
      rows.value = []
      return
    }
    rows.value = (r.rows || []).map((d) => ({
      ...d,
      pendingQty: Math.max(0, Number(d.qty || 0) - Number(d.disposedQty || 0)),
      displayStatus: deriveDisplayStatus(d)
    }))
    total.value = r.total || 0
  } catch (e) {
    errMsg.value = (e as Error)?.message || t('common.reqFail')
    rows.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 对账条：后端给的是「每张工单一行」，这里取当前页这些记录所属工单的那一条。
 *
 * 取第一条而不是随便挑一张：不良品页关心的是眼前这批货，
 * 拿别的工单的对账数摆在这里，看的人会以为差的是这批货。
 */
async function loadRecon() {
  try {
    const r = await qcReportApi.recon()
    if (!r || !r.ok || !r.items || !r.items.length) {
      recon.value = null
      return
    }
    const want = rows.value.find((x) => x.workOrderNo)?.workOrderNo
    const item = want ? r.items.find((i) => i.workOrderNo === want) : r.items[0]
    recon.value = item
      ? {
          deviceQty: item.deviceQty,
          manualQty: item.manualQty,
          effectiveQty: item.effectiveQty,
          gap: item.gap,
          challenged: item.challenged,
          source: item.source,
          deviceConfigured: item.deviceConfigured,
          deviceCollected: item.deviceCollected
        }
      : null
  } catch {
    recon.value = null
  }
}

function onSelect(sel: QcDefectRow[]) {
  selected.value = sel
}

/** 批量操作要的是单号，不是整行 */
const selectedNos = computed(() => selected.value.map((d) => d.defectNo))

function clearSelection() {
  selected.value = []
}

function openDispose(row: QcDefectRow) {
  disposeTargets.value = [row]
  disposeOpen.value = true
}

async function onSaved() {
  selected.value = []
  await load()
  await loadRecon()
}

onMounted(() => {
  load()
  loadRecon()
})
</script>

<style scoped>
.recon-wrap {
  margin-bottom: 12px;
}
.batch-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  padding: 8px 12px;
  border: 1px solid var(--accent);
  border-radius: var(--radius-md);
  background: var(--accent-soft);
}
.bb-count {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--fg);
}
.w140 { width: 140px; }
</style>
