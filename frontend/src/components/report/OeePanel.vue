<template>
  <div class="oee">
    <div class="main">
      <div class="big">
        <span class="lbl">{{ t('oee.title') }}</span>
        <b class="num" :class="grade(oee?.oee)">{{ oeeText }}</b>
        <em>{{ t('oee.formula') }}</em>
        <!-- 把分母摆出来：可用率看着低的时候，得让人知道是「计划 72 小时只干了 2.7 小时」
             还是「系统下午才接上、根本没采到」 -->
        <em class="base">{{ baseText }}</em>
      </div>
      <div class="factors">
        <div class="f">
          <span>{{ t('oee.availability') }}</span>
          <b :class="grade(oee?.availability)">{{ pctText(oee?.availability) }}</b>
          <em>{{ t('oee.availabilityHint') }}</em>
        </div>
        <div class="f">
          <span>{{ t('oee.performance') }}</span>
          <b :class="grade(oee?.performance)">{{ pctText(oee?.performance) }}</b>
          <em>{{ t('oee.performanceHint') }}</em>
        </div>
        <div class="f">
          <span>{{ t('oee.quality') }}</span>
          <b :class="grade(oee?.quality)">{{ pctText(oee?.quality) }}</b>
          <em>{{ t('oee.qualityHint') }}</em>
        </div>
      </div>
    </div>

    <!-- 算不出来的时候，把缺什么、怎么补说清楚。不能拿个假数糊弄过去 -->
    <div v-if="notes.length" class="notes">
      <div v-for="(n, i) in notes" :key="i" class="note">{{ n }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { num, type OeeInfo } from '@/api/reports'

const { t } = useI18n()

const props = defineProps<{ oee?: OeeInfo }>()

const notes = computed(() => props.oee?.notes || [])

/** 算不出来显示「--」，跟「0」分开。0 是一台都没做，-- 是数据不全 */
function pctText(v: number | null | undefined): string {
  if (v === null || v === undefined) return '--'
  return num(v, 1) + '%'
}

const oeeText = computed(() => pctText(props.oee?.oee))

/** 可用率的分母和分子摆出来，数字低的时候才知道该看哪里 */
const baseText = computed(() => {
  const o = props.oee
  if (!o) return ''
  const h = (s?: number) => num((s || 0) / 3600, 1)
  return t('oee.base', {
    planned: h(o.plannedSec),
    run: h(o.runSec),
    counted: h(o.countedSec)
  })
})

/** 按数值给个颜色档：好 / 中 / 差 */
function grade(v: number | null | undefined): string {
  if (v === null || v === undefined) return 'na'
  if (v >= 85) return 'good'
  if (v >= 60) return 'mid'
  return 'bad'
}
</script>

<style scoped>
.oee { display: flex; flex-direction: column; gap: var(--space-3); }
.main { display: grid; grid-template-columns: minmax(150px, 1fr) 3fr; gap: var(--space-4); align-items: stretch; }
.big { display: flex; flex-direction: column; gap: 2px; }
.big .lbl { font-size: var(--text-xs); color: var(--muted); letter-spacing: .06em; }
.big b { font-size: var(--text-2xl); font-weight: 600; line-height: 1.1; }
.big em { font-style: normal; font-size: var(--text-2xs); color: var(--meta); }
.factors { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--space-3); }
.f {
  background: var(--surface-2); border-radius: var(--radius-sm);
  padding: var(--space-3); display: flex; flex-direction: column; gap: 2px; min-width: 0;
}
.f span { font-size: var(--text-2xs); color: var(--muted); }
.f b { font-size: var(--text-lg); font-weight: 600; }
.f em { font-style: normal; font-size: var(--text-2xs); color: var(--meta); line-height: 1.6; }
.good { color: var(--run); }
.mid { color: var(--idle); }
.bad { color: var(--alarm); }
.na { color: var(--muted); }
.notes { display: flex; flex-direction: column; gap: var(--space-2); }
.note {
  font-size: var(--text-2xs); color: var(--muted); line-height: 1.7;
  background: var(--surface-2); border-left: 2px solid var(--border-strong);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0; padding: var(--space-2) var(--space-3);
}
@media (max-width: 1100px) {
  .main { grid-template-columns: 1fr; }
  .factors { grid-template-columns: repeat(3, 1fr); }
}
</style>
