<template>
  <div class="page">
    <el-card shadow="never">
      <el-tabs v-model="tab">
        <el-tab-pane label="商品" name="sku"><ErpSkuTab /></el-tab-pane>
        <el-tab-pane label="往来单位" name="partner"><ErpPartnerTab /></el-tab-pane>
        <el-tab-pane label="仓库" name="warehouse"><ErpWarehouseTab /></el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import ErpSkuTab from '@/components/erp/ErpSkuTab.vue'
import ErpPartnerTab from '@/components/erp/ErpPartnerTab.vue'
import ErpWarehouseTab from '@/components/erp/ErpWarehouseTab.vue'

/* ERP 基础资料：商品 / 往来单位 / 仓库（参照 jshERP 基本资料自研重写） */
const tab = ref('sku')
</script>
