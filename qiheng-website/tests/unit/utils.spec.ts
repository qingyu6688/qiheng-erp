import { describe, expect, it } from 'vitest'
import { shotSrc, shotSrcset } from '@/utils/assets'
import { easeOutCubic, formatCount } from '@/utils/count'
import { splitInlineCode, splitPlaceholder } from '@/utils/text'

describe('formatCount', () => {
  it('加千分位和后缀', () => {
    expect(formatCount(1000, '+')).toBe('1,000+')
    expect(formatCount(97.6)).toBe('98')
  })
})

describe('easeOutCubic', () => {
  it('超出范围时夹在 0 到 1', () => {
    expect(easeOutCubic(-1)).toBe(0)
    expect(easeOutCubic(2)).toBe(1)
    expect(easeOutCubic(0.5)).toBeGreaterThan(0.5)
  })
})

describe('splitInlineCode', () => {
  it('反引号内的部分标记为代码', () => {
    expect(splitInlineCode('Set `DB_USERNAME` first')).toEqual([
      { text: 'Set ', code: false },
      { text: 'DB_USERNAME', code: true },
      { text: ' first', code: false },
    ])
  })

  it('没有反引号时原样返回', () => {
    expect(splitInlineCode('plain')).toEqual([{ text: 'plain', code: false }])
  })
})

describe('splitPlaceholder', () => {
  it('按占位拆成前后两段', () => {
    expect(splitPlaceholder('未核销应收 {amount} · 截至 10:32', 'amount')).toEqual(['未核销应收 ', ' · 截至 10:32'])
  })

  it('找不到占位时后段为空', () => {
    expect(splitPlaceholder('无占位', 'amount')).toEqual(['无占位', ''])
  })
})

describe('截图地址', () => {
  it('桌面截图 1440 / 2880 宽，PDA 截图 344 / 688 宽', () => {
    // 地址带部署时的 base，GitHub Pages 上为 /qiheng-erp/
    const base = import.meta.env.BASE_URL
    expect(shotSrc('orders')).toBe(`${base}images/shots/orders.webp`)
    expect(shotSrcset('orders')).toBe(`${base}images/shots/orders.webp 1440w, ${base}images/shots/orders@2x.webp 2880w`)
    expect(shotSrcset('pda')).toContain('pda@2x.webp 688w')
  })
})
