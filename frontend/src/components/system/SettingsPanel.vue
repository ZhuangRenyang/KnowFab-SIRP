<template>
  <div class="panel">
    <div class="p-head"><h3>系统设置</h3>
      <span v-if="pendingTip" class="pend">{{ pendingTip }}</span>
    </div>

    <div v-if="!groups.length" class="empty">加载中…</div>
    <div v-for="g in groups" :key="g.name" class="group">
      <div class="gname">{{ g.name }}</div>
      <div v-for="it in g.items" :key="it.key" class="setrow">
        <div class="lab">
          <div class="lt">{{ it.label }}
            <span v-if="it.pendingRestart" class="tag wn">未生效</span>
            <span v-else-if="it.overridden" class="tag ok">已改过</span>
            <span v-else-if="it.scope !== 'LIVE'" class="tag">重启生效</span>
          </div>
          <div class="desc">{{ it.desc }}</div>
          <div class="def">默认：{{ it.defaultValue || '（空）' }}
            <el-button v-if="it.overridden" text type="primary" size="small" @click="reset(it.key)">恢复默认</el-button>
          </div>
        </div>
        <div class="ctl">
          <el-checkbox v-if="it.type === 'BOOL'" :model-value="edits[it.key] === 'true'"
                       @update:model-value="(v: boolean) => (edits[it.key] = v ? 'true' : 'false')" />
          <template v-else-if="it.type === 'INT'">
            <el-input v-model="edits[it.key]" type="number" style="width:140px" />
            <span v-if="it.max > 0" class="range">允许 {{ it.min }} ~ {{ it.max }}</span>
          </template>
          <el-input v-else-if="it.type === 'AREA'" v-model="edits[it.key]" type="textarea" :rows="3" />
          <el-input v-else v-model="edits[it.key]" />
        </div>
      </div>
    </div>

    <div class="savebar">
      <el-button type="primary" :loading="saving" @click="save">保存设置</el-button>
      <span class="msg" :class="{ ok: msgOk }">{{ msg }}</span>
    </div>

    <div class="sect">只能改配置文件（这里故意不给开关）</div>
    <div v-for="r in readonly" :key="r.label" class="ro">
      <b>{{ r.label }}</b><span class="roval num">{{ r.value }}</span>
      <div class="desc">{{ r.why }}</div>
    </div>

    <div class="sect">演示数据</div>
    <div class="purge">
      <el-button v-for="p in PURGE_META" :key="p.kind" type="danger" plain size="small" @click="purge(p)">
        清除{{ p.name.split('（')[0] }}演示数据
      </el-button>
    </div>
    <div class="desc">不可恢复。如果对应演示开关仍开着，下次重启会重新生成，交付前记得关掉并保存。</div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { PURGE_META, systemApi, type PurgeKind, type SettingItem } from '@/api/system'

const groups = ref<{ name: string; items: SettingItem[] }[]>([])
const readonly = ref<{ label: string; value: string; why: string }[]>([])
const edits = reactive<Record<string, string>>({})
const pendingRestart = ref(0)
const saving = ref(false)
const msg = ref('')
const msgOk = ref(false)

const pendingTip = computed(() => pendingRestart.value > 0
  ? `${pendingRestart.value} 项改了但没生效（等重启）` : '')

async function load() {
  try {
    const d = await systemApi.settings()
    groups.value = d.groups || []
    readonly.value = d.readonly || []
    pendingRestart.value = d.pendingRestart || 0
    for (const g of groups.value) for (const it of g.items) edits[it.key] = it.value ?? ''
  } catch (e: any) { ElMessage.error(e?.message || '设置加载失败') }
}

async function save() {
  saving.value = true
  try {
    const r = await systemApi.saveSettings({ ...edits })
    msgOk.value = !(r && r.ok === false)
    msg.value = (r.message || '') +
      ((r.errors || []).length ? '；' + r.errors.join('；') : '') +
      ((r.warnings || []).length ? '；' + r.warnings.join('；') : '')
    if (r && r.ok !== false) await load()
  } catch (e: any) {
    msg.value = '保存失败：' + (e?.message || ''); msgOk.value = false
  } finally { saving.value = false }
}

async function reset(key: string) {
  try {
    const r = await systemApi.resetSetting(key)
    if (r && r.ok === false) { ElMessage.error(r.message || '恢复失败'); return }
    await load()
  } catch (e: any) { ElMessage.error(e?.message || '恢复失败') }
}

async function purge(p: { kind: PurgeKind; name: string; switchKey: string }) {
  try {
    await ElMessageBox.confirm(
      `清除${p.name}的全部演示数据？这个操作不可恢复。如果是客户真实数据，点了就没了。`,
      '清除演示数据', { type: 'warning', confirmButtonText: '确认清除', cancelButtonText: '取消' })
  } catch { return }
  try {
    const r = await systemApi.purge(p.kind)
    if (r && r.ok === false) { ElMessage.error(r.message || '清除失败'); return }
    const sw = groups.value.flatMap((g) => g.items).find((i) => i.key === p.switchKey)
    const stillOn = sw && sw.value === 'true'
    if (stillOn) {
      ElMessageBox.alert('注意：上面的演示数据开关还开着，下次重启会重新生成。交付前把它关掉并保存。',
        '已清除', { type: 'warning', confirmButtonText: '知道了' }).catch(() => {})
    } else {
      ElMessage.success(r.message || '已清除')
    }
    await load()
  } catch (e: any) { ElMessage.error('清除失败：' + (e?.message || '')) }
}

onMounted(load)
</script>

<style scoped>
.pend { color: var(--idle); font-size: var(--text-xs); }
.group { margin-bottom: var(--space-3); }
.gname { font-size: var(--text-xs); color: var(--muted); letter-spacing: .1em; margin: var(--space-3) 0 var(--space-2); }
.setrow { display: grid; grid-template-columns: minmax(0, 1fr) minmax(260px, 42%); gap: var(--space-4); padding: var(--space-3) 0; border-bottom: 1px solid var(--border-soft); }
.lab .lt { font-size: var(--text-sm); font-weight: 500; display: flex; align-items: center; gap: var(--space-2); }
.desc { font-size: var(--text-2xs); color: var(--meta); line-height: 1.7; margin-top: 2px; }
.def { font-size: var(--text-2xs); color: var(--muted); margin-top: 4px; }
.tag { font-size: var(--text-2xs); padding: 1px 7px; border-radius: var(--radius-pill); color: var(--muted); background: var(--surface-2); }
.tag.wn { color: var(--idle); background: var(--idle-soft, rgba(234, 179, 8, .13)); }
.tag.ok { color: var(--run); background: var(--run-soft, rgba(130, 221, 45, .14)); }
.ctl { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.range { font-size: var(--text-2xs); color: var(--meta); }
.savebar { display: flex; align-items: center; gap: var(--space-3); margin: var(--space-4) 0; }
.msg { font-size: var(--text-xs); color: var(--muted); }
.msg.ok { color: var(--run); }
.sect { font-size: var(--text-xs); color: var(--muted); letter-spacing: .1em; margin: var(--space-4) 0 var(--space-2); }
.ro { padding: var(--space-2) 0; border-bottom: 1px solid var(--border-soft); font-size: var(--text-sm); }
.roval { margin-left: var(--space-3); color: var(--fg-2); font-size: var(--text-xs); }
.purge { display: flex; gap: var(--space-2); flex-wrap: wrap; margin-bottom: var(--space-2); }
.empty { color: var(--muted); font-size: var(--text-sm); padding: var(--space-4); text-align: center; }
</style>
