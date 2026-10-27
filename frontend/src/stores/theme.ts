import { defineStore } from 'pinia'
import { ref } from 'vue'

/* 主题：浅色为默认（v3 珍珠玻璃基调），深色可切。html.dark 与 html.light 两个
   class 由本 store 同步维护；localStorage 键 dh-theme 与存量页互通。
   历史值兼容：'dark' 才进深色，未设置/'light' 都走浅色。 */
export const useThemeStore = defineStore('theme', () => {
  const light = ref(true)

  function apply() {
    const el = document.documentElement
    el.classList.toggle('light', light.value)
    el.classList.toggle('dark', !light.value)
  }

  function init() {
    try { light.value = localStorage.getItem('dh-theme') !== 'dark' } catch { /* 隐私模式 */ }
    apply()
  }

  function toggle() {
    light.value = !light.value
    try { localStorage.setItem('dh-theme', light.value ? 'light' : 'dark') } catch { /* 隐私模式 */ }
    apply()
  }

  return { light, init, toggle }
})
