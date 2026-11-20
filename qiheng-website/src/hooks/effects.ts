import { easeOutCubic, formatCount } from '@/utils/count'
import { canObserve, prefersReducedMotion } from './motion'

type Cleanup = () => void

const COUNT_DURATION_MS = 1600
const STAGGER_GAP_S = 0.08

/** 数字从 0 滚动到 data-count 的值；减少动态效果时直接显示结果 */
export function animateCount(el: HTMLElement, reduce = prefersReducedMotion()): void {
  const target = Number(el.dataset.count)
  const suffix = el.dataset.suffix ?? ''
  if (!Number.isFinite(target) || target <= 0 || reduce) {
    if (Number.isFinite(target)) {
      el.textContent = formatCount(target, suffix)
    }
    return
  }
  let start: number | null = null
  const frame = (time: number): void => {
    if (start === null) {
      start = time
    }
    const progress = Math.min((time - start) / COUNT_DURATION_MS, 1)
    el.textContent = formatCount(target * easeOutCubic(progress), suffix)
    if (progress < 1) {
      requestAnimationFrame(frame)
    }
  }
  requestAnimationFrame(frame)
}

/**
 * 滚动入场：[data-reveal] 和 .flow 进入视口后加 is-in，只触发一次。
 * [data-stagger] 的子元素依次延迟出现，延迟写在 --d 上。
 */
export function setupReveal(root: HTMLElement, reduce = prefersReducedMotion()): Cleanup {
  root.querySelectorAll<HTMLElement>('[data-stagger]').forEach((group) => {
    const gap = Number.parseFloat(group.dataset.stagger ?? '') || STAGGER_GAP_S
    Array.from(group.children).forEach((child, index) => {
      const el = child as HTMLElement
      if (!el.hasAttribute('data-reveal')) {
        el.setAttribute('data-reveal', '')
      }
      el.style.setProperty('--d', `${(index * gap).toFixed(2)}s`)
    })
  })
  const targets = root.querySelectorAll<HTMLElement>('[data-reveal], .flow')
  const reveal = (el: HTMLElement): void => {
    el.classList.add('is-in')
    el.querySelectorAll<HTMLElement>('[data-count]').forEach((n) => animateCount(n, reduce))
  }
  if (reduce || !canObserve()) {
    targets.forEach(reveal)
    return () => {}
  }
  const observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          reveal(entry.target as HTMLElement)
          observer.unobserve(entry.target)
        }
      })
    },
    { threshold: 0.12, rootMargin: '0px 0px -6% 0px' },
  )
  targets.forEach((el) => observer.observe(el))
  return () => observer.disconnect()
}

/** [data-anim] 区块离开视口时加 is-paused，CSS 只暂停其中的循环动画 */
export function setupAnimPause(root: HTMLElement, reduce = prefersReducedMotion()): Cleanup {
  if (reduce || !canObserve()) {
    return () => {}
  }
  const observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => entry.target.classList.toggle('is-paused', !entry.isIntersecting))
    },
    { rootMargin: '120px 0px' },
  )
  root.querySelectorAll<HTMLElement>('[data-anim]').forEach((el) => observer.observe(el))
  return () => observer.disconnect()
}

/** .spot 卡片的描边高光跟随鼠标 */
export function setupSpotlight(root: HTMLElement): Cleanup {
  const cards = Array.from(root.querySelectorAll<HTMLElement>('.spot'))
  const onMove = (event: PointerEvent): void => {
    const card = event.currentTarget as HTMLElement
    const rect = card.getBoundingClientRect()
    card.style.setProperty('--mx', `${event.clientX - rect.left}px`)
    card.style.setProperty('--my', `${event.clientY - rect.top}px`)
  }
  cards.forEach((card) => card.addEventListener('pointermove', onMove))
  return () => cards.forEach((card) => card.removeEventListener('pointermove', onMove))
}
