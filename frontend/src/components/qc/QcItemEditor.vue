<template>
  <div class="ie">
    <el-table :data="rows" size="small" stripe :empty-text="t('qc.item.empty')">
      <el-table-column :label="t('qc.item.colName')" min-width="150">
        <template #default="{ row }">
          <el-input v-model="row.itemName" size="small" :placeholder="t('qc.item.namePh')" />
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.item.colStandard')" min-width="130">
        <template #default="{ row }">
          <el-input v-model="row.standard" size="small" :placeholder="t('qc.item.standardPh')" />
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.item.colMethod')" width="120">
        <template #default="{ row }">
          <el-input v-model="row.method" size="small" :placeholder="t('qc.item.methodPh')" />
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.item.colDefect')" width="196">
        <template #default="{ row }">
          <!-- 缺陷代码：没登记过的可以就地新建，不用跳到另一个页面 -->
          <el-select
            v-model="row.defectCodeId"
            size="small"
            filterable
            clearable
            class="w-full"
            :placeholder="t('qc.item.defectPh')"
            @change="(v: number | undefined) => onPickCode(row, v)"
          >
            <el-option v-for="c in codes" :key="c.id" :value="c.id" :label="`${c.code} ${c.name}`">
              <span class="opt-code">{{ c.code }}</span>
              <el-tag size="small" effect="plain" :type="gradeType(c.severity)">
                {{ t('qc.severity.' + c.severity) }}
              </el-tag>
              <span class="opt-name">{{ c.name }}</span>
            </el-option>
            <el-option :value="NEW_CODE" :label="t('qc.item.newDefect')" class="new-opt">
              <el-icon><Plus /></el-icon> {{ t('qc.item.newDefect') }}
            </el-option>
          </el-select>
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.item.colMeasured')" min-width="130">
        <template #default="{ row }">
          <el-input v-model="row.measured" size="small" :placeholder="t('qc.item.measuredPh')" />
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.item.colResult')" width="92">
        <template #default="{ row }">
          <el-radio-group v-model="row.result" size="small">
            <el-radio-button value="OK">{{ t('qc.item.ok') }}</el-radio-button>
            <el-radio-button value="NG">{{ t('qc.item.ng') }}</el-radio-button>
          </el-radio-group>
        </template>
      </el-table-column>
      <el-table-column :label="t('qc.item.colQty')" width="92" align="right">
        <template #default="{ row }">
          <el-input-number
            v-model="row.defectQty"
            :min="0"
            :precision="0"
            size="small"
            controls-position="right"
            class="w-full"
          />
        </template>
      </el-table-column>
      <el-table-column :label="t('base.action')" width="64" align="right">
        <template #default="{ $index }">
          <el-button link type="danger" :icon="Delete" @click="removeRow($index)" />
        </template>
      </el-table-column>
    </el-table>

    <div class="ie-foot">
      <el-button size="small" :icon="Plus" @click="addRow">{{ t('qc.item.addRow') }}</el-button>
      <span class="hint">{{ t('qc.item.qtyHint') }}</span>
    </div>

    <!-- 缺陷代码就地新建 -->
    <el-dialog v-model="newVisible" :title="t('qc.item.newDefectTitle')" width="460px" append-to-body>
      <el-form label-width="86px">
        <el-form-item :label="t('qc.item.code')" required>
          <el-input v-model="newCode.code" :placeholder="t('qc.item.codePh')" maxlength="32" />
        </el-form-item>
        <el-form-item :label="t('qc.item.name')" required>
          <el-input v-model="newCode.name" :placeholder="t('qc.item.namePh2')" maxlength="128" />
        </el-form-item>
        <el-form-item :label="t('qc.item.severity')" required>
          <el-radio-group v-model="newCode.severity">
            <el-radio-button v-for="s in SEVERITIES" :key="s" :value="s">
              {{ t('qc.severity.' + s) }}
            </el-radio-button>
          </el-radio-group>
          <div class="field-hint">{{ t('qc.item.severityHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('qc.item.category')">
          <el-input v-model="newCode.category" :placeholder="t('qc.item.categoryPh')" maxlength="64" />
        </el-form-item>
        <el-form-item :label="t('qc.item.remark')">
          <el-input v-model="newCode.remark" type="textarea" :rows="2" :placeholder="t('qc.item.remarkPh')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="newVisible = false">{{ t('base.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="createCode">{{ t('base.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import { gradeType, qcDefectCodeApi, SEVERITIES, type QcDefectCode, type QcInspectionItemInput } from '@/api/qc'

const props = defineProps<{
  modelValue: QcInspectionItemInput[]
  /** 已登记的缺陷代码 */
  codes: QcDefectCode[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: QcInspectionItemInput[]): void
  (e: 'created', code: QcDefectCode): void
}>()

const { t } = useI18n()

/** 下拉里的"新建"哨兵值。用 -1 而不是字符串，避免和真实 id 撞 */
const NEW_CODE = -1

const newVisible = ref(false)
const saving = ref(false)
const newCode = ref({ code: '', name: '', severity: 'MA' as string, category: '', remark: '' })
/** 新建完成后要回填到哪一行 */
const pendingRow = ref<QcInspectionItemInput | null>(null)

const rows = computed(() => props.modelValue)

function emitRows() {
  emit('update:modelValue', [...rows.value])
}

function addRow() {
  emit('update:modelValue', [
    ...rows.value,
    { itemName: '', standard: '', method: '', measured: '', result: 'OK', defectQty: 0 }
  ])
}

function removeRow(i: number) {
  const next = [...rows.value]
  next.splice(i, 1)
  emit('update:modelValue', next)
}

function onPickCode(row: QcInspectionItemInput, id: number | undefined) {
  if (id === NEW_CODE) {
    pendingRow.value = row
    newCode.value = { code: '', name: '', severity: 'MA', category: '', remark: '' }
    newVisible.value = true
    return
  }
  const c = props.codes.find((x) => x.id === id)
  if (c) {
    row.defectCode = c.code
    row.defectName = c.name
    row.severity = c.severity
  } else {
    row.defectCode = null
    row.defectName = null
    row.severity = null
  }
  emitRows()
}

async function createCode() {
  if (!newCode.value.code.trim() || !newCode.value.name.trim()) {
    ElMessage.warning(t('qc.item.needCodeName'))
    return
  }
  saving.value = true
  try {
    const r = await qcDefectCodeApi.create({
      code: newCode.value.code.trim(),
      name: newCode.value.name.trim(),
      severity: newCode.value.severity as 'CR' | 'MA' | 'MI',
      category: newCode.value.category.trim() || null,
      remark: newCode.value.remark.trim() || null,
      enabled: true
    })
    if (!r.ok) {
      ElMessage.error(r.message || t('common.reqFail'))
      return
    }
    ElMessage.success(r.message || t('qc.item.codeCreated'))
    newVisible.value = false
    emit('created', {
      id: 0,
      code: newCode.value.code.trim(),
      name: newCode.value.name.trim(),
      severity: newCode.value.severity as 'CR' | 'MA' | 'MI',
      category: newCode.value.category.trim() || null,
      defaultAql: null,
      remark: newCode.value.remark.trim() || null,
      sortNo: 0,
      enabled: true
    })
    // 父组件拿到新代码后会刷新 codes 列表，用户再选一次即可回填
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.ie-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}
.hint {
  font-size: var(--text-xs);
  color: var(--muted);
}
.opt-code {
  display: inline-block;
  min-width: 64px;
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  color: var(--muted);
}
.opt-name {
  margin-left: 6px;
  color: var(--fg-2);
}
:deep(.new-opt) {
  font-weight: 600;
}
</style>
