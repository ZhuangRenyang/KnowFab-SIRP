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
        <el-table-column :label="t('maint.actions')" width="176" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'ANALYZED'"
              size="small" type="primary" @click="start(row)"
            >{{ t('maint.startBtn') }}</el-button>
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
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { maintApi, type MaintOrder } from '@/api/maint'
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
</style>
