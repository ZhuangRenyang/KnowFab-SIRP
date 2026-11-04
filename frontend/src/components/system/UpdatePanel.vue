<template>
  <div class="panel">
    <div class="p-head"><h3>{{ t('upd.title') }}</h3>
      <span>{{ t('upd.hint') }}</span></div>
    <div class="row">
      <el-button :loading="checking" @click="check">{{ checking ? t('upd.checking') : t('upd.check') }}</el-button>
      <span class="msg" v-html="checkMsg"></span>
    </div>
    <div v-if="sources.length" class="sources">
      <div class="sname">{{ t('upd.sourcesTitle') }}</div>
      <div v-for="(s, i) in sources" :key="i" class="sline">
        <span :class="s.ok ? 'ok' : 'bad'">{{ s.ok ? t('upd.available') : t('upd.unavailable') }}</span>
        {{ s.source }}<template v-if="s.ok">{{ t('upd.latestSuffix', { ver: s.latest }) }}</template>
        <template v-else>{{ t('upd.connFailedWrap', { err: s.error || t('upd.connFailed') }) }}</template>
      </div>
    </div>

    <div class="sect">{{ t('upd.pkgSect') }}</div>
    <div class="row">
      <input ref="fileEl" type="file" accept=".zip" class="file" />
      <el-button :loading="uploading" @click="upload">{{ uploading ? t('upd.uploading') : t('upd.upload') }}</el-button>
    </div>
    <div class="msg line" :class="{ ok: upOk }">{{ upMsg }}</div>

    <div class="sect">{{ t('upd.applySect') }}</div>
    <div class="row">
      <el-button type="primary" :disabled="!status?.pending?.present" :loading="applying" @click="apply">
        {{ t('upd.apply') }}
      </el-button>
      <el-button :disabled="!status?.pending?.present" @click="cancel">{{ t('upd.cancelPending') }}</el-button>
      <el-button type="danger" plain :disabled="!status?.backups?.length" @click="rollback">{{ t('upd.rollback') }}</el-button>
    </div>
    <div class="msg line">{{ applyMsg }}</div>

    <div class="info">
      {{ t('upd.updateDir') }}{{ status?.updateDir || '—' }}<br>
      <template v-if="status?.allowWebRestart">{{ t('upd.webRestart') }}<b>{{ t('upd.on') }}</b></template>
      <template v-else>
        {{ t('upd.webRestartOff') }}
      </template>
      <template v-if="status?.backups?.length">
        <br>{{ t('upd.lastBackup') }}{{ status.backups.map((b) => b.dir).join(t('upd.backupSep')) }}
      </template>
    </div>

    <el-collapse class="offline">
      <el-collapse-item :title="t('upd.offlineTitle')" name="off">
        <div class="hint">
          {{ t('upd.offlineBody') }}
        </div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'

const { t } = useI18n()
import { systemApi, type CheckSource, type UpdateStatus } from '@/api/system'

const props = defineProps<{ status: UpdateStatus | null }>()
const emit = defineEmits<{ (e: 'changed'): void }>()

const checking = ref(false)
const fileEl = ref<HTMLInputElement>()
const uploading = ref(false)
const applying = ref(false)
const checkMsg = ref('')
const sources = ref<CheckSource[]>([])
const upMsg = ref('')
const upOk = ref(false)
const applyMsg = ref('')

/**
 * @param force true = 绕过服务端 10 分钟缓存，真的重新联网探测
 *
 * 默认走缓存：连不上的源每次都要等满超时（8 秒/源），而版本号不会每分钟变。
 * 缓存命中时明确告诉用户「N 分钟前查过」，并给一个「重新检查」的出口——
 * **不能让用户以为这是刚查的**，那是另一种形式的假数据。
 */
async function check(force = false) {
  checking.value = true
  checkMsg.value = t('upd.connecting')
  sources.value = []
  try {
    const r = await systemApi.check(force)
    if (!r.ok) { checkMsg.value = r.message || t('upd.checkFailed'); return }
    // 缓存提示：秒数 -> 人话
    const age = r.cached
      ? (r.cacheAgeSec && r.cacheAgeSec >= 60
          ? t('upd.cachedMinAgo', { n: Math.floor(r.cacheAgeSec / 60) })
          : t('upd.cachedJustNow'))
      : ''
    const cacheHint = r.cached
      ? `<br><span class="cache-hint">${age} · `
        + `<el-button link type="primary" size="small" class="recheck">${t('upd.recheck')}</el-button></span>`
      : ''
    if (r.hasUpdate) {
      checkMsg.value = '<b>' + t('upd.foundNew', { ver: r.latest, cur: r.currentVersion || '' }) + '</b>'
        + (r.notes ? '<br>' + t('upd.notesPrefix') + r.notes : '')
        + `<br><el-button type="primary" size="small" data-url="${encodeURIComponent(r.url || '')}"`
        + ` data-sha="${encodeURIComponent(r.sha256 || '')}" class="dl">${t('upd.downloadBtn')}</el-button>`
        + cacheHint
    } else {
      checkMsg.value = (r.message || '') + cacheHint
    }
    sources.value = r.sources || []
    /* 下载按钮与「重新检查」动态绑定（v-html 内无法用 Vue 事件） */
    requestAnimationFrame(() => {
      const btn = document.querySelector('.dl') as HTMLElement | null
      btn?.addEventListener('click', () => download(
        decodeURIComponent(btn.dataset.url || ''),
        decodeURIComponent(btn.dataset.sha || '')), { once: true })

      const re = document.querySelector('.recheck') as HTMLElement | null
      re?.addEventListener('click', () => check(true), { once: true })
    })
  } catch (e: any) {
    checkMsg.value = t('upd.checkFailed') + '：' + (e?.message || '')
  } finally { checking.value = false }
}

