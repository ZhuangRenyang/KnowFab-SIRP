<template>
  <!--
    基本电费「计算参数」条：四个数字 + 计费方式都能在当前页直接改，改完立刻重算。

    为什么要把它放在页面上而不是藏在弹窗里：
    现场最常问的是「合同需量报 400 还是 500」「按需量还是按容量划算」。
    以前只能 改配置 → 保存 → 看结果 → 改回来，改一次写一次审计、还影响别人看到的数。
    现在直接在这儿改数字，**不保存就不影响任何人**。

    ⚠️ 计算一律走后端试算接口，前端不自己乘：
    「同一笔电费两个地方算」必然会在某次改价后对不上，而且没人能解释。
  -->
  <div v-if="canEdit" class="calc">
    <span class="calc-hd">{{ t('energyDemand.calcTitle') }}</span>

    <label class="calc-f">
      <span>{{ t('energyDemand.contractKw') }}</span>
      <el-input v-model.number="fm.contractDemandKw" type="number" step="1" size="small" class="calc-i" />
      <i>kW</i>
    </label>

    <label class="calc-f">
      <span>{{ t('energyDemand.demandPrice') }}</span>
      <el-input v-model.number="fm.demandPrice" type="number" step="0.01" size="small" class="calc-i" />
      <i>{{ t('energyDemand.unitPerKw') }}</i>
    </label>

    <label class="calc-f">
      <span>{{ t('energyDemand.transformer') }}</span>
      <el-input v-model.number="fm.transformerKva" type="number" step="1" size="small" class="calc-i" />
      <i>kVA</i>
    </label>

    <label class="calc-f">
      <span>{{ t('energyDemand.capacityPrice') }}</span>
      <el-input v-model.number="fm.capacityPrice" type="number" step="0.01" size="small" class="calc-i" />
      <i>{{ t('energyDemand.unitPerKva') }}</i>
    </label>

    <label class="calc-f">
      <span>{{ t('energyDemand.billingMode') }}</span>
      <el-select v-model="fm.billingMode" size="small" class="calc-s">
        <el-option value="AUTO" :label="t('energyDemand.modeAuto')" />
        <el-option value="DEMAND" :label="t('energy.modeDemand')" />
        <el-option value="CAPACITY" :label="t('energy.modeCapacity')" />
      </el-select>
    </label>

    <span class="calc-res">
      <template v-if="pv?.ok">
        {{ t('energyDemand.calcResult') }}<b class="num">{{ num(pv.billing.cost, 2) }}</b>{{ t('energy.currency') }}
        <em>{{ pv.billing.formula }}</em>
      </template>
      <template v-else-if="pv && !pv.ok">{{ pv.message || t('common.reqFail') }}</template>
      <template v-else>--</template>
    </span>

    <template v-if="pv?.changed">
      <el-tag size="small" type="warning" effect="light">{{ t('energyDemand.calcUnsaved') }}</el-tag>
      <el-button size="small" type="primary" :loading="saving" @click="save">
        {{ t('energyDemand.calcSave') }}
      </el-button>
      <el-button size="small" @click="reset">{{ t('common.reset') }}</el-button>
    </template>
    <!-- 试算与已存一致时，明确说一句，免得用户以为没生效 -->
    <span v-else-if="pv?.ok" class="calc-same">{{ t('energyDemand.calcSameAsSaved') }}</span>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, watch, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { energyApi, type DemandConfig, type DemandPreview } from '@/api/energy'
import { num } from '@/api/reports'

const { t } = useI18n()

const props = defineProps<{
  config: DemandConfig | null
  canEdit: boolean
  /** 页面上当前选的月份/设备，试算要跟着走 */
  month?: string
  device?: string
}>()
const emit = defineEmits<{ saved: [] }>()

const fm = reactive<DemandConfig>({
  contractDemandKw: 500, demandPrice: 38, transformerKva: 800, capacityPrice: 23,
  billingMode: 'AUTO', warnRatio: 0.9, enabled: true
})
const pv = ref<DemandPreview | null>(null)
const saving = ref(false)

/** 用已保存的配置初始化（外部数据刷新时也跟着更新） */
function syncFrom(c: DemandConfig | null) {
  if (!c) return
  Object.assign(fm, c)
}

watch(() => props.config, syncFrom, { immediate: true, deep: true })

let timer: ReturnType<typeof setTimeout> | null = null
function schedule() {
  if (timer) clearTimeout(timer)
  // 输入框边打边算会打一串请求，300ms 够用；本地后端也就几毫秒
  timer = setTimeout(run, 300)
}

async function run() {
  try {
    const r = await energyApi.demandPreview({
      contractDemandKw: fm.contractDemandKw,
      demandPrice: fm.demandPrice,
      transformerKva: fm.transformerKva,
      capacityPrice: fm.capacityPrice,
      billingMode: fm.billingMode,
      month: props.month,
      device: props.device
    })
    pv.value = r
  } catch {
    pv.value = null
  }
}

watch(fm, schedule, { deep: true })

function reset() {
  syncFrom(props.config)
}

async function save() {
  saving.value = true
  try {
    const r = await energyApi.saveDemandConfig({ ...fm })
    if (!r.ok) {
      ElMessage.error(r.message || t('common.reqFail'))
      return
    }
    ElMessage.success(r.message || t('energyDemand.calcSaved'))
    emit('saved')
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  } finally {
    saving.value = false
  }
}

/** 首次渲染就先算一遍，让人看到"当前是按这组数算的" */
if (props.canEdit) run()
</script>

<style scoped>
.calc {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 14px;
  padding: 10px 12px;
  margin-top: 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel-2);
}
.calc-hd {
  font-size: var(--text-sm);
  color: var(--muted);
}
.calc-f {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: var(--text-sm);
  color: var(--muted);
}
.calc-i {
  width: 84px;
}
.calc-s {
  width: 108px;
}
.calc-f i {
  font-style: normal;
  color: var(--muted);
  font-size: var(--text-2xs);
}
.calc-res {
  font-size: var(--text-sm);
  color: var(--text);
}
.calc-res b {
  margin: 0 3px;
  font-size: var(--text-md);
}
.calc-res em {
  margin-left: 6px;
  font-style: normal;
  color: var(--muted);
  font-size: var(--text-2xs);
}
.calc-same {
  font-size: var(--text-2xs);
  color: var(--muted);
}
</style>
