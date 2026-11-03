<template>
  <div class="page">
    <el-card shadow="never">
      <el-tabs v-model="tab">
        <el-tab-pane :label="t('base.tabSku')" name="sku"><ErpSkuTab /></el-tab-pane>
        <el-tab-pane :label="t('base.tabPartner')" name="partner"><ErpPartnerTab /></el-tab-pane>
        <el-tab-pane :label="t('base.tabWh')" name="warehouse"><ErpWarehouseTab /></el-tab-pane>
        <el-tab-pane :label="t('base.tabBom')" name="bom" lazy><ErpBomTab /></el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import ErpSkuTab from '@/components/erp/ErpSkuTab.vue'
import ErpPartnerTab from '@/components/erp/ErpPartnerTab.vue'
import ErpWarehouseTab from '@/components/erp/ErpWarehouseTab.vue'
import ErpBomTab from '@/components/erp/ErpBomTab.vue'

const { t } = useI18n()

/* ERP 基础资料：商品 / 往来单位 / 仓库 / 物料清单（BOM，DH-70）
   BOM 是「原料 → 成品」闭环的依据：没有它，成品产出时原料一分不动。 */
const tab = ref('sku')
</script>
