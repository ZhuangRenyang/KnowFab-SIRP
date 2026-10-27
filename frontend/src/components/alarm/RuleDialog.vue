<template>
  <el-dialog :model-value="modelValue" title="告警规则" width="800px"
             :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)"
             @open="open">
    <!-- 列表视图 -->
    <div v-if="view === 'list'">
      <div class="hint lead">
        规则决定「什么情况算报警」。默认只开了「离线」和「报警信号」两条——这两条基本不会误报。
        停机类规则要先想清楚你们厂几点上班、机器正常停多久，不然晚上全厂一起报。
      </div>
      <div v-if="!rules.length" class="empty">还没有任何规则，点下面「新建规则」。</div>
      <div v-for="x in rules" :key="x.rule.id" class="rule-card" :class="{ off: !x.rule.enabled }">
        <div class="body">
          <div class="rn">{{ x.rule.name }}
            <span class="tag" :class="'lv-' + x.rule.level.toLowerCase()">{{ LV[x.rule.level] || x.rule.level }}</span>
          </div>
          <div class="rd">{{ ruleDesc(x.rule) }}</div>
          <div v-if="x.error" class="err">配置有问题：{{ x.error }}</div>
        </div>
        <el-button size="small" @click="toggle(x.rule, !x.rule.enabled)">{{ x.rule.enabled ? '停用' : '启用' }}</el-button>
        <el-button size="small" @click="edit(x.rule)">编辑</el-button>
        <el-button size="small" type="danger" plain @click="remove(x.rule)">删除</el-button>
      </div>
    </div>

    <!-- 编辑视图 -->
    <div v-else class="edit">
      <div class="row">
        <div class="field">
          <label>规则名称</label>
          <el-input v-model="fm.name" placeholder="如 加工中心停机超 10 分钟" />
        </div>
        <div class="field">
          <label>规则类型</label>
          <el-select v-model="fm.type">
            <el-option v-for="(v, k) in RULE_TYPES" :key="k" :label="v" :value="k" />
          </el-select>
        </div>
      </div>

      <div class="row">
        <div class="field">
          <label>适用设备</label>
          <el-input v-model="fm.deviceCodes" placeholder="留空 = 全部设备" />
          <div class="hint">多个用逗号分隔，支持 CNC-* 这种通配</div>
        </div>
        <div class="field">
          <label>级别</label>
          <el-select v-model="fm.level">
            <el-option value="CRITICAL" label="严重（停机、通讯断、报警位）" />
            <el-option value="MAJOR" label="重要（影响产能）" />
            <el-option value="MINOR" label="提示（温度偏高等）" />
          </el-select>
        </div>
      </div>

      <div v-if="showPoint" class="row">
        <div class="field">
          <label>监控点位</label>
          <el-input v-model="fm.pointKey" placeholder="点表里的 key，如 temperature" />
          <div class="hint">{{ pointHint }}</div>
        </div>
        <div v-if="showThreshold" class="field">
          <label>阈值条件</label>
          <div class="cond">
            <el-select v-if="fm.type === 'THRESHOLD'" v-model="fm.op" style="width:86px">
              <el-option v-for="o in OPS" :key="o.v" :label="o.l" :value="o.v" />
            </el-select>
            <el-input v-model="thresholdText" :placeholder="fm.type === 'STOP' ? '0' : '阈值'" style="flex:1" />
          </div>
          <div class="hint">{{ thresholdHint }}</div>
        </div>
      </div>

      <div class="row">
        <div class="field">
          <label>持续时间（秒）</label>
          <el-input v-model.number="fm.forSeconds" type="number" min="0" />
          <div class="hint">条件要连续成立 {{ fmtDur((fm.forSeconds || 0) * 1000) }} 才报警</div>
        </div>
        <div class="field">
          <label>恢复确认（秒）</label>
          <el-input v-model.number="fm.recoverSeconds" type="number" min="0" />
          <div class="hint">条件消失后再观察这么久才判恢复，防止临界值反复跳</div>
        </div>
      </div>

      <div class="row">
        <div class="field">
          <label>冷却时间（秒）</label>
          <el-input v-model.number="fm.cooldownSeconds" type="number" min="0" />
          <div class="hint">恢复后这段时间内不再重复报同一台</div>
        </div>
        <div class="field">
          <label>生效时段</label>
          <el-input v-model="fm.schedule" placeholder="留空 = 全天，如 08:00-20:00" />
          <div class="hint">支持跨零点，如 20:00-06:00（夜班）</div>
        </div>
      </div>

      <div class="field wide">
        <label>生效星期</label>
        <div class="days">
          <el-checkbox v-for="(n, i) in DAY_NAMES" :key="n" v-model="daySel[i]" :label="`周${n}`" :value="String(i + 1)" border size="small" />
        </div>
        <div class="hint">全不勾 = 每天。<b>必须勾</b>，否则这条规则不会触发</div>
      </div>

      <div class="field wide">
        <label>消息模板（可选）</label>
        <el-input v-model="fm.message" placeholder="留空用默认文案" />
        <div class="hint">可用变量：{device} 设备名 · {code} 编码 · {value} 当前值 · {threshold} 阈值 · {duration} 持续时间 · {point} 点位</div>
      </div>

      <div class="checks">
        <el-checkbox v-model="fm.notify">发通知到手机</el-checkbox>
        <el-checkbox v-model="fm.enabled">启用这条规则</el-checkbox>
      </div>
    </div>

    <template #footer>
      <template v-if="view === 'list'">
        <el-button @click="emit('update:modelValue', false)">关闭</el-button>
        <el-button type="primary" @click="create">+ 新建规则</el-button>
      </template>
      <template v-else>
        <el-button @click="emit('update:modelValue', false)">关闭</el-button>
        <el-button @click="view = 'list'">返回列表</el-button>
        <el-button type="primary" @click="save">保存规则</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  alarmsApi, DAY_NAMES, LV, RULE_TYPES,
  fmtDur, newRule, ruleDesc,
  type AlarmRule
} from '@/api/alarms'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'changed'): void }>()

