<template>
  <div v-if="d" class="detail">
    <div class="d-head">
      <div>
        <div class="d-name">
          {{ d.name }}
          <StatusBadge :type="s.type" :label="t(s.labelKey)" :dot="s.type === 'run'" />
        </div>
        <div class="d-meta num">
          {{ d.code }} · {{ d.protocol }}<template v-if="d.location"> · {{ d.location }}</template>
          <template v-if="d.pollIntervalMs"> · {{ t('device.pollInterval', { ms: d.pollIntervalMs }) }}</template>
        </div>
      </div>
      <div v-if="auth.canWrite" class="d-actions">
        <el-button size="small" @click="emit('edit', d.code)">{{ t('device.editConfig') }}</el-button>
        <el-button size="small" type="danger" plain @click="removeDevice">{{ t('device.delete') }}</el-button>
      </div>
    </div>

    <div class="keybar">
      <template v-if="!d.apiKey">
        <span class="off">{{ t('device.apiDisabled') }}</span>
        <el-button size="small" @click="resetKey">{{ t('device.genKey') }}</el-button>
      </template>
      <template v-else>
        <span>API Key</span>
        <code class="num">{{ d.apiKey }}</code>
        <el-button size="small" @click="copyKey(d.apiKey)">{{ t('device.copy') }}</el-button>
        <el-button size="small" @click="resetKey">{{ t('device.reset') }}</el-button>
        <el-button v-if="d.apiKeyEnabled" size="small" type="danger" plain @click="toggleKey(false)">{{ t('device.disable') }}</el-button>
        <template v-else>
          <el-button size="small" @click="toggleKey(true)">{{ t('device.enable') }}</el-button>
          <span class="off">{{ t('device.disabledTip') }}</span>
        </template>
      </template>
    </div>

    <div class="pts">
      <div v-for="p in d.points" :key="p.pointKey ?? p.key" class="pt" :class="{ bad: p.quality !== 'GOOD' }">
        <!-- 产量点位只显示「今日产量」：老厂 PLC 寄存器里是几十万的历史累计，
             直接当产量展示会误导 -->
        <span>{{ p.role === 'count' ? t('device.todayOutput') : p.name }}</span>
        <b class="num">
          <template v-if="p.role === 'count'">
            {{ fmt(d.todayOutput ?? 0) + (p.unit || '') }}
          </template>
          <template v-else>{{ p.quality === 'GOOD' ? fmt(p.value) + (p.unit || '') : '--' }}</template>
        </b>
      </div>
    </div>

    <!-- 远程控制：只显示点表里显式勾了「可写」的点位（默认只读，没配就看不到这块） -->
    <div v-if="writablePoints.length" class="ctrl">
      <div class="ctrl-head">
        {{ t('device.remoteControl') }}
        <span class="ctrl-hint">{{ t('device.ctrlHint') }}</span>
      </div>
      <div v-for="p in writablePoints" :key="'w-' + (p.pointKey ?? p.key)" class="ctrl-row">
        <span class="ctrl-name">{{ p.name }}</span>
        <span class="ctrl-addr">{{ t('device.address', { addr: p.address }) }}</span>
        <template v-if="p.functionCode === 1">
          <el-button size="small" type="success" plain @click="doWrite(p, 1)">{{ t('device.on') }}</el-button>
          <el-button size="small" plain @click="doWrite(p, 0)">{{ t('device.off') }}</el-button>
        </template>
        <template v-else>
          <el-input-number v-model="writeVal[p.pointKey ?? p.key]" :min="0" :max="65535"
                           size="small" controls-position="right" style="width: 130px" />
          <el-button size="small" type="primary" plain @click="doWrite(p, writeVal[p.pointKey ?? p.key])">
            {{ t('device.write') }}
          </el-button>
        </template>
      </div>
    </div>

    <!-- key=设备编码：切换设备时销毁重建。没有 key 的话，先打开一台无数值点的
         老设备（v-if 让 el 不存在）再切到新设备，onMounted 里的 init 已错过、
         图表永远空白——这已经是本项目第四次踩"图表 div 未挂载就 init"的坑 -->
    <PointTrend :device="d" :key="d.code" />
    <ProdStats :code="d.code" />

    <div v-if="d.lastError" class="last-err">{{ t('device.lastError', { msg: d.lastError }) }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useDevicesStore, statusOf, fmt } from '@/stores/devices'
