<template>
  <div class="system">
    <div class="kpis">
      <div class="ebox"><span>当前版本</span><b class="num">{{ st?.version || '--' }}</b>
        <em>构建时间 {{ st?.buildTime || '--' }}</em></div>
      <div class="ebox"><span>上次升级</span>
        <b class="num">{{ st?.applied?.version || '首次安装' }}</b>
        <em>{{ st?.applied?.at || ('数据库结构版本 ' + (st?.schemaVersion || '0')) }}</em></div>
      <div class="ebox"><span>待应用</span>
        <b class="num">{{ st?.pending?.present ? (st.pending.toVersion || '有') : '无' }}</b>
        <em>{{ st?.pending?.present ? ('从 ' + (st.pending.fromVersion || '--') + ' 升级') : '上传升级包后显示' }}</em></div>
      <div class="ebox"><span>可回滚备份</span><b class="num">{{ st?.backups?.length || 0 }}<i>份</i></b>
        <em>回滚只恢复程序，不动数据</em></div>
    </div>

    <el-alert v-if="pendingMsg" :title="pendingMsg" type="success" show-icon :closable="false" class="pm" />

    <SettingsPanel />
    <UpdatePanel :status="st" @changed="load" />
    <MigrationsPanel />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { systemApi, type UpdateStatus } from '@/api/system'
import SettingsPanel from '@/components/system/SettingsPanel.vue'
import UpdatePanel from '@/components/system/UpdatePanel.vue'
import MigrationsPanel from '@/components/system/MigrationsPanel.vue'

const st = ref<UpdateStatus | null>(null)

const pendingMsg = computed(() => {
  const p = st.value?.pending
  if (!p?.present) return ''
  return `已收到升级包 ${p.toVersion || ''}：${p.notes || '（无说明）'}。`
    + '点「应用升级」后生效；当前服务不会自己换文件，需要重启那一下。'
})

async function load() {
  try { st.value = await systemApi.status() }
  catch (e: any) { ElMessage.error(e?.message || '系统信息加载失败') }
}

onMounted(load)
</script>

<style scoped>
.kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--space-3); margin-bottom: var(--space-4); }
.ebox { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--elev-raised); padding: var(--space-4); display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.ebox span { font-size: var(--text-xs); color: var(--muted); letter-spacing: .06em; }
.ebox b { font-size: var(--text-xl); font-weight: 600; }
.ebox b i { font-style: normal; font-size: var(--text-xs); color: var(--muted); margin-left: 4px; }
.ebox em { font-style: normal; font-size: var(--text-2xs); color: var(--meta); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pm { margin-bottom: var(--space-4); border-radius: var(--radius-md); }
@media (max-width: 1100px) { .kpis { grid-template-columns: repeat(2, 1fr); } }
</style>