async function download(url: string, sha256?: string) {
  if (!url) { ElMessage.warning(t('upd.noUrl')); return }
  checkMsg.value = t('upd.downloading')
  try {
    // sha256 来自 check 结果（version.json 声明），后端下载完先比对再验签
    const r = await systemApi.download(url, sha256)
    if (r && r.ok === false) { checkMsg.value = t('upd.downloadFailed') + (r.message || ''); return }
    checkMsg.value = t('upd.downloaded', { ver: r.version || '' })
    emit('changed')
  } catch (e: any) { checkMsg.value = t('upd.downloadFailed') + (e?.message || '') }
}

async function upload() {
  const f = fileEl.value?.files?.[0]
  if (!f) { ElMessage.warning(t('upd.pickZip')); return }
  uploading.value = true
  upMsg.value = ''
  try {
    const r = await systemApi.upload(f)
    if (r && r.ok === false) {
      upOk.value = false
      upMsg.value = t('upd.uploadFailed') + (r.message || t('upd.unknownReason'))
    } else {
      upOk.value = true
      upMsg.value = t('upd.verifyPassed', { from: r.currentVersion || '', to: r.version, n: r.fileCount || 0 })
        + (r.notes ? `；${r.notes}` : '')
      emit('changed')
    }
  } catch (e: any) { upOk.value = false; upMsg.value = t('upd.uploadFailed') + (e?.message || '') }
  finally { uploading.value = false }
}

async function apply() {
  applying.value = true
  applyMsg.value = ''
  try {
    const r = await systemApi.apply()
    if (r && r.ok === false) { applyMsg.value = r.message || t('upd.applyFailed'); return }
    applyMsg.value = r.message || ''
    if (r.restarting) {
      /* 服务重启中：分三次延时刷新（旧版同款 4s/12s/25s） */
      window.setTimeout(() => emit('changed'), 4000)
      window.setTimeout(() => emit('changed'), 12000)
      window.setTimeout(() => emit('changed'), 25000)
    }
  } catch (e: any) { applyMsg.value = t('upd.applyFailed') + '：' + (e?.message || '') }
  finally { applying.value = false }
}

async function cancel() {
  try {
    const r = await systemApi.cancel()
    if (r && r.ok === false) { ElMessage.error(r.message || t('upd.cancelFailed')); return }
    applyMsg.value = r.message || t('upd.cancelled')
    emit('changed')
  } catch (e: any) { ElMessage.error(e?.message || t('upd.cancelFailed')) }
}

async function rollback() {
  try {
    await ElMessageBox.confirm(t('upd.rollbackConfirm'),
      t('upd.rollbackTitle'), { type: 'warning', confirmButtonText: t('upd.rollbackBtn'), cancelButtonText: t('upd.cancelBtn') })
  } catch { return }
  try {
    const r = await systemApi.rollback()
    applyMsg.value = (r && r.ok === false) ? (r.message || t('upd.rollbackFailed')) : (r.message || t('upd.rollbackRequested'))
    emit('changed')
  } catch (e: any) { applyMsg.value = t('upd.rollbackFailed') + '：' + (e?.message || '') }
}
</script>

<style scoped>
.row { display: flex; gap: var(--space-3); align-items: center; flex-wrap: wrap; margin-bottom: var(--space-2); }
.msg { font-size: var(--text-xs); color: var(--muted); line-height: 1.8; }
.msg.ok { color: var(--run); }
.msg.line { min-height: 18px; margin-bottom: var(--space-2); }
.msg :deep(b) { color: var(--fg); }
.file { font-size: var(--text-xs); color: var(--muted); }
.sources { margin: var(--space-2) 0; font-size: var(--text-2xs); color: var(--muted); }
.sname { color: var(--meta); margin-bottom: 4px; }
.sline { line-height: 1.9; }
.ok { color: var(--run); }
.bad { color: var(--alarm); }
.sect { font-size: var(--text-xs); color: var(--muted); letter-spacing: .1em; margin: var(--space-4) 0 var(--space-2); }
.info { font-size: var(--text-2xs); color: var(--muted); line-height: 1.9; padding: var(--space-3); background: var(--surface-2); border-radius: var(--radius-sm); }
.info code { background: var(--surface-3); padding: 1px 5px; border-radius: 4px; }
.offline { margin-top: var(--space-4); }
.hint { font-size: var(--text-xs); color: var(--muted); line-height: 1.9; }

/* 缓存提示：不能让它看起来像"刚查到的"——"3 分钟前查过"必须能一眼看到 */
.cache-hint { font-size: var(--text-2xs); color: var(--meta); }

/* 缓存提示：不能让它看起来像"刚查到的"——"3 分钟前查过"必须能一眼看到 */
.cache-hint { font-size: var(--text-2xs); color: var(--meta); }
</style>