const view = ref<'list' | 'edit'>('list')
const rules = ref<{ rule: AlarmRule; error?: string }[]>([])
const fm = reactive<AlarmRule>(newRule())
const daySel = ref<boolean[]>([false, false, false, false, false, false, false])
const editingId = ref<string | null>(null)
const thresholdText = ref('')

const OPS = [
  { v: '>', l: '>' }, { v: '>=', l: '≥' }, { v: '<', l: '<' },
  { v: '<=', l: '≤' }, { v: '==', l: '=' }, { v: '!=', l: '≠' }
]

const showPoint = computed(() => fm.type === 'THRESHOLD' || fm.type === 'FLAG')
const showThreshold = computed(() => fm.type === 'STOP' || fm.type === 'THRESHOLD')
const pointHint = computed(() =>
  fm.type === 'FLAG' ? '留空 = 设备上任何标了 alarm 的点都算' : '点表里的 key，比如 temperature、current')
const thresholdHint = computed(() =>
  fm.type === 'STOP' ? '状态点等于这个值算停机，一般填 0' : '超过就报警')

watch(() => props.modelValue, (v) => { if (v) open() })

async function open() {
  view.value = 'list'
  try {
    const r = await alarmsApi.rules()
    rules.value = r.items || []
  } catch (e: any) { ElMessage.error(e?.message || '规则加载失败') }
}

function fill(r: AlarmRule) {
  Object.assign(fm, JSON.parse(JSON.stringify(r)))
  editingId.value = r.id ?? null
  const set = new Set(String(r.days || '').split(',').map((s) => s.trim()))
  daySel.value = DAY_NAMES.map((_, i) => set.has(String(i + 1)))
  thresholdText.value = r.threshold === null || r.threshold === undefined ? '' : String(r.threshold)
}

function create() {
  fill(newRule())
  view.value = 'edit'
}

function edit(r: AlarmRule) {
  fill(r)
  view.value = 'edit'
}

