/** 数字滚动的缓动：先快后慢 */
export function easeOutCubic(progress: number): number {
  const p = Math.min(Math.max(progress, 0), 1)
  return 1 - Math.pow(1 - p, 3)
}

/** 统计数字的显示格式：千分位 + 后缀，例如 1,000+ */
export function formatCount(value: number, suffix = ''): string {
  return Math.round(value).toLocaleString('en-US') + suffix
}
