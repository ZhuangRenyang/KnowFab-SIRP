<template>
  <div class="panel">
    <div class="p-head"><h3>{{ t('mig.title') }}</h3>
      <span>{{ t('mig.summary', { ver: m?.schemaVersion || '0', applied: m?.applied?.length || 0, waiting: m?.waiting || 0 }) }}</span></div>

    <el-alert v-if="m?.failedVersion" type="error" :closable="false" class="fail">
      <template #title>
        <div>
          {{ t('mig.failedPre', { ver: m.failedVersion }) }}{{ m.failedError || t('mig.unknownReason') }}
          <div class="sub">{{ t('mig.failedSub', { dir: m.dir }) }}</div>
        </div>
      </template>
    </el-alert>

    <div v-if="!m?.files?.length" class="empty">{{ t('mig.empty') }}</div>
    <table v-else class="etable">
      <thead><tr><th>{{ t('mig.thVersion') }}</th><th>{{ t('mig.thScript') }}</th><th class="r">{{ t('mig.thStatements') }}</th><th>{{ t('mig.thRisk') }}</th><th class="r">{{ t('mig.thAction') }}</th></tr></thead>
      <tbody>
        <tr v-for="f in m.files" :key="f.version">
          <td class="num">{{ f.version }}</td>
          <td>{{ f.file }}</td>
          <td class="r num">{{ f.statements ?? '—' }}</td>
          <td>
            <span v-if="f.blocked" class="risk bad">{{ t('mig.blocked', { reason: f.blocked }) }}</span>
            <span v-else-if="f.warnings && f.warnings.length" class="risk wn">{{ f.warnings.join('；') }}</span>
            <span v-else class="risk">—</span>
          </td>
          <td class="r"><el-button text type="primary" size="small" @click="showSql(f.version)">{{ t('mig.viewSql') }}</el-button></td>
        </tr>
      </tbody>
    </table>

    <div v-if="sqlView" class="sqlbox">
      <div class="sqlhead">{{ t('mig.sqlHead', { ver: sqlView.version, n: sqlView.list.length }) }}
        <el-button text size="small" @click="sqlView = null">{{ t('mig.collapse') }}</el-button></div>
      <pre>{{ sqlView.list.map((s, i) => `${i + 1}. ${s}`).join('\n') }}</pre>
    </div>

    <div class="sect">{{ t('mig.appliedSect') }}</div>
    <div v-if="!m?.applied?.length" class="empty small">{{ t('mig.appliedEmpty') }}</div>
    <table v-else class="etable">
      <thead><tr><th>{{ t('mig.thVersion') }}</th><th>{{ t('mig.thAt') }}</th><th class="r">{{ t('mig.thStatements') }}</th><th class="r">{{ t('mig.thAttempts') }}</th><th>{{ t('mig.thResult') }}</th></tr></thead>
      <tbody>
        <tr v-for="a in m.applied" :key="a.version">
          <td class="num">{{ a.version }}</td>
          <td class="dim">{{ a.at }}</td>
          <td class="r num">{{ a.statements }}</td>
          <td class="r num">{{ a.attempts }}</td>
          <td><span :class="a.ok ? 'ok' : 'bad'">{{ a.ok ? t('mig.ok') : (a.error || t('mig.fail')) }}</span></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { systemApi, type MigrationsView } from '@/api/system'

const { t } = useI18n()

const m = ref<MigrationsView | null>(null)
const sqlView = ref<{ version: string; list: string[] } | null>(null)

async function load() {
  try { m.value = await systemApi.migrations() }
  catch (e: any) { ElMessage.error(e?.message || t('mig.loadFailed')) }
}

async function showSql(version: string) {
  try {
    const r = await systemApi.migrationSql(version)
    const list = r.statements || r.sql || []
    sqlView.value = { version, list }
  } catch (e: any) { ElMessage.error(e?.message || t('mig.sqlFailed')) }
}

onMounted(load)
</script>

<style scoped>
.fail { margin-bottom: var(--space-3); border-radius: var(--radius-sm); }
.sub { font-size: var(--text-2xs); color: var(--muted); margin-top: 4px; }
.empty { color: var(--muted); font-size: var(--text-sm); padding: var(--space-4); text-align: center; }
.empty.small { padding: var(--space-2); }
.etable { width: 100%; border-collapse: collapse; font-size: var(--text-sm); }
.etable th { text-align: left; font-size: var(--text-2xs); color: var(--meta); font-weight: 400; padding: 6px 8px; }
.etable td { padding: 8px; border-bottom: 1px solid var(--border-soft); }
.etable .r { text-align: right; }
.etable .dim { color: var(--muted); font-size: var(--text-2xs); }
.risk { font-size: var(--text-2xs); color: var(--muted); }
.risk.bad { color: var(--alarm); }
.risk.wn { color: var(--idle); }
.ok { color: var(--run); }
.bad { color: var(--alarm); }
.sqlbox { margin-top: var(--space-3); border: 1px solid var(--border); border-radius: var(--radius-sm); overflow: hidden; }
.sqlhead { display: flex; align-items: center; gap: var(--space-2); padding: 6px 10px; background: var(--surface-2); font-size: var(--text-xs); color: var(--muted); }
.sqlbox pre { margin: 0; padding: var(--space-3); font-size: var(--text-2xs); line-height: 1.8; overflow: auto; max-height: 260px; }
.sect { font-size: var(--text-xs); color: var(--muted); letter-spacing: .1em; margin: var(--space-4) 0 var(--space-2); }
</style>
