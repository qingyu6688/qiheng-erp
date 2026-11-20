import { describe, expect, it } from 'vitest'
import { animateCount, setupReveal } from '@/hooks/effects'

function render(html: string): HTMLElement {
  const root = document.createElement('main')
  root.innerHTML = html
  return root
}

describe('setupReveal', () => {
  it('减少动态效果时直接显示全部内容，并给依次出现的子元素写好延迟', () => {
    const root = render(`
      <div class="head" data-reveal></div>
      <div data-stagger="0.1"><div></div><div></div></div>
      <div class="flow"></div>
      <div class="stats" data-reveal><span data-count="1000" data-suffix="+">1,000+</span></div>`)
    const cleanup = setupReveal(root, true)
    const children = root.querySelectorAll<HTMLElement>('[data-stagger] > div')
    expect(children[0]?.hasAttribute('data-reveal')).toBe(true)
    expect(children[1]?.style.getPropertyValue('--d')).toBe('0.10s')
    expect(root.querySelectorAll('.is-in')).toHaveLength(5)
    expect(root.querySelector('[data-count]')?.textContent).toBe('1,000+')
    cleanup()
  })
})

describe('animateCount', () => {
  it('减少动态效果时直接写入最终值', () => {
    const el = document.createElement('span')
    el.dataset.count = '68'
    animateCount(el, true)
    expect(el.textContent).toBe('68')
  })
})
