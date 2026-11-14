import { describe, expect, it } from 'vitest'
import { formatMoney, formatPrice, formatQuantity } from '../src/utils/format'

describe('服务端数值的显示', () => {
  it('显示精确金额和负数', () => {
    expect(formatMoney('1130.00')).toBe('1,130.00')
    expect(formatMoney('-45.83')).toBe('−45.83')
    expect(formatMoney('9007199254740993.05')).toBe('9,007,199,254,740,993.05')
  })
  it('按数值类型保留小数位', () => {
    expect(formatPrice('33.33333333')).toBe('33.33333333')
    expect(formatPrice('0')).toBe('0.00')
    expect(formatQuantity('4.000000', '件')).toBe('4 件')
    expect(formatQuantity('0.001000')).toBe('0.001')
  })
  it('未返回的数据使用占位符', () => {
    expect(formatMoney(null)).toBe('—')
    expect(formatQuantity(undefined, '件')).toBe('—')
  })
})
