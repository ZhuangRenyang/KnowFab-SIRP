<template>
  <!--
    设备发现弹框（2026-10-29）：用户要求把「发现」从独立页面搬进设备监控界面，
    以弹框形式打开。内容直接复用 DiscoveryView（三步流程一行没改），
    避免维护两份扫描 UI；/discovery 路由保留，旧书签 /discover.html 继续可用。
    embed 模式：第三步多一个「直接添加为设备」，把 IP/端口/点表直接带到新增设备表单。
  -->
  <el-dialog
    v-model="visible" title="设备发现 · 自动扫描找设备"
    width="1120px" top="4vh" class="disco-dialog" destroy-on-close
  >
    <div class="disco-body">
      <DiscoveryView embed @add-device="onAddDevice" />
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import DiscoveryView from '@/views/DiscoveryView.vue'

const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{
  addDevice: [p: { protocol: string; host: string; port: number; unitId: number; pointsText: string }]
}>()

/** 扫描结果直接去新增设备：关掉发现弹框，把预填数据交给父级打开表单 */
function onAddDevice(p: { protocol: string; host: string; port: number; unitId: number; pointsText: string }) {
  visible.value = false
  emit('addDevice', p)
}
</script>

<style scoped>
/* 弹框正文区独立滚动：发现页三步内容很长，不能把弹框撑出屏幕 */
.disco-body { max-height: 74vh; overflow-y: auto; padding-right: 6px; }
</style>
