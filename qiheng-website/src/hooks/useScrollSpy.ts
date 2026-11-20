import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'
import type { NavKey } from '@/types/content'
import { canObserve } from './motion'

/**
 * 首页滚动时高亮的导航项。undefined 表示当前页面不做滚动高亮，导航改用路由上的 navKey；
 * null 表示正处在不对应任何导航项的区块（首屏、技术栈条、行动区）。
 */
export const spyKey = ref<NavKey | null | undefined>(undefined)

/** 区块用 data-nav 声明对应的导航项，视口 45% 高度处的区块决定高亮 */
export function useScrollSpy(root: Ref<HTMLElement | null>): void {
  let observer: IntersectionObserver | null = null
  onMounted(() => {
    const el = root.value
    if (!el || !canObserve()) {
      return
    }
    spyKey.value = null
    observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            const key = (entry.target as HTMLElement).dataset.nav
            spyKey.value = key ? (key as NavKey) : null
          }
        })
      },
      { rootMargin: '-45% 0px -54% 0px' },
    )
    el.querySelectorAll<HTMLElement>(':scope > section[data-nav]').forEach((section) => observer?.observe(section))
  })
  onBeforeUnmount(() => {
    observer?.disconnect()
    spyKey.value = undefined
  })
}
