import { describe, expect, it } from 'vitest'
import {
  businessMonth,
  chartOption,
  displayValue,
  hasPlotData,
  moveMetric,
} from '../src/pages/dashboard/model'

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

  it('大屏图表使用暗色文字与网格，零值仍是有效数据', () => {
    const plot = { name: '销售月度趋势', points: [{ label: '2026-11', value: '0.00', unit: '元' }] }
    expect(hasPlotData(plot)).toBe(true)
    expect(hasPlotData({ ...plot, points: [{ ...plot.points[0]!, value: null }] })).toBe(false)
    expect(chartOption(plot, true)).toMatchObject({
      color: ['#3c7eff', '#14c9c9', '#f7ba1e', '#722ed1', '#f77234', '#3491fa'],
      textStyle: { color: '#c5c5c5', fontSize: 12 },
      tooltip: { backgroundColor: '#232324', textStyle: { color: '#f6f6f6' } },
      yAxis: { name: '元', splitLine: { lineStyle: { color: '#333335' } } },
      series: [{ data: [0] }],
    })
    expect(chartOption(plot, false)).toMatchObject({ textStyle: { color: '#626975' } })
  })
})
