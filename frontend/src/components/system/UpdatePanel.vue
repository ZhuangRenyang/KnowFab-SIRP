<template>
  <div class="panel">
    <div class="p-head"><h3>检查更新</h3>
      <span>默认盯 GitHub Releases，无需配置；国内不稳可换镜像</span></div>
    <div class="row">
      <el-button :loading="checking" @click="check">{{ checking ? '检查中…' : '检查更新' }}</el-button>
      <span class="msg" v-html="checkMsg"></span>
    </div>
    <div v-if="sources.length" class="sources">
      <div class="sname">更新源探测结果</div>
      <div v-for="(s, i) in sources" :key="i" class="sline">
        <span :class="s.ok ? 'ok' : 'bad'">{{ s.ok ? '可用' : '不可用' }}</span>
        {{ s.source }}<template v-if="s.ok">（最新 {{ s.latest }}）</template>
        <template v-else>（{{ s.error || '连接失败' }}）</template>
      </div>
    </div>

    <div class="sect">升级包</div>
    <div class="row">
      <input ref="fileEl" type="file" accept=".zip" class="file" />
      <el-button :loading="uploading" @click="upload">{{ uploading ? '上传中…' : '上传并校验' }}</el-button>
    </div>
    <div class="msg line" :class="{ ok: upOk }">{{ upMsg }}</div>

    <div class="sect">应用与回滚</div>
    <div class="row">
      <el-button type="primary" :disabled="!status?.pending?.present" :loading="applying" @click="apply">
        应用升级（会重启服务）
      </el-button>
      <el-button :disabled="!status?.pending?.present" @click="cancel">取消待应用</el-button>
      <el-button type="danger" plain :disabled="!status?.backups?.length" @click="rollback">回滚到上一版本</el-button>
    </div>
    <div class="msg line">{{ applyMsg }}</div>

    <div class="info">
      升级目录：{{ status?.updateDir || '—' }}<br>
      <template v-if="status?.allowWebRestart">网页可直接重启：<b>已开启</b></template>
      <template v-else>
        网页可直接重启：未开启（安装时用 <code>install.sh --allow-web-restart</code> 可打开）。
        现在是「上传后由管理员执行一条命令」的安全模式。
      </template>
      <template v-if="status?.backups?.length">
        <br>最近备份：{{ status.backups.map((b) => b.dir).join('、') }}
      </template>
    </div>

    <el-collapse class="offline">
      <el-collapse-item title="离线升级说明（服务器不能联网时）" name="off">
        <div class="hint">
          1、在有网的电脑下载升级包 zip；2、本页「上传并校验」；3、点「应用升级」；
          若网页重启未开启，按提示到服务器执行一条命令。全程数据在 MySQL，升级只换程序。
        </div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
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

async function check() {
  checking.value = true
  checkMsg.value = '正在连接更新源…'
  sources.value = []
  try {
    const r = await systemApi.check()
    if (!r.ok) { checkMsg.value = r.message || '检查失败'; return }
    if (r.hasUpdate) {
      checkMsg.value = `<b>发现新版本 ${r.latest}</b>（当前 ${r.currentVersion || ''}）`
        + (r.notes ? `<br>说明：${r.notes}` : '')
        + `<br><el-button type="primary" size="small" data-url="${encodeURIComponent(r.url || '')}" class="dl">下载并校验</el-button>`
    } else {
      checkMsg.value = r.message || ''
    }
    sources.value = r.sources || []
    /* 下载按钮动态绑定（v-html 内无法用 Vue 事件） */
    requestAnimationFrame(() => {
      const btn = document.querySelector('.dl') as HTMLElement | null
      btn?.addEventListener('click', () => download(decodeURIComponent(btn.dataset.url || '')), { once: true })
    })
  } catch (e: any) {
    checkMsg.value = '检查失败：' + (e?.message || '')
  } finally { checking.value = false }
}

async function download(url: string) {
  if (!url) { ElMessage.warning('更新源没给下载地址，请手工下载后用「上传并校验」'); return }
  checkMsg.value = '正在下载（包有几十 MB，慢一点正常）…'
  try {
    const r = await systemApi.download(url)
    if (r && r.ok === false) { checkMsg.value = '下载失败：' + (r.message || ''); return }
    checkMsg.value = `已下载并验签通过（${r.version || ''}），到下面「应用与回滚」里应用。`
    emit('changed')
  } catch (e: any) { checkMsg.value = '下载失败：' + (e?.message || '') }
}

async function upload() {
  const f = fileEl.value?.files?.[0]
  if (!f) { ElMessage.warning('先选一个升级包（.zip）'); return }
  uploading.value = true
  upMsg.value = ''
  try {
    const r = await systemApi.upload(f)
    if (r && r.ok === false) {
      upOk.value = false
      upMsg.value = '上传失败：' + (r.message || '未知原因')
    } else {
      upOk.value = true
      upMsg.value = `校验通过：${r.currentVersion || ''} → ${r.version}（${r.fileCount || 0} 个文件）`
        + (r.notes ? `；${r.notes}` : '')
      emit('changed')
    }
  } catch (e: any) { upOk.value = false; upMsg.value = '上传失败：' + (e?.message || '') }
  finally { uploading.value = false }
}

async function apply() {
  applying.value = true
  applyMsg.value = ''
  try {
    const r = await systemApi.apply()
    if (r && r.ok === false) { applyMsg.value = r.message || '应用失败'; return }
    applyMsg.value = r.message || ''
    if (r.restarting) {
      /* 服务重启中：分三次延时刷新（旧版同款 4s/12s/25s） */
      window.setTimeout(() => emit('changed'), 4000)
      window.setTimeout(() => emit('changed'), 12000)
      window.setTimeout(() => emit('changed'), 25000)
    }
  } catch (e: any) { applyMsg.value = '应用失败：' + (e?.message || '') }
  finally { applying.value = false }
}

async function cancel() {
  try {
    const r = await systemApi.cancel()
    if (r && r.ok === false) { ElMessage.error(r.message || '取消失败'); return }
    applyMsg.value = r.message || '已取消'
    emit('changed')
  } catch (e: any) { ElMessage.error(e?.message || '取消失败') }
}

async function rollback() {
  try {
    await ElMessageBox.confirm('回滚到上一版本？只回滚程序，数据库里的数据不会回退。',
      '回滚', { type: 'warning', confirmButtonText: '回滚', cancelButtonText: '取消' })
  } catch { return }
  try {
    const r = await systemApi.rollback()
    applyMsg.value = (r && r.ok === false) ? (r.message || '回滚失败') : (r.message || '已申请回滚')
    emit('changed')
  } catch (e: any) { applyMsg.value = '回滚失败：' + (e?.message || '') }
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
</style>
