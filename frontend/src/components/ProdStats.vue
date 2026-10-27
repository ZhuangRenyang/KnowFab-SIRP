<template>
  <div class="pstats">
    <div class="ps-head">
      <span class="ps-title">产量统计</span>
      <span v-if="st && st.skuName" class="ps-meta">产品：{{ st.skuName }}</span>
      <span v-if="st && st.auto" class="ps-meta">
        自动入库{{ st.warehouseName ? ' → ' + st.warehouseName : '' }} · 每日 {{ st.resetHour }} 点清零
      </span>
    </div>

    <template v-if="st && (st.hasCount || st.auto)">
      <div class="ps-kpis num">
        <div class="ps-kpi">今日<b>{{ st.today }}</b></div>
        <div class="ps-kpi">本月<b>{{ st.month }}</b></div>
        <div class="ps-kpi">今年<b>{{ st.year }}</b></div>
      </div>
      <div ref="el" class="ps-chart" />
    </template>
    <div v-else-if="st" class="ps-empty">
      该设备未绑定成品，或点表里没有 role=count 的产量点——在「编辑配置 → 生产入库联动」里启用后，
      每产一件自动入成品仓，这里出每日产量折线图。
    </div>
    <div v-else class="ps-empty">加载中…</div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useEcharts } from '@/composables/useEcharts'
import { prodApi, type ProdStatus } from '@/api/erp'

/* 机器级产量统计（用户口径：每台机器单独清零时刻 + 日/月/年产量 + 详情内折线图）。
   数据源 prod_hourly.output（增量已处理计数器清零与断线补记）。 */
const props = defineProps<{ code: string }>()

const el = ref<HTMLElement>()
const { init, setOption } = useEcharts()
const st = ref<ProdStatus | null>(null)

async function load() {
  try {
    const [s, daily] = await Promise.all([
      prodApi.status(props.code),
      prodApi.daily(props.code, 30)
    ])
    st.value = s
    if (s.hasCount || s.auto) {
      setOption({
        grid: { left: 44, right: 12, top: 14, bottom: 22 },
        xAxis: { type: 'category', data: daily.map((x) => x.date) },
        yAxis: { type: 'value', minInterval: 1 },
        series: [{
          name: '日产量', type: 'line', smooth: true, showSymbol: true,
          symbolSize: 5, data: daily.map((x) => x.qty)
        }]
      })
    }
  } catch { /* 详情页静默，下一轮刷新重试 */ }
}

watch(() => props.code, load)
onMounted(() => {
  if (el.value) init(el.value)
  load()
})
</script>

<style scoped>
.pstats { margin-top: var(--space-3); }
.ps-head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.ps-title { font-size: var(--text-sm); font-weight: 500; }
.ps-meta { font-size: var(--text-xs); color: var(--muted); }
.ps-kpis { display: flex; gap: var(--space-3); margin: 8px 0; flex-wrap: wrap; }
.ps-kpi {
  background: var(--surface-2); border-radius: var(--radius-md); padding: 6px 14px;
  font-size: var(--text-xs); color: var(--muted);
  display: flex; align-items: baseline; gap: 6px;
}
.ps-kpi b { font-size: var(--text-lg); font-weight: 500; color: var(--fg); }
.ps-chart { width: 100%; height: 150px; }
.ps-empty {
  background: var(--surface-2); border-radius: var(--radius-md);
  padding: 14px; font-size: var(--text-xs); color: var(--muted);
}
</style>
