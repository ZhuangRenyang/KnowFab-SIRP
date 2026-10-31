import { createI18n } from 'vue-i18n'
import { computed, ref } from 'vue'

/* Element Plus 自带组件文案（日期选择器、分页、空状态、确认框）必须跟着界面语言走，
   否则切到英文后弹窗里还是中文 */
import epZhCn from 'element-plus/es/locale/lang/zh-cn'
import epEn from 'element-plus/es/locale/lang/en'
import epJa from 'element-plus/es/locale/lang/ja'
import epKo from 'element-plus/es/locale/lang/ko'
import epEs from 'element-plus/es/locale/lang/es'
import epDe from 'element-plus/es/locale/lang/de'
import epRu from 'element-plus/es/locale/lang/ru'

import zhCN from '../locales/zh-CN'
import enUS from '../locales/en-US'
import jaJP from '../locales/ja-JP'
import koKR from '../locales/ko-KR'
import esES from '../locales/es-ES'
import deDE from '../locales/de-DE'
import ruRU from '../locales/ru-RU'

export interface LocaleOption {
  code: string
  /** 语言自身的写法（下拉框里显示） */
  label: string
  /** Element Plus 对应语言包 */
  ep: unknown
}

export const LOCALES: LocaleOption[] = [
  { code: 'zh-CN', label: '简体中文', ep: epZhCn },
  { code: 'en-US', label: 'English', ep: epEn },
  { code: 'ja-JP', label: '日本語', ep: epJa },
  { code: 'ko-KR', label: '한국어', ep: epKo },
  { code: 'es-ES', label: 'Español', ep: epEs },
  { code: 'de-DE', label: 'Deutsch', ep: epDe },
  { code: 'ru-RU', label: 'Русский', ep: epRu }
]

const STORAGE_KEY = 'sirp.locale'

/** 首次访问跟随浏览器语言；匹配不到前缀就回落中文 */
function detect(): string {
  const nav = (navigator.language || 'zh-CN').toLowerCase()
  const exact = LOCALES.find((l) => l.code.toLowerCase() === nav)
  if (exact) return exact.code
  const prefix = nav.split('-')[0]
  return LOCALES.find((l) => l.code.toLowerCase().startsWith(prefix))?.code ?? 'zh-CN'
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
    'en-US': enUS,
    'ja-JP': jaJP,
    'ko-KR': koKR,
    'es-ES': esES,
    'de-DE': deDE,
    'ru-RU': ruRU
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
