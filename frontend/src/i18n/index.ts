import { createI18n } from 'vue-i18n'
import { computed, ref } from 'vue'

/* Element Plus 自带组件文案（日期选择器、分页、空状态、确认框）必须跟着界面语言走，
   否则切到英文后弹窗里还是中文 */
import epZhCn from 'element-plus/es/locale/lang/zh-cn'
import epEn from 'element-plus/es/locale/lang/en'

import zhCN from '../locales/zh-CN'
import enUS from '../locales/en-US'

/*
 * 语言范围：中英双语。
 * locales/ 下另有 ja-JP / ko-KR / es-ES / de-DE / ru-RU 五份翻译（导航与登录已齐），
 * 未在此注册，因此不会打进产物；需要时把对应 import 与 LOCALES 条目加回来即可。
 */
export interface LocaleOption {
  code: string
  /** 语言自身的写法（下拉框里显示） */
  label: string
  /** Element Plus 对应语言包 */
  ep: unknown
}

export const LOCALES: LocaleOption[] = [
  { code: 'zh-CN', label: '简体中文', ep: epZhCn },
  { code: 'en-US', label: 'English', ep: epEn }
]

const STORAGE_KEY = 'sirp.locale'

/** 首次访问跟随浏览器语言：中文环境用中文，其余一律英文 */
function detect(): string {
  const nav = (navigator.language || 'zh-CN').toLowerCase()
  return nav.startsWith('zh') ? 'zh-CN' : 'en-US'
}

function initial(): string {
  const saved = localStorage.getItem(STORAGE_KEY)
  return saved && LOCALES.some((l) => l.code === saved) ? saved : detect()
}

export const currentLocale = ref(initial())

export const i18n = createI18n({
  legacy: false,
  locale: currentLocale.value,
  /* 未翻译的键回退中文：新页面还没铺译文时，界面不会出现 key 原文 */
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS
  }
})

export function setLocale(code: string) {
  if (!LOCALES.some((l) => l.code === code)) return
  currentLocale.value = code
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  ;(i18n.global.locale as any).value = code
  localStorage.setItem(STORAGE_KEY, code)
  document.documentElement.lang = code
}

/** 首帧同步一次 <html lang>，便于无障碍与拼写检查 */
document.documentElement.lang = currentLocale.value

/** Element Plus 组件语言包，随界面语言切换 */
export const elementLocale = computed(
  () => (LOCALES.find((l) => l.code === currentLocale.value)?.ep ?? epZhCn) as never
)