function readForm(): AlarmRule {
  const r: AlarmRule = { ...fm, id: editingId.value }
  r.name = fm.name.trim()
  r.deviceCodes = fm.deviceCodes.trim()
  r.pointKey = showPoint.value ? fm.pointKey.trim() : ''
  r.schedule = fm.schedule.trim()
  r.message = fm.message.trim()
  r.days = daySel.value.map((on, i) => (on ? String(i + 1) : '')).filter(Boolean).join(',')
  const th = thresholdText.value.trim()
  if (fm.type === 'STOP') r.threshold = th === '' ? 0 : Number(th)
  else if (fm.type === 'THRESHOLD') r.threshold = th === '' ? null : Number(th)
  else r.threshold = null
  for (const k of ['forSeconds', 'recoverSeconds', 'cooldownSeconds'] as const) {
    const v = Number(fm[k])
    r[k] = Number.isFinite(v) && v >= 0 ? Math.round(v) : 0
  }
  return r
}

async function save() {
  const r = readForm()
  if (!r.name) { ElMessage.warning('给规则起个名字，比如「加工中心停机超 10 分钟」'); return }
  if (r.type === 'THRESHOLD' && (r.threshold === null || !Number.isFinite(r.threshold))) {
    ElMessage.warning('数值越限规则要填阈值，不然它永远不会触发'); return
  }
  try {
    const res = await alarmsApi.saveRule(r)
    if (res && res.ok === false) { ElMessage.error(res.message || '保存失败'); return }
    view.value = 'list'
    await open()
    emit('changed')
  } catch (e: any) { ElMessage.error(e?.message || '保存失败') }
}

async function toggle(rule: AlarmRule, enabled: boolean) {
  try {
    const copy: AlarmRule = JSON.parse(JSON.stringify(rule))
    copy.enabled = enabled
    const res = await alarmsApi.saveRule(copy)
    if (res && res.ok === false) { ElMessage.error(res.message || '操作失败'); return }
    await open()
    emit('changed')
  } catch (e: any) { ElMessage.error(e?.message || '操作失败') }
}

async function remove(rule: AlarmRule) {
  try {
    await ElMessageBox.confirm(`删除规则「${rule.name}」？已产生的告警记录会保留。`,
      '删除规则', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch { return }
  try {
    await alarmsApi.deleteRule(String(rule.id))
    await open()
    emit('changed')
  } catch (e: any) { ElMessage.error(e?.message || '删除失败') }
}
</script>

<style scoped>
.hint { font-size: var(--text-2xs); color: var(--meta); line-height: 1.8; }
.hint.lead { margin-bottom: var(--space-3); }
.empty { color: var(--muted); font-size: var(--text-sm); padding: var(--space-5); text-align: center; }
.rule-card {
  display: flex; gap: var(--space-2); align-items: center;
  background: var(--surface-2); border-radius: var(--radius-sm);
  padding: var(--space-3); margin-bottom: var(--space-2);
}
.rule-card.off { opacity: .5; }
.rule-card .body { flex: 1; min-width: 0; }
.rn { font-size: var(--text-sm); font-weight: 600; }
.rd { font-size: var(--text-xs); color: var(--muted); margin-top: 2px; }
.err { font-size: var(--text-2xs); color: var(--alarm); margin-top: 2px; }
.tag { padding: 1px 7px; border-radius: var(--radius-pill); font-size: var(--text-2xs); margin-left: 6px; }
.lv-critical { color: var(--alarm); background: var(--alarm-soft, rgba(255, 90, 80, .11)); }
.lv-major { color: var(--idle); background: var(--idle-soft, rgba(234, 179, 8, .13)); }
.lv-minor { color: var(--stop); background: var(--stop-soft, rgba(100, 116, 139, .14)); }
.edit .row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); }
.edit .field { margin-bottom: var(--space-3); min-width: 0; }
.edit .field.wide { grid-column: 1 / -1; }
.edit label { display: block; font-size: var(--text-xs); color: var(--muted); margin-bottom: 4px; }
.cond { display: flex; gap: var(--space-2); }
.days { display: flex; gap: var(--space-2); flex-wrap: wrap; }
.checks { display: flex; gap: var(--space-5); margin-top: var(--space-2); }
</style>
