import { describe, expect, it } from 'vitest'
import { businessMonth, chartOption, displayValue, moveMetric } from '../src/pages/dashboard/model'

describe('经营驾驶舱', () => {
  it('按上海业务日选择默认月份并保留首尾边界', () => {
    expect(businessMonth(new Date('2026-12-31T15:59:59Z'))).toBe('2026-12')
    expect(businessMonth(new Date('2026-12-31T16:00:00Z'))).toBe('2027-01')
    const order = ['inventory', 'sales', 'finance']
    expect(moveMetric(order, 'finance', -2)).toEqual(['finance', 'inventory', 'sales'])
    expect(moveMetric(order, 'inventory', -1)).toEqual(order)
  })

  it('金额显示保留分币和超出安全整数的精度，未知比率保留空值', () => {
    expect(displayValue({ label: '库存', value: '9007199254740993.12', unit: '元' })).toContain(
      '9,007,199,254,740,993.12',
    )
    expect(displayValue({ label: '毛利率', value: null, unit: '%' })).toBe('—')
    expect(
      chartOption({ name: '概览', points: [{ label: '样本', value: null, unit: '%' }] }, false)
        .series,
    ).toMatchObject([{ data: [null] }])
  })
})