import { devicesApi } from '@/api/devices'
import StatusBadge from '@/components/StatusBadge.vue'
import PointTrend from '@/components/PointTrend.vue'
import ProdStats from '@/components/ProdStats.vue'

const { t } = useI18n()

const props = defineProps<{ code: string }>()
const emit = defineEmits<{ edit: [code: string]; removed: [code: string] }>()

const auth = useAuthStore()
const store = useDevicesStore()

const d = computed(() => store.list.find((x: any) => x.code === props.code))
const s = computed(() => (d.value ? statusOf(d.value) : { v: -1, label: '--', labelKey: 'status.unknown', type: 'off' as const }))

/* ---------- 远程控制 ---------- */
const writeVal = ref<Record<string, number>>({})

/** 只有显式「可写」且是线圈/保持寄存器的点位才出现在控制区 */
const writablePoints = computed(() =>
  ((d.value?.points as any[]) || []).filter(
    (p) => p.writable && (p.functionCode === 1 || p.functionCode === 3)
  )
)

async function doWrite(p: any, value: number) {
  if (!d.value) return
  const key = p.pointKey ?? p.key
  const label = p.functionCode === 1 ? (value === 1 ? t('device.on') : t('device.off')) : String(value)
  try {
    await ElMessageBox.confirm(
      t('device.confirmWrite', { device: d.value.name, point: p.name, value: label }),
      t('device.confirmWriteTitle'),
      { type: 'warning', confirmButtonText: t('device.confirmWriteOk'), cancelButtonText: t('common.cancel') }
    )
  } catch { return }
  try {
    const r = await devicesApi.write(d.value.code, key, value)
    ElMessage[r?.ok === false ? 'error' : 'success'](
      r?.message || (r?.ok === false ? t('device.writeFailed') : t('device.writeOk'))
    )
  } catch (e: any) {
    ElMessage.error(e?.message || t('device.writeFailed'))
  }
}

/* 危险操作 confirm 文案与旧版逐字一致（PRD 风险 8） */
async function removeDevice() {
  if (!d.value) return
  try {
    await ElMessageBox.confirm(
      t('device.confirmDelete', { name: d.value.name, code: d.value.code }),
      t('device.confirmDeleteTitle'),
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
      ElMessage.success(t('device.copied'))
    } else {
      window.prompt(t('device.copyManual'), key)
    }
  } catch {
    window.prompt('手动复制：', key)
  }
}

async function resetKey() {
  if (!d.value) return
  try {
    await ElMessageBox.confirm(t('device.confirmResetKey'), t('device.resetKeyTitle'), { type: 'warning' })
  } catch { return }
  const r = await devicesApi.resetKey(d.value.code)
  if (!r.ok) { ElMessage.error(r.message || t('device.resetFailed')); return }
  await ElMessageBox.alert(t('device.newKeyBody', { key: r.apiKey }), t('device.newKeyTitle'))
  await store.reloadAll()
}

async function toggleKey(enabled: boolean) {
  if (!d.value) return
  const r = await devicesApi.toggleKey(d.value.code, enabled)
  if (!r.ok) { ElMessage.error(r.message || t('device.opFailed')); return }
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

/* 远程控制区 */
.ctrl {
  margin-top: var(--space-3); padding: 10px 12px; border-radius: var(--radius-md);
  background: var(--surface-2); border: 1px solid var(--line, var(--border));
}
.ctrl-head {
  font-size: var(--text-sm); font-weight: 600; margin-bottom: 8px;
  display: flex; align-items: baseline; gap: 8px;
}
.ctrl-hint { font-size: var(--text-2xs); color: var(--muted); font-weight: 400; }
.ctrl-row { display: flex; align-items: center; gap: 8px; padding: 4px 0; }
.ctrl-name { font-size: var(--text-sm); min-width: 90px; }
.ctrl-addr { font-size: var(--text-2xs); color: var(--muted); flex: 1; }
.last-err { margin-top: var(--space-3); color: var(--alarm); font-size: var(--text-sm); }
</style>
