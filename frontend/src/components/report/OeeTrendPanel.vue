<template>
  <div class="tp">
    <div class="head">
      <div class="hd-left">
        <span class="hd-title">{{ t('oee.trendTitle') }}</span>
        <span class="hd-sub">{{ t('oee.trendPanelHint') }}</span>
      </div>
      <div class="hd-right">
        <el-select v-model="days" class="w120" @change="load">
          <el-option :value="7" :label="t('oee.trendDays7')" />
          <el-option :value="30" :label="t('oee.trendDays30')" />
          <el-option :value="90" :label="t('oee.trendDays90')" />
        </el-select>
        <el-select v-model="devCode" class="w220" @change="onDev">
          <el-option value="" :label="t('oee.trendPlant')" />
          <el-option v-for="d in devices" :key="d.code" :value="d.code" :label="d.name + '（' + d.code + '）'" />
        </el-select>
      </div>
    </div>

    <OeeTrendChart :dates="dates" :points="plantPoints"
                   :title="t('oee.trendPlant')" :subtitle="t('oee.trendPlantSub')" />
    <OeeTrendChart v-if="devCode" :dates="dates" :points="devPoints" show-toggle
                   :title="t('oee.trendDevice')" :subtitle="devSub" />
    <div class="hint">{{ t('oee.trendHint') }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { reportsApi, type TrendDevice } from '@/api/reports'
import OeeTrendChart from '@/components/report/OeeTrendChart.vue'
import type { OeePoint } from '@/utils/oeeTrend'

const { t } = useI18n()

const days = ref(30)
const devCode = ref('')
const raw = ref<{ dates: string[]; rows: any[] }>({ dates: [], rows: [] })
const devices = ref<TrendDevice[]>([])

/**
 * 今天不进趋势图。
 * <p>
 * 今天还没过完：计划 24 小时、现在才跑了 2 小时，可用率算出来是 0.2%——
 * 这个数跟完整的日子根本不可比，画在趋势末尾就是一道假悬崖，
 * 每次看趋势都得先被它晃一下。今天的数字在上面的日报里，那儿有它的说明。
 */
const today = new Date().toISOString().slice(0, 10)

const dates = computed(() => (raw.value.dates || []).filter((d) => d !== today))

/** 厂级逐日 OEE */
const plantPoints = computed<(OeePoint | null)[]>(() => {
  const idx = (raw.value.dates || []).map((d, i) => (d === today ? -1 : i)).filter((i) => i >= 0)
  return idx.map((i) => {
    const o = raw.value.rows?.[i]?.oee
    return (o ? o : null) as OeePoint | null
  })
})

/** 选中设备的逐日 OEE */
const devPoints = computed<(OeePoint | null)[]>(() => {
  const d = devices.value.find((x) => x.code === devCode.value)
  if (!d) return []
  const idx = (raw.value.dates || []).map((dd, i) => (dd === today ? -1 : i)).filter((i) => i >= 0)
  return idx.map((i) => {
    const s = d.series?.[i]
    return (s ? s : null) as OeePoint | null
  })
})

const devSub = computed(() => {
  const d = devices.value.find((x) => x.code === devCode.value)
  return d ? `${d.name}（${d.code}）` : ''
})

async function load() {
  try {
    const j = await reportsApi.range(days.value)
    if (!j || !j.ok) return
    raw.value = { dates: j.dates || [], rows: j.rows || [] }
    devices.value = j.devices || []
    if (devCode.value && !devices.value.some((d) => d.code === devCode.value)) {
      devCode.value = ''
    }
  } catch {
    raw.value = { dates: [], rows: [] }
    devices.value = []
  }
}

function onDev() { /* 图表是响应式的，选中即重画 */ }

onMounted(load)
</script>

<style scoped>
.tp { display: flex; flex-direction: column; gap: var(--space-4); }
.head { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-2); flex-wrap: wrap; }
.hd-title { font-size: var(--text-md); font-weight: 600; }
.hd-sub { font-size: var(--text-2xs); color: var(--meta); }
.hd-right { display: flex; align-items: center; gap: var(--space-2); }
.w120 { width: 120px; }
.w220 { width: 220px; }
.hint { font-size: var(--text-2xs); color: var(--meta); }
</style>
