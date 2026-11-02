<template>
  <div class="page">
    <el-card shadow="never" class="mb">
      <template #header>
        <div class="card-head">
          <span>{{ t('audit.title') }}</span>
          <span class="hint">{{ t('audit.lead') }}</span>
        </div>
      </template>

      <div class="toolbar">
        <el-date-picker
          v-model="range" type="daterange" unlink-panels range-separator="→"
          :start-placeholder="t('audit.rangeStart')" :end-placeholder="t('audit.rangeEnd')"
          value-format="YYYY-MM-DD" style="width:240px" :disabled="loading" @change="reload"
        />
        <el-select v-model="category" :placeholder="t('audit.allCategories')" clearable
          style="width:146px" :disabled="loading" @change="reload">
          <el-option v-for="c in CATEGORIES" :key="c" :label="t(catKey(c))" :value="c" />
        </el-select>
        <el-input
          v-model="keyword" :placeholder="t('audit.keywordPh')" clearable :prefix-icon="Search"
          style="width:290px" :disabled="loading" @keyup.enter="reload" @clear="reload"
        />
        <el-button type="primary" :icon="Search" :loading="loading" @click="reload">
          {{ t('audit.query') }}
        </el-button>
        <el-button :icon="RefreshLeft" :disabled="loading" @click="reset">
          {{ t('audit.reset') }}
        </el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>{{ t('audit.listTitle') }}</span>
          <span class="hint num">{{ t('audit.count', { n: rows.length }) }}</span>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" stripe max-height="620">
        <el-table-column :label="t('audit.colTime')" width="148">
          <template #default="{ row }">
            <span class="num">{{ dt(row.at) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('audit.colCategory')" width="92">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ t(catKey(row.category)) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('audit.colAction')" width="124">
          <template #default="{ row }">{{ t(actKey(row.action)) }}</template>
        </el-table-column>
        <el-table-column :label="t('audit.colTarget')" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ targetLabel(row) }}</span>
            <span v-if="targetCode(row)" class="num dim ml6">{{ targetCode(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('audit.colDetail')" min-width="340">
          <template #default="{ row }">
            <el-tooltip v-if="row.detail" placement="top" :show-after="200">
              <template #content>
                <span class="tip-text">{{ row.detail }}</span>
              </template>
              <div class="detail">{{ row.detail }}</div>
            </el-tooltip>
            <span v-else class="dim">--</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('audit.colOperator')" width="112" show-overflow-tooltip>
          <template #default="{ row }"><span>{{ row.operator || '--' }}</span></template>
        </el-table-column>
        <el-table-column :label="t('audit.colIp')" width="132">
          <template #default="{ row }"><span class="num dim">{{ row.ip || '--' }}</span></template>
        </el-table-column>
        <el-table-column :label="t('audit.colResult')" width="88">
          <template #default="{ row }">
            <el-tooltip :content="row.message" :disabled="row.ok || !row.message" placement="top">
              <el-tag size="small" :type="row.ok ? 'success' : 'danger'">
                {{ row.ok ? t('audit.ok') : t('audit.fail') }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>

        <!-- 空状态走 #empty 插槽：替换掉表格自带的「暂无数据」（自带文案是硬编码中文，英文界面会露馅）。
             插槽里必须始终渲染一个真实元素：Vue 的 renderSlot 在插槽只产出注释节点（v-if 为假）时
             会回落到默认插槽，自带的「暂无数据」就又冒出来了。所以外层留个空 div 占位，
             加载中不显示空状态（避免遮罩后面透出一句「没有记录」）。 -->
        <template #empty>
          <div class="empty-slot">
            <el-empty v-if="!loading" :description="t('audit.empty')" />
          </div>
        </template>
      </el-table>

      <div v-if="rows.length >= limit" class="limit-tip">{{ t('audit.limitHint', { n: limit }) }}</div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { RefreshLeft, Search } from '@element-plus/icons-vue'
import {
  AUDIT_CATEGORIES, AUDIT_LIMIT, actKey, catKey, fetchAudit,
  type AuditCategory, type AuditRow
} from '@/api/audit'
import { todayStr } from '@/api/reports'
import { dt } from '@/utils/format'

const { t } = useI18n()

const CATEGORIES = AUDIT_CATEGORIES
const limit = AUDIT_LIMIT

const rows = ref<AuditRow[]>([])
const loading = ref(false)
/** 默认看最近 7 天：与后端 AuditService 的默认区间一致 */
const range = ref<[string, string] | null>([todayStr(-6), todayStr(0)])
const category = ref<AuditCategory | ''>('')
const keyword = ref('')

/* 对象列显示名。
   后端 targetName 常常已经带出 target（用户：target=「zhaolq」、targetName=「zhaolq（班组长）」；
   告警规则：target=规则 id、targetName=规则名），targetName 为空时用 target 顶上。
   再单独把 target 显示一遍就是重复，所以只有 targetName 没带出 target 时才补标识。 */
function targetLabel(row: AuditRow): string {
  return row.targetName || row.target || '--'
}
function targetCode(row: AuditRow): string {
  const code = (row.target || '').trim()
  if (!code) return ''
  const name = (row.targetName || '').trim()
  if (!name) return ''
  return name.startsWith(code) ? '' : code
}

async function load() {
  if (loading.value) return
  loading.value = true
  try {
    const r = await fetchAudit({
      from: range.value?.[0],
      to: range.value?.[1],
      category: category.value || undefined,
      keyword: keyword.value.trim() || undefined,
      limit
    })
    rows.value = r.rows || []
  } catch (e: any) {
    rows.value = []
    ElMessage.error(t('audit.loadFailed', { msg: e?.message || '' }))
  } finally {
    loading.value = false
  }
}

/** 改筛选条件后重查 */
function reload() { load() }

function reset() {
  range.value = [todayStr(-6), todayStr(0)]
  category.value = ''
  keyword.value = ''
  load()
}

onMounted(load)
</script>

<style scoped>
.mb { margin-bottom: var(--space-4); }
.card-head { display: flex; align-items: baseline; gap: var(--space-3); flex-wrap: wrap; }
.card-head .hint { font-size: var(--text-xs); color: var(--muted); font-weight: 400; }
.toolbar { display: flex; gap: var(--space-2); flex-wrap: wrap; align-items: center; }
.dim { color: var(--muted); font-size: var(--text-2xs); }
.ml6 { margin-left: 6px; }
/* 详情：列宽固定、单元格内换行——长文本不会把表格撑宽 */
.detail {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.limit-tip { margin-top: var(--space-3); font-size: var(--text-2xs); color: var(--meta); }
/* 空状态占位：插槽必须始终有真实元素，否则表格会回落到自带文案 */
.empty-slot { min-height: 40px; }
/* tooltip 内容用插槽渲染：插槽内容带本组件作用域标记，scoped 样式能命中
   （popper 本身被 teleport 到 body，:deep(.popper-class) 选不中） */
.tip-text { display: block; max-width: 520px; white-space: pre-wrap; word-break: break-word; line-height: 1.6; }
</style>
