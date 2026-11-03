<template>
  <!-- 检验项明细（只读）。录入用 QcItemEditor，这里只负责展示 -->
  <el-table :data="items" size="small" stripe :empty-text="t('qc.item.empty')">
    <el-table-column :label="t('qc.item.colName')" min-width="150">
      <template #default="{ row }">
        <div class="cell-main">{{ row.itemName }}</div>
        <div class="cell-sub">{{ row.standard || '--' }}</div>
      </template>
    </el-table-column>
    <el-table-column :label="t('qc.item.colMethod')" width="110">
      <template #default="{ row }">{{ row.method || '--' }}</template>
    </el-table-column>
    <el-table-column :label="t('qc.item.colDefect')" min-width="150">
      <template #default="{ row }">
        <template v-if="row.defectCode">
          <span class="num">{{ row.defectCode }}</span>
          <span class="muted"> {{ row.defectName }}</span>
          <el-tag
            v-if="row.severity"
            size="small"
            effect="plain"
            class="ml8"
            :type="gradeType(row.severity)"
          >{{ t('qc.severity.' + row.severity) }}</el-tag>
        </template>
        <span v-else class="muted">--</span>
      </template>
    </el-table-column>
    <el-table-column :label="t('qc.item.colMeasured')" min-width="130">
      <template #default="{ row }">{{ row.measured || '--' }}</template>
    </el-table-column>
    <el-table-column :label="t('qc.item.colResult')" width="80">
      <template #default="{ row }">
        <el-tag size="small" effect="light" :type="row.result === 'NG' ? 'danger' : 'success'">
          {{ row.result === 'NG' ? t('qc.item.ng') : t('qc.item.ok') }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column :label="t('qc.item.colQty')" width="80" align="right">
      <template #default="{ row }"><span class="num">{{ qty(row.defectQty, 0) }}</span></template>
    </el-table-column>
  </el-table>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { gradeType, type QcInspectionItem } from '@/api/qc'
import { qty } from '@/utils/format'

defineProps<{ items: QcInspectionItem[] }>()

const { t } = useI18n()
</script>
