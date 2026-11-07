import { createI18n } from 'vue-i18n'
import { computed, ref } from 'vue'

/* Element Plus 自带组件文案（日期选择器、分页、空状态、确认框）必须跟着界面语言走，
   否则切到英文后弹窗里还是中文 */
import epZhCn from 'element-plus/es/locale/lang/zh-cn'
import epEn from 'element-plus/es/locale/lang/en'

import zhCN from '../locales/zh-CN'
import enUS from '../locales/en-US'

/*
 * 语言范围：**只有中英双语**（规则 R5，见 docs/PROJECT-RULES.md）。
 *
 * ⚠️ 这不是「暂时只做了两种」，是**产品决定**：不新增第三种语言。
 *   加一种语言不是加一份文件，是加一份 1977 键 × 持续维护；
 *   漏翻译的键会回退成中文（fallbackLocale: 'zh-CN'）⇒ 界面中英混杂，比不支持更难排查。
 *
 * locales/ 下只允许存在 zh-CN.ts 与 en-US.ts。
 * scripts/check-i18n.mjs 会断言这一点 ⇒ 新增第三份 locale 文件，门禁直接红。
 *
 * ⇒ **不要在这里加第三种语言**。要加先看 R5 的「什么时候可以破例」三条，并先问用户。
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
