<template>
  <div class="page">
    <!-- 一条路由 + 4 个 tab（QUALITY-SPEC §2.9）：
         不加 5 个一级菜单，1366×768 一体机上侧栏会出滚动条 -->
    <el-tabs v-model="tab" class="qc-tabs" @tab-change="onTabChange">
      <el-tab-pane :label="t('qc.tab.board')" name="board">
        <QcBoardTab v-if="tab === 'board'" />
      </el-tab-pane>
      <el-tab-pane :label="t('qc.tab.inspection')" name="inspection">
        <QcInspectionTab v-if="tab === 'inspection'" />
      </el-tab-pane>
      <el-tab-pane :label="t('qc.tab.ncr')" name="ncr">
        <QcNcrTab v-if="tab === 'ncr'" />
      </el-tab-pane>
      <el-tab-pane :label="t('qc.tab.dict')" name="dict">
        <QcMasterDataTab v-if="tab === 'dict'" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import QcBoardTab from '@/components/qc/QcBoardTab.vue'
import QcInspectionTab from '@/components/qc/QcInspectionTab.vue'
import QcNcrTab from '@/components/qc/QcNcrTab.vue'
import QcMasterDataTab from '@/components/qc/QcMasterDataTab.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

/** tab 名与路由 query 同步，刷新后停在原处；?qc=单号 可直接深链到检验单 */
const TABS = ['board', 'inspection', 'ncr', 'dict'] as const
type TabName = typeof TABS[number]

const tab = ref<TabName>('board')

function readQuery(): TabName {
  const q = String(route.query.tab || '')
  return (TABS as readonly string[]).includes(q) ? (q as TabName) : 'board'
}

function syncQuery(next: TabName) {
  const q: Record<string, string> = { tab: next }
  // 深链参数保留：?qc=QC202611030001 打开时直接落在检验单页
  const deep = route.query.qc
  if (typeof deep === 'string' && deep) q.qc = deep
  router.replace({ path: route.path, query: q })
}

onMounted(() => {
  tab.value = readQuery()
  if (route.query.qc) tab.value = 'inspection'
})

function onTabChange(v: string | number) {
  const next = v as TabName
  tab.value = next
  syncQuery(next)
}
</script>

<style scoped>
/* tab 头部：贴住页面顶部，别让内容整体往下缩一截 */
.qc-tabs :deep(.el-tabs__header) {
  margin-bottom: 14px;
}
</style>
