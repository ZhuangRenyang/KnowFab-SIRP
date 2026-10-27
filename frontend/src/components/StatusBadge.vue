<template>
  <span class="tag" :class="`tag-${type}`">
    <i v-if="dot" class="tag-dot" />{{ label }}
  </span>
</template>

<script setup lang="ts">
/* 全站统一状态标签：12px、文字色 = 状态 fg、底色 = soft bg（设计系统 v2 §五） */
withDefaults(defineProps<{
  type?: 'run' | 'idle' | 'stop' | 'alarm' | 'off'
  label?: string
  dot?: boolean
}>(), { type: 'stop', label: '--', dot: false })
</script>

<style scoped>
.tag {
  display: inline-flex; align-items: center; gap: 5px;
  font-size: var(--text-xs); padding: 2px 8px; border-radius: var(--radius-sm);
  white-space: nowrap;
}
.tag-dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.tag-run  { color: var(--run);   background: var(--run-soft); }
.tag-idle { color: var(--idle);  background: var(--idle-soft); }
.tag-stop { color: var(--stop);  background: var(--stop-soft); }
.tag-alarm{ color: var(--alarm); background: var(--alarm-soft); }
.tag-off  { color: var(--off);   background: var(--off-soft); }
</style>
