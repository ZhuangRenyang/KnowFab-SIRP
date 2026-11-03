<template>
  <!-- 质检主数据：缺陷分类 + AQL 抽样方案。两块内容差异很大，各自一个组件 -->
  <el-tabs v-model="tab" class="md-tabs">
    <el-tab-pane :label="t('qc.dict.tabCodes')" name="code">
      <QcDefectCodeTab v-if="tab === 'code'" />
    </el-tab-pane>
    <el-tab-pane :label="t('qc.dict.tabPlans')" name="plan">
      <QcAqlPlanTab v-if="tab === 'plan'" />
    </el-tab-pane>
  </el-tabs>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import QcDefectCodeTab from './QcDefectCodeTab.vue'
import QcAqlPlanTab from './QcAqlPlanTab.vue'

const { t } = useI18n()
const tab = ref('code')
</script>

<style scoped>
.md-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}
</style>
