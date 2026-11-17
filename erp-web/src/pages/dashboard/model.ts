import type { DashboardPlot, DashboardValue } from '../../types/dashboard'
import { formatMoney, formatQuantity } from '../../utils/format'
import type { EChartsCoreOption } from 'echarts/core'

export function businessMonth(date = new Date()): string {
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
  }).format(date)
}

export function moveMetric(metrics: string[], code: string, offset: number): string[] {
  const from = metrics.indexOf(code)
  const to = from + offset
  if (from < 0 || to < 0 || to >= metrics.length) return metrics.slice()
  const ordered = metrics.slice()
  ordered.splice(from, 1)
  ordered.splice(to, 0, code)
  return ordered
}

export function displayValue(item: DashboardValue): string {
  if (item.value === null) return '—'
  if (item.unit === '元') return formatMoney(item.value)
  return formatQuantity(item.value) + item.unit
}

export function chartOption(plot: DashboardPlot, screen: boolean): EChartsCoreOption {
  const color = screen ? '#56d4ca' : '#176b9d'
  const text = screen ? '#c8d5e6' : '#566174'
  const months = plot.points.every((item) => /^\d{4}-\d{2}$/.test(item.label))
  return {
    color: [color],
    aria: { enabled: true },
    grid: { left: 14, right: 18, top: 14, bottom: 12, containLabel: true },
    tooltip: { trigger: 'axis', renderMode: 'richText' },
    xAxis: {
      type: 'category',
      data: plot.points.map((item) => item.label),
      axisLabel: { color: text, rotate: months ? 0 : 24, width: 90, overflow: 'truncate' },
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: text },
      splitLine: { lineStyle: { color: screen ? '#304359' : '#e1e7ef' } },
    },
    series: [
      {
        type: months ? 'line' : 'bar',
        barMaxWidth: 40,
        data: plot.points.map((item) => (item.value === null ? null : Number(item.value))),
        smooth: false,
        showSymbol: true,
      },
    ],
  }
}
