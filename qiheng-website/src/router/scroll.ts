import type { RouterScrollBehavior } from 'vue-router'
import { prefersReducedMotion } from '@/hooks/motion'

/** 悬浮导航的高度加上一点留白，锚点滚动时让标题不被导航挡住 */
export const NAV_OFFSET = 88
const TARGET_HIGHLIGHT_MS = 2400
/** 跨页跳转时等新页面渲染完再滚动 */
const CROSS_PAGE_DELAY_MS = 120

/** 模块卡片被锚点命中时短暂高亮，提示用户落在了哪一张 */
export function highlightTarget(el: HTMLElement): void {
  if (!el.classList.contains('module')) {
    return
  }
  el.classList.remove('is-target')
  void el.offsetWidth
  el.classList.add('is-target')
  window.setTimeout(() => el.classList.remove('is-target'), TARGET_HIGHLIGHT_MS)
}

/** 模块卡片居中显示，其余锚点对齐到导航下方 */
export function anchorOffset(el: HTMLElement): number {
  if (el.classList.contains('module')) {
    return Math.max(NAV_OFFSET, (window.innerHeight - el.offsetHeight) / 2)
  }
  return NAV_OFFSET
}

export const scrollBehavior: RouterScrollBehavior = (to, from, savedPosition) => {
  if (savedPosition) {
    return savedPosition
  }
  if (!to.hash) {
    return { top: 0 }
  }
  const delay = to.path === from.path ? 0 : CROSS_PAGE_DELAY_MS
  return new Promise((resolve) => {
    window.setTimeout(() => {
      const el = document.getElementById(decodeURIComponent(to.hash.slice(1)))
      if (!el) {
        resolve({ top: 0 })
        return
      }
      highlightTarget(el)
      resolve({ el, top: anchorOffset(el), behavior: prefersReducedMotion() ? 'auto' : 'smooth' })
    }, delay)
  })
}
