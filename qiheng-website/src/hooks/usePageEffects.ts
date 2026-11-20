import { onBeforeUnmount, onMounted, type Ref } from 'vue'
import { setupAnimPause, setupReveal, setupSpotlight } from './effects'

/** 页面级动效：滚动入场、离开视口暂停循环动画、卡片描边高光。卸载页面时全部清理 */
export function usePageEffects(root: Ref<HTMLElement | null>): void {
  const cleanups: (() => void)[] = []
  onMounted(() => {
    const el = root.value
    if (!el) {
      return
    }
    cleanups.push(setupReveal(el), setupAnimPause(el), setupSpotlight(el))
  })
  onBeforeUnmount(() => {
    cleanups.splice(0).forEach((cleanup) => cleanup())
  })
}
