<template>
  <div class="page">
    <el-card shadow="never">
      <el-empty :description="`${route.meta.title || '模块'} 正在按迁移批次（B2~B7）推进，一次性切换前旧版界面继续可用`">
        <el-button type="primary" tag="a" href="/" plain>返回旧版界面</el-button>
      </el-empty>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
</script>
