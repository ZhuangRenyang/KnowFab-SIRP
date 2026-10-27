import { defineStore } from 'pinia'
import { ref } from 'vue'

/* 主题：dark 默认 / light 可切。html.dark（Element Plus 暗色机制）与
   html.light（SIRP Token 浅色）两个 class 同步维护；
   localStorage 键 dh-theme 与存量页互通（PRD 风险 10）。 */
export const useThemeStore = defineStore('theme', () => {
  const light = ref(false)

  function apply() {
    const el = document.documentElement
    el.classList.toggle('light', light.value)
    el.classList.toggle('dark', !light.value)
  }

  function init() {
    try { light.value = localStorage.getItem('dh-theme') === 'light' } catch { /* 隐私模式 */ }
    apply()
  }

  function toggle() {
    light.value = !light.value
    try { localStorage.setItem('dh-theme', light.value ? 'light' : 'dark') } catch { /* 隐私模式 */ }
    apply()
  }

  return { light, init, toggle }
})
