import 'vue-router'
import type { Locale, NavKey } from './content'

declare module 'vue-router' {
  interface RouteMeta {
    /** 页面语言，决定导航、页脚文案和 html 的 lang */
    locale?: Locale
    /** 页面本身对应的一级导航项，用于 aria-current */
    navKey?: NavKey
    /** 为 false 时不显示页脚（演示跳转页、404 页） */
    footer?: boolean
  }
}
