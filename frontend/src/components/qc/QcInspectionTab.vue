<template>
  <div>
    <div class="tab-toolbar">
      <el-select v-model="type" clearable :placeholder="t('qc.insp.filterType')" class="w140" @change="reload">
        <el-option v-for="x in QC_TYPES" :key="x" :value="x" :label="t('qc.type.' + x)" />
      </el-select>
      <el-select v-model="status" clearable :placeholder="t('qc.insp.filterStatus')" class="w140" @change="reload">
        <el-option v-for="s in QC_STATUSES" :key="s" :value="s" :label="t('qc.status.' + s)" />
      </el-select>
      <el-date-picker
        v-model="range"
        type="daterange"
        value-format="YYYY-MM-DD"
        :range-separator="t('qc.insp.to')"
        :start-placeholder="t('qc.insp.from')"
        :end-placeholder="t('qc.insp.to')"
        class="w240"
        @change="reload"
      />
      <el-input
        v-model="kw"
        :placeholder="t('qc.insp.searchPh')"
        clearable
        :prefix-icon="Search"
        class="w200"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-button :icon="Refresh" :loading="loading" @click="reload">{{ t('base.refresh') }}</el-button>
      <div class="grow" />
      <el-button v-if="auth.canOperate" type="primary" :icon="Plus" @click="openCreate">
        {{ t('qc.insp.newBtn') }}
      </el-button>
    </div>

    <el-alert
      v-if="errMsg"
      type="error"
      :closable="false"
      show-icon
      class="mb12"
      :title="t('qc.insp.loadFail')"
      :description="errMsg"
    >
      <template #default>
        <el-button size="small" :icon="Refresh" @click="reload">{{ t('qc.insp.retry') }}</el-button>
      </template>
    </el-alert>

    <el-table :data="rows" stripe v-loading="loading" :empty-text="t('qc.insp.empty')">
      <el-table-column :label="t('qc.insp.colNo')" width="150">
        <template #default="{ row }">
          <el-button link type="primary" class="num" @click="openDetail(row.inspectionNo)">
            {{ row.inspectionNo }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.insp.colType')" width="76">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ t('qc.type.' + row.inspectionType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.insp.colProduct')" min-width="140">
        <template #default="{ row }">
          <div class="cell-main">{{ row.skuName || '--' }}</div>
          <div class="cell-sub num">{{ row.skuCode }}</div>
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.insp.colLot')" width="84" align="right">
        <template #default="{ row }"><span class="num">{{ qty(row.lotQty, 0) }}</span></template>
      </el-table-column>
      <el-table-column :label="t('qc.insp.colSample')" width="108">
        <template #default="{ row }">
          <span class="num">{{ row.codeLetter || '--' }}</span>
          <span class="num muted">n={{ row.sampleSize ?? '--' }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.insp.colDefect')" width="84" align="right">
        <template #default="{ row }">
          <span class="num" :class="{ danger: row.defectQty > 0 }">{{ qty(row.defectQty, 0) }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.insp.colResult')" width="92">
        <template #default="{ row }">
          <el-tag v-if="row.result" size="small" effect="light" :type="verdictType(row.result)">
            {{ t('qc.verdict.' + row.result) }}
          </el-tag>
          <span v-else class="muted">--</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.insp.colStatus')" width="92">
        <template #default="{ row }">
          <el-tag size="small" effect="light" :type="qcStatusType(row.status)">
            {{ t('qc.status.' + row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.insp.colTime')" width="96">
        <template #default="{ row }"><span class="num muted">{{ dayText(row.createdAt) }}</span></template>
      </el-table-column>
      <el-table-column :label="t('base.action')" width="84" align="right">
        <template #default="{ row }">
          <el-button size="small" @click="openDetail(row.inspectionNo)">{{ t('qc.insp.detail') }}</el-button>
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

    <QcInspectionDrawer
      v-model="drawerOpen"
      :detail="detail"
      :loading="detailLoading"
      :has-prev="hasPrev"
      :has-next="hasNext"
      @nav="navDetail"
      @changed="onChanged"
    />
    <QcInspectionCreateDialog v-model="createOpen" @created="onCreated" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import {
  qcInspectionApi, qcStatusType, QC_STATUSES, QC_TYPES, verdictType,
  type QcInspectionDetail, type QcInspectionRow
} from '@/api/qc'
import { useAuthStore } from '@/stores/auth'
import { dayText, qty } from '@/utils/format'
import QcInspectionDrawer from './QcInspectionDrawer.vue'
import QcInspectionCreateDialog from './QcInspectionCreateDialog.vue'

const { t } = useI18n()
const auth = useAuthStore()

const rows = ref<QcInspectionRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const errMsg = ref('')
const type = ref('')
const status = ref('')
const kw = ref('')
const range = ref<[string, string] | null>(null)

const drawerOpen = ref(false)
const createOpen = ref(false)
const detail = ref<QcInspectionDetail | null>(null)
const detailLoading = ref(false)

/** 上一张 / 下一张：按当前列表顺序找，找不到就禁用 */
const curIndex = computed(() => rows.value.findIndex((r) => r.inspectionNo === detail.value?.inspectionNo))
const hasPrev = computed(() => curIndex.value > 0)
const hasNext = computed(() => curIndex.value >= 0 && curIndex.value < rows.value.length - 1)

function reload() {
  page.value = 1
  load()
}

async function load() {
  loading.value = true
  errMsg.value = ''
  try {
    const [from, to] = range.value || []
    const r = await qcInspectionApi.list({
      type: type.value, status: status.value, kw: kw.value,
      from, to, page: page.value, size: size.value
    })
    if (!r.ok) {
      errMsg.value = (r as unknown as { message?: string }).message || t('common.reqFail')
      rows.value = []
      return
    }
    rows.value = r.rows || []
    total.value = r.total || 0
  } catch (e) {
    errMsg.value = (e as Error)?.message || t('common.reqFail')
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function openDetail(no: string) {
  drawerOpen.value = true
  detailLoading.value = true
  detail.value = null
  try {
    const d = await qcInspectionApi.detail(no)
    detail.value = d
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
    drawerOpen.value = false
  } finally {
    detailLoading.value = false
  }
}

async function navDetail(dir: -1 | 1) {
  const next = rows.value[curIndex.value + dir]
  if (next) await openDetail(next.inspectionNo)
}

function openCreate() {
  createOpen.value = true
}

async function onChanged() {
  await load()
  if (detail.value) await openDetail(detail.value.inspectionNo)
}

async function onCreated(no: string) {
  await load()
  if (no) await openDetail(no)
}

onMounted(load)
</script>

<style scoped>
.w140 { width: 140px; }
</style>
