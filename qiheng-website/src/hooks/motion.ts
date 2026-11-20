/** 用户是否在系统里开启了“减少动态效果”；服务端渲染时按开启处理，保证内容直接可见 */
export function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return true
  }
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

export function canObserve(): boolean {
  return typeof window !== 'undefined' && 'IntersectionObserver' in window
}
