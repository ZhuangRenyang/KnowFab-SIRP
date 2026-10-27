<template>
  <div class="page">
    <div class="toolbar">
      <el-input v-model="q" placeholder="搜索编码 / 名称 / 位置" clearable :prefix-icon="Search" class="w240" />
      <el-select v-model="st" class="w110">
        <el-option value="" label="全部状态" />
        <el-option value="online" label="仅在线" />
        <el-option value="offline" label="仅离线" />
        <el-option value="run" label="仅运行" />
        <el-option value="alarm" label="仅报警" />
      </el-select>
      <el-select v-model="pageSize" class="w100">
        <el-option v-for="n in [24, 48, 96, 200]" :key="n" :value="n" :label="`${n} 台/页`" />
      </el-select>
      <el-button :icon="Refresh" :loading="store.loading" @click="store.load()">刷新</el-button>
    </div>

    <div v-if="store.lastError" class="load-err">加载失败：{{ store.lastError }}（自动重试：点「刷新」）</div>

    <div v-if="paged.length" class="grid">
      <div
        v-for="d in paged" :key="d.code"
        class="card" :class="{ offline: !d.online, sel: selected === d.code }"
        @click="selected = d.code"
      >
        <div class="card-top">
          <div class="card-id">
            <div class="dev-name">{{ d.name }}</div>
            <div class="dev-code num">{{ d.code }}<template v-if="d.location"> · {{ d.location }}</template></div>
          </div>
          <StatusBadge :type="statusOf(d).type" :label="statusOf(d).label" :dot="statusOf(d).type === 'run'" />
        </div>
        <div class="kpis">
          <div v-for="p in pickKpis(d)" :key="p.pointKey ?? p.key" class="kpi">
            <span>{{ p.name }}</span>
            <b class="num">{{ fmt(p.value) }}<i v-if="p.unit">{{ p.unit }}</i></b>
          </div>
        </div>
      </div>
    </div>
    <el-empty v-else :description="store.list.length ? '没有匹配的设备' : '还没有设备，点右上角「新增设备」'" />

    <div v-if="filtered.length" class="pager">
      共 <b>{{ filtered.length }}</b> 台 · 第 <b>{{ page }}</b>/<b>{{ totalPages }}</b> 页
      <el-button size="small" :disabled="page <= 1" @click="page--">上一页</el-button>
      <el-button size="small" :disabled="page >= totalPages" @click="page++">下一页</el-button>
    </div>

    <DeviceDetail v-if="selected" :code="selected" @edit="openEdit" @removed="onRemoved" />

    <DeviceFormDialog v-model="formVisible" :edit-code="editCode" @saved="onSaved" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Refresh, Search } from '@element-plus/icons-vue'
import { useDevicesStore, statusOf, pickKpis, fmt } from '@/stores/devices'
import StatusBadge from '@/components/StatusBadge.vue'
import DeviceDetail from '@/components/DeviceDetail.vue'
import DeviceFormDialog from '@/components/DeviceFormDialog.vue'

const route = useRoute()
const router = useRouter()
const store = useDevicesStore()

/* 筛选与分页（PRD §1.1：搜索即时生效、状态五选、每页 24/48/96/200 默认 48） */
const q = ref('')
const st = ref('')
const pageSize = ref(48)
const page = ref(1)

const filtered = computed(() => {
  let list = store.list
  if (q.value) {
    const k = q.value.toLowerCase()
    list = list.filter((d: any) => `${d.code} ${d.name} ${d.location || ''}`.toLowerCase().includes(k))
  }
  if (st.value === 'online') list = list.filter((d: any) => d.online)
  else if (st.value === 'offline') list = list.filter((d: any) => !d.online)
  else if (st.value === 'run') list = list.filter((d: any) => statusOf(d).v === 2)
  else if (st.value === 'alarm') list = list.filter((d: any) => statusOf(d).v === 3)
  return list
})
const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize.value)))
const paged = computed(() => filtered.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))

watch([q, st, pageSize], () => { page.value = 1 })

const selected = ref('')
const formVisible = ref(false)
const editCode = ref('')

function openEdit(code: string) {
  editCode.value = code
  formVisible.value = true
}
function onRemoved(code: string) {
  if (selected.value === code) selected.value = ''
  store.load()
}
function onSaved() {
  formVisible.value = false
  store.load()
}

/* 顶栏「+ 新增设备」跳转 ?new=1 时自动开弹窗，然后清掉 query 防刷新重开 */
watch(() => route.query.new, (v) => {
  if (v) {
    editCode.value = ''
    formVisible.value = true
    router.replace({ path: '/' })
  }
}, { immediate: true })
</script>

<style scoped>
.toolbar { display: flex; gap: 10px; margin-bottom: var(--space-4); flex-wrap: wrap; }
.w240 { width: 240px; }
.w110 { width: 110px; }
.w100 { width: 100px; }
.load-err {
  margin-bottom: var(--space-3); padding: 8px 12px; font-size: var(--text-sm);
  color: var(--alarm); background: var(--alarm-soft); border-radius: var(--radius-sm);
}
.grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: var(--space-3);
}
.card {
  background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg);
  padding: var(--space-3) var(--space-4); cursor: pointer; user-select: none;
  transition: border-color var(--motion-fast) var(--ease-standard);
}
.card:hover { border-color: var(--border-strong); }
.card.sel { border-color: var(--accent); box-shadow: var(--focus-ring); }
.card.offline { opacity: .62; }
.card-top { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.dev-name { font-size: var(--text-md); font-weight: 500; }
.dev-code { font-size: var(--text-xs); color: var(--muted); }
.kpis { display: grid; grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); gap: 6px; margin-top: 10px; }
.kpi {
  background: var(--surface-2); border-radius: var(--radius-md); padding: 6px 10px;
  display: flex; flex-direction: column; min-width: 0;
}
.kpi span { font-size: var(--text-2xs); color: var(--muted); }
.kpi b { font-size: var(--text-md); font-weight: 500; }
.kpi b i { font-style: normal; font-size: var(--text-2xs); color: var(--muted); margin-left: 2px; }
.empty { text-align: center; color: var(--muted); padding: var(--space-10) 0; }
.pager {
  display: flex; align-items: center; gap: 10px; justify-content: flex-end;
  margin-top: var(--space-4); color: var(--muted); font-size: var(--text-sm);
}
.pager b { color: var(--fg); }
</style>
