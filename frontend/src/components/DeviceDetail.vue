<template>
  <div v-if="d" class="detail">
    <div class="d-head">
      <div>
        <div class="d-name">
          {{ d.name }}
          <StatusBadge :type="s.type" :label="s.label" :dot="s.type === 'run'" />
        </div>
        <div class="d-meta num">
          {{ d.code }} · {{ d.protocol }}<template v-if="d.location"> · {{ d.location }}</template>
          <template v-if="d.pollIntervalMs"> · 采集周期 {{ d.pollIntervalMs }}ms</template>
        </div>
      </div>
      <div v-if="auth.canWrite" class="d-actions">
        <el-button size="small" @click="emit('edit', d.code)">编辑配置</el-button>
        <el-button size="small" type="danger" plain @click="removeDevice">删除</el-button>
      </div>
    </div>

    <div class="keybar">
      <template v-if="!d.apiKey">
        <span class="off">对外接口未启用（没有 API Key，/open/v1 不对外）</span>
        <el-button size="small" @click="resetKey">生成 Key</el-button>
      </template>
      <template v-else>
        <span>API Key</span>
        <code class="num">{{ d.apiKey }}</code>
        <el-button size="small" @click="copyKey(d.apiKey)">复制</el-button>
        <el-button size="small" @click="resetKey">重置</el-button>
        <el-button v-if="d.apiKeyEnabled" size="small" type="danger" plain @click="toggleKey(false)">停用</el-button>
        <template v-else>
          <el-button size="small" @click="toggleKey(true)">启用</el-button>
          <span class="off">已停用</span>
        </template>
      </template>
    </div>

    <div class="pts">
      <div v-for="p in d.points" :key="p.pointKey ?? p.key" class="pt" :class="{ bad: p.quality !== 'GOOD' }">
        <!-- 产量点位只显示「今日产量」：老厂 PLC 寄存器里是几十万的历史累计，
             直接当产量展示会误导 -->
        <span>{{ p.role === 'count' ? '今日产量' : p.name }}</span>
        <b class="num">
          <template v-if="p.role === 'count'">
            {{ fmt(d.todayOutput ?? 0) + (p.unit || '') }}
          </template>
          <template v-else>{{ p.quality === 'GOOD' ? fmt(p.value) + (p.unit || '') : '--' }}</template>
        </b>
      </div>
    </div>

    <!-- key=设备编码：切换设备时销毁重建。没有 key 的话，先打开一台无数值点的
         老设备（v-if 让 el 不存在）再切到新设备，onMounted 里的 init 已错过、
         图表永远空白——这已经是本项目第四次踩"图表 div 未挂载就 init"的坑 -->
    <PointTrend :device="d" :key="d.code" />
    <ProdStats :code="d.code" />

    <div v-if="d.lastError" class="last-err">最后错误：{{ d.lastError }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useDevicesStore, statusOf, fmt } from '@/stores/devices'
import { devicesApi } from '@/api/devices'
import StatusBadge from '@/components/StatusBadge.vue'
import PointTrend from '@/components/PointTrend.vue'
import ProdStats from '@/components/ProdStats.vue'

const props = defineProps<{ code: string }>()
const emit = defineEmits<{ edit: [code: string]; removed: [code: string] }>()

const auth = useAuthStore()
const store = useDevicesStore()

const d = computed(() => store.list.find((x: any) => x.code === props.code))
const s = computed(() => (d.value ? statusOf(d.value) : { v: -1, label: '--', type: 'off' as const }))

/* 危险操作 confirm 文案与旧版逐字一致（PRD 风险 8） */
async function removeDevice() {
  if (!d.value) return
  try {
    await ElMessageBox.confirm(
      `确定删除设备「${d.value.name}（${d.value.code}）」？\n采集会立即停止，历史缓存也会清掉。`,
      '删除设备',
      { type: 'warning' }
    )
  } catch { return }
  await devicesApi.remove(d.value.id)
  emit('removed', d.value.code)
}

async function copyKey(key: string) {
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(key)
      ElMessage.success('已复制到剪贴板')
    } else {
      window.prompt('手动复制：', key)
    }
  } catch {
    window.prompt('手动复制：', key)
  }
}

async function resetKey() {
  if (!d.value) return
  try {
    await ElMessageBox.confirm('重置后旧 Key 立即失效，正在对接的系统会断。确认重置？', '重置 API Key', { type: 'warning' })
  } catch { return }
  const r = await devicesApi.resetKey(d.value.code)
  if (!r.ok) { ElMessage.error(r.message || '重置失败'); return }
  await ElMessageBox.alert(`新 Key：${r.apiKey}\n\n请同步给对接方，旧 Key 已失效。`, '已生成新 Key')
  await store.reloadAll()
}

async function toggleKey(enabled: boolean) {
  if (!d.value) return
  const r = await devicesApi.toggleKey(d.value.code, enabled)
  if (!r.ok) { ElMessage.error(r.message || '操作失败'); return }
  await store.reloadAll()
}
</script>

<style scoped>
.detail {
  margin-top: var(--space-4); background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius-lg); padding: var(--space-4);
}
.d-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.d-name { display: flex; align-items: center; gap: 8px; font-size: var(--text-md); font-weight: 500; }
.d-meta { font-size: var(--text-xs); color: var(--muted); margin-top: 2px; }
.d-actions { flex: none; }
.keybar {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  background: var(--surface-2); border-radius: var(--radius-md);
  padding: 8px 12px; margin: var(--space-3) 0; font-size: var(--text-sm);
}
.keybar code { font-size: var(--text-xs); color: var(--fg-2); word-break: break-all; }
.keybar .off { color: var(--alarm); }
.pts { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 6px; }
.pt {
  background: var(--surface-2); border-radius: var(--radius-md); padding: 6px 10px;
  display: flex; flex-direction: column; min-width: 0;
}
.pt span { font-size: var(--text-2xs); color: var(--muted); }
.pt b { font-size: var(--text-sm); font-weight: 500; }
.pt.bad b { color: var(--muted); }
.last-err { margin-top: var(--space-3); color: var(--alarm); font-size: var(--text-sm); }
</style>
