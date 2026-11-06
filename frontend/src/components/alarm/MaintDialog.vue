<template>
  <el-dialog :model-value="modelValue" :title="t('maint.title')" width="720px"
             :close-on-click-modal="false" @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <div v-if="alarm" class="who">
      <b>{{ alarm.deviceName || alarm.deviceCode }}</b>
      <em>{{ alarm.deviceCode }}</em>
      <span class="msg">{{ alarm.message || alarm.ruleName }}</span>
    </div>

    <!-- 分析中：AI 通常 3~8 秒，明确说清，别让人以为卡死 -->
    <div v-if="loading" class="loading">
      <el-icon class="spin"><Loading /></el-icon>
      <span>{{ t('maint.analyzing') }}</span>
    </div>

    <template v-else-if="order">
      <div class="advice">
        <div class="adv-head">
          <!-- 来源必须标出来：本地规则说成 AI 结论就是骗现场 -->
          <el-tag :type="order.aiSource === 'AI' ? 'primary' : 'info'" size="small" effect="light">
            {{ order.aiSource === 'AI' ? t('maint.byAi') : t('maint.byLocal') }}
          </el-tag>
          <span v-if="order.aiSource !== 'AI'" class="hint">{{ t('maint.localHint') }}</span>
          <span class="no">{{ order.orderNo }}</span>
          <el-tag size="small" effect="plain">{{ order.statusText }}</el-tag>
        </div>
        <pre class="text">{{ order.aiAdvice }}</pre>
      </div>

      <!-- 负责人（DH-85）：事前责任人。没派 = 这活没人认领，全靠口头喊 -->
      <div class="assign-row">
        <span class="assign-k">{{ t('maint.assignee') }}</span>
        <el-tag v-if="order.assignee" size="small" type="success" effect="light">
          {{ nameOf(order.assignee) }}
        </el-tag>
        <span v-else class="assign-none">{{ t('maint.unassigned') }}</span>
        <template v-if="canAssign && !isDone">
          <el-select v-model="pick" size="small" :placeholder="t('maint.pickWho')"
                     style="width: 176px" @change="assignTo">
            <el-option v-for="p in people" :key="p.username" :value="p.username"
                       :label="(p.displayName || p.username) + ' · ' + p.role" />
          </el-select>
          <el-button v-if="order.assignee" link type="info" size="small" @click="assignTo('')">
            {{ t('maint.unassign') }}
          </el-button>
        </template>
      </div>

      <!-- 历史样本：闭环有没有用的最直观证据 -->
      <div v-if="history.length" class="hist">
        <div class="h-head">{{ t('maint.historyTitle', { n: history.length }) }}</div>
        <div v-for="h in history" :key="h.orderNo" class="h-row">
          <span class="h-dot"></span>
          <div>
            <div class="h-cause">{{ h.rootCause }}</div>
            <div class="h-meta">
              <span v-if="h.causeCategoryText">{{ h.causeCategoryText }}</span>
              <span v-if="h.solution">{{ h.solution }}</span>
              <span v-if="h.partsReplaced">{{ t('maint.parts') }} {{ h.partsReplaced }}</span>
              <span v-if="h.downtimeMin">{{ t('maint.downtimeMin', { n: h.downtimeMin }) }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 已结案：只读展示回填结果 -->
      <div v-if="order.status === 'RESOLVED'" class="done">
        <div class="h-head">{{ t('maint.resolvedTitle') }}</div>
        <div class="kv"><span>{{ t('maint.causeCategory') }}</span><b>{{ order.causeCategoryText }}</b></div>
        <div class="kv"><span>{{ t('maint.rootCause') }}</span><b>{{ order.rootCause }}</b></div>
        <div v-if="order.solution" class="kv"><span>{{ t('maint.solution') }}</span><b>{{ order.solution }}</b></div>
        <div v-if="order.partsReplaced" class="kv"><span>{{ t('maint.parts') }}</span><b>{{ order.partsReplaced }}</b></div>
        <div v-if="order.downtimeMin" class="kv"><span>{{ t('maint.downtime') }}</span><b>{{ order.downtimeMin }} min</b></div>
      </div>

      <!-- 结案回填表单 -->
      <div v-if="order.status === 'IN_PROGRESS'" class="form">
        <div class="h-head">{{ t('maint.fillTitle') }}</div>
        <div class="hint-line">{{ t('maint.fillHint') }}</div>
        <div class="row">
          <label>{{ t('maint.causeCategory') }}<i>*</i></label>
          <el-select v-model="fm.causeCategory" :placeholder="t('maint.pickCategory')" style="width:200px">
            <el-option v-for="c in categories" :key="c.value" :value="c.value" :label="c.text" />
          </el-select>
        </div>
        <div class="row top">
          <label>{{ t('maint.rootCause') }}<i>*</i></label>
          <el-input v-model="fm.rootCause" type="textarea" :rows="2" :placeholder="t('maint.rootCausePh')" />
        </div>
        <div class="row top">
          <label>{{ t('maint.solution') }}</label>
          <el-input v-model="fm.solution" type="textarea" :rows="2" :placeholder="t('maint.solutionPh')" />
        </div>
        <div class="row">
          <label>{{ t('maint.parts') }}</label>
          <el-input v-model="fm.partsReplaced" style="width:220px" :placeholder="t('maint.partsPh')" />
        </div>
        <div class="row">
          <label>{{ t('maint.downtime') }}</label>
          <el-input v-model.number="fm.downtimeMin" type="number" style="width:120px" /> <em>min</em>
        </div>
      </div>
    </template>

    <div v-else-if="err" class="err">{{ err }}</div>

    <template #footer>
      <el-button @click="close">{{ t('common.close') }}</el-button>
      <template v-if="order">
        <el-button v-if="order.status === 'ANALYZED'" @click="doCancel">{{ t('maint.cancelBtn') }}</el-button>
        <el-button v-if="order.status === 'ANALYZED'" type="primary" @click="doStart">{{ t('maint.startBtn') }}</el-button>
        <el-button v-if="order.status === 'IN_PROGRESS'" type="primary" :loading="saving" @click="doResolve">
          {{ t('maint.resolveBtn') }}
        </el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, reactive, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { maintApi, type MaintAssignee, type MaintOrder, type MaintSample } from '@/api/maint'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()

const props = defineProps<{
  modelValue: boolean
  /** 从报警列表带过来的那条报警 */
  alarm: { id: number; deviceCode: string; deviceName?: string; message?: string; ruleName?: string } | null
}>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; changed: [] }>()

