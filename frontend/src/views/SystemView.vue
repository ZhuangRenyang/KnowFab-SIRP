<template>
  <div class="system">
    <div class="kpis">
      <div class="ebox"><span>{{ t('sysHead.version') }}</span><b class="num">{{ st?.version || '--' }}</b>
        <em>{{ t('sysHead.buildTime') }}{{ st?.buildTime || '--' }}</em></div>
      <div class="ebox"><span>{{ t('sysHead.lastUpgrade') }}</span>
        <b class="num">{{ st?.applied?.version || t('sysHead.firstInstall') }}</b>
        <em>{{ st?.applied?.at || t('sysHead.schemaVer', { ver: st?.schemaVersion || '0' }) }}</em></div>
      <div class="ebox"><span>{{ t('sysHead.pending') }}</span>
        <b class="num">{{ st?.pending?.present ? (st.pending.toVersion || t('sysHead.yes')) : t('sysHead.none') }}</b>
        <em>{{ st?.pending?.present ? t('sysHead.fromUpgrade', { ver: st.pending.fromVersion || '--' }) : t('sysHead.pendingHint') }}</em></div>
      <div class="ebox"><span>{{ t('sysHead.backups') }}</span><b class="num">{{ st?.backups?.length || 0 }}<i>{{ t('sysHead.unit') }}</i></b>
        <em>{{ t('sysHead.backupHint') }}</em></div>
    </div>

    <el-alert v-if="pendingMsg" :title="pendingMsg" type="success" show-icon :closable="false" class="pm" />

    <SettingsPanel />
    <DbPasswordPanel />
    <UpdatePanel :status="st" @changed="load" />
    <MigrationsPanel />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { systemApi, type UpdateStatus } from '@/api/system'
import SettingsPanel from '@/components/system/SettingsPanel.vue'
import DbPasswordPanel from '@/components/system/DbPasswordPanel.vue'
import UpdatePanel from '@/components/system/UpdatePanel.vue'
import MigrationsPanel from '@/components/system/MigrationsPanel.vue'

const { t } = useI18n()

const st = ref<UpdateStatus | null>(null)

const pendingMsg = computed(() => {
  const p = st.value?.pending
  if (!p?.present) return ''
  return t('sysHead.pendingMsg', { ver: p.toVersion || '', notes: p.notes || t('sysHead.noNotes') })
})

async function load() {
  try { st.value = await systemApi.status() }
  catch (e: any) { ElMessage.error(e?.message || t('sysHead.loadFailed')) }
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
