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
  const color = screen ? '#3c7eff' : '#165dff'
  const text = screen ? '#c5c5c5' : '#626975'
  const border = screen ? '#333335' : '#e5e6eb'
  const months = plot.points.every((item) => /^\d{4}-\d{2}$/.test(item.label))
  return {
    color: [color, '#14c9c9', '#f7ba1e', '#722ed1', '#f77234', '#3491fa'],
    backgroundColor: 'transparent',
    textStyle: { color: text, fontSize: 12 },
    aria: { enabled: true },
    grid: { left: 14, right: 18, top: 30, bottom: 12, containLabel: true },
    tooltip: {
      trigger: 'axis',
      renderMode: 'richText',
      backgroundColor: screen ? '#232324' : '#ffffff',
      borderColor: border,
      textStyle: { color: screen ? '#f6f6f6' : '#1d2129', fontSize: 12 },
    },
    xAxis: {
      type: 'category',
      data: plot.points.map((item) => item.label),
      axisLine: { lineStyle: { color: border } },
      axisTick: { lineStyle: { color: border } },
      axisLabel: { color: text, rotate: months ? 0 : 24, width: 90, overflow: 'truncate' },
    },
    yAxis: {
      type: 'value',
      name: plot.points[0]?.unit ?? '',
      nameGap: 12,
      nameTextStyle: { color: text },
      axisLabel: { color: text },
      splitLine: { lineStyle: { color: border } },
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

export function hasPlotData(plot: DashboardPlot): boolean {
  return plot.points.some(
    (point) =>
      point.value !== null && point.value.trim() !== '' && Number.isFinite(Number(point.value)),
  )
}