const loading = ref(false)
const saving = ref(false)
const err = ref('')
const order = ref<MaintOrder | null>(null)
const history = ref<MaintSample[]>([])
const categories = ref<{ value: string; text: string }[]>([])

// ---- 派工（DH-85）----
// 前端藏按钮 ≠ 权限：这里只是「不给不能派的人显示下拉」，真正的门在 SecurityConfig（403 兜底）
const auth = useAuthStore()
const people = ref<MaintAssignee[]>([])
const pick = ref('')
const canAssign = computed(() =>
  ['ADMIN', 'MANAGER', 'LEADER'].includes(auth.user?.role || ''))
const isDone = computed(() =>
  order.value?.status === 'RESOLVED' || order.value?.status === 'CANCELLED')

const fm = reactive({
  causeCategory: '',
  rootCause: '',
  solution: '',
  partsReplaced: '',
  downtimeMin: null as number | null
})

function reset() {
  pick.value = ''
  order.value = null
  history.value = []
  err.value = ''
  fm.causeCategory = ''
  fm.rootCause = ''
  fm.solution = ''
  fm.partsReplaced = ''
  fm.downtimeMin = null
}

/** 可选人员：拉一次就够（同一个会话里人员不变） */
async function loadPeople() {
  if (people.value.length) return
  try {
    people.value = await maintApi.assignable()
  } catch { /* 拉不到就不显示下拉，不影响看诊断 */ }
}

/** 指派；传空 = 取消指派 */
async function assignTo(who: string) {
  if (!order.value || isDone.value) return
  try {
    const r = await maintApi.assign(order.value.orderNo, who)
    if (!r.ok) {
      ElMessage.error(r.message || t('common.reqFail'))
      pick.value = ''
      return
    }
    order.value = r.order || order.value
    ElMessage.success(r.message || t('maint.assignOk'))
    emit('changed')
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  }
  pick.value = ''
}

/** 显示姓名，查不到就退回用户名 */
function nameOf(username: string) {
  const p = people.value.find(x => x.username === username)
  return p?.displayName || username
}

async function loadCategories() {
  if (categories.value.length) return
  try {
    const r = await maintApi.categories()
    categories.value = r.categories || []
  } catch { /* 拉不到就用空下拉，不至于卡住整个弹窗 */ }
}

/** 打开就诊断：用户点了「诊断」，就该等结果，不该再点一次 */
watch(() => props.modelValue, async (open) => {
  if (!open || !props.alarm) return
  reset()
  await Promise.all([loadCategories(), loadPeople()])
  loading.value = true
  try {
    const r = await maintApi.analyze(props.alarm.id)
    if (!r.ok) { err.value = r.message || t('common.reqFail'); return }
    order.value = r.order || null
    history.value = r.history || []
    if (r.reused) ElMessage.info(r.message || '')
  } catch (e) {
    err.value = (e as Error)?.message || t('common.reqFail')
  } finally {
    loading.value = false
  }
})

async function doStart() {
  if (!order.value) return
  saving.value = true
  try {
    const r = await maintApi.start(order.value.orderNo)
    if (!r.ok) { ElMessage.error(r.message || t('common.reqFail')); return }
    order.value = r.order || order.value
    emit('changed')
  } finally { saving.value = false }
}

async function doResolve() {
  if (!order.value) return
  if (!fm.causeCategory) { ElMessage.warning(t('maint.needCategory')); return }
  if (!fm.rootCause || fm.rootCause.trim().length < 2) { ElMessage.warning(t('maint.needCause')); return }
  saving.value = true
  try {
    const r = await maintApi.resolve(order.value.orderNo, { ...fm })
    if (!r.ok) { ElMessage.error(r.message || t('common.reqFail')); return }
    order.value = r.order || order.value
    ElMessage.success(t('maint.resolvedOk'))
    emit('changed')
  } finally { saving.value = false }
}

async function doCancel() {
  if (!order.value) return
  try {
    const { value } = await ElMessageBox.prompt(t('maint.cancelWhy'), t('maint.cancelBtn'), {
      inputPlaceholder: t('maint.cancelWhyPh'),
      inputValidator: (v: string) => (!!v && v.trim().length >= 2) || t('maint.needReason')
    })
    const r = await maintApi.cancel(order.value.orderNo, value)
    if (!r.ok) { ElMessage.error(r.message || t('common.reqFail')); return }
    order.value = r.order || order.value
    emit('changed')
  } catch { /* 用户取消输入，什么都不做 */ }
}

function close() {
  emit('update:modelValue', false)
}
</script>

<style scoped>
.assign-row { display: flex; align-items: center; gap: 8px; margin: 4px 0 12px; flex-wrap: wrap; }
.assign-k { font-size: 13px; color: var(--el-text-color-secondary); }
.assign-none { font-size: 13px; color: var(--el-text-color-placeholder); }

.who { display: flex; align-items: center; gap: 8px; font-size: var(--text-sm); margin-bottom: var(--space-3); flex-wrap: wrap; }
.who em { font-style: normal; color: var(--muted); }
.who .msg { color: var(--muted); }
.loading { display: flex; align-items: center; gap: 8px; padding: var(--space-6) 0; color: var(--muted); font-size: var(--text-sm); }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.advice { border: 1px solid var(--line); border-radius: var(--radius-sm); padding: var(--space-3); background: var(--surface-2); }
.adv-head { display: flex; align-items: center; gap: 8px; margin-bottom: var(--space-2); flex-wrap: wrap; }
.adv-head .hint { font-size: var(--text-2xs); color: var(--muted); }
.adv-head .no { margin-left: auto; font-size: var(--text-2xs); color: var(--muted); }
.text { margin: 0; white-space: pre-wrap; font-family: inherit; font-size: var(--text-sm); line-height: 1.8; color: var(--text); }
.hist, .done, .form { margin-top: var(--space-4); }
.h-head { font-size: var(--text-xs); color: var(--muted); letter-spacing: .08em; margin-bottom: var(--space-2); }
.hint-line { font-size: var(--text-2xs); color: var(--muted); margin-bottom: var(--space-2); line-height: 1.7; }
.h-row { display: flex; gap: 8px; padding: var(--space-2) 0; border-bottom: 1px dashed var(--line); font-size: var(--text-sm); }
.h-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--muted); margin-top: 7px; flex: none; }
.h-cause { color: var(--text); }
.h-meta { display: flex; gap: 10px; flex-wrap: wrap; font-size: var(--text-2xs); color: var(--muted); margin-top: 2px; }
.kv { display: flex; gap: 10px; font-size: var(--text-sm); padding: 3px 0; }
.kv span { color: var(--muted); min-width: 72px; }
.kv b { font-weight: normal; color: var(--text); }
.row { display: flex; align-items: center; gap: 10px; margin-bottom: var(--space-2); font-size: var(--text-sm); }
.row.top { align-items: flex-start; }
.row label { color: var(--muted); min-width: 96px; }
.row label i { color: var(--alarm); font-style: normal; margin-left: 2px; }
.row em { font-style: normal; color: var(--muted); font-size: var(--text-2xs); }
.err { color: var(--alarm); font-size: var(--text-sm); padding: var(--space-4) 0; }
</style>
