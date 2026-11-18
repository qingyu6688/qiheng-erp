<script setup lang="ts">
import { computed } from 'vue'
import { formatMoney } from '../utils/format'

const props = defineProps<{
  title: string
  value: string
  previousMonth?: string
  previousYear?: string
  trend: number[]
}>()
const percentage = new Intl.NumberFormat('zh-CN', {
  style: 'percent',
  maximumFractionDigits: 1,
  signDisplay: 'exceptZero',
})
function change(previous?: string): string {
  const baseline = Number(previous)
  return Number.isFinite(baseline) && baseline !== 0
    ? percentage.format((Number(props.value) - baseline) / Math.abs(baseline))
    : '—'
}
const points = computed(() => {
  if (props.trend.length < 2) return ''
  const minimum = Math.min(...props.trend)
  const range = Math.max(...props.trend) - minimum
  return props.trend
    .map((value, index) => {
      const x = (index * 120) / (props.trend.length - 1)
      const y = range ? 31 - ((value - minimum) * 26) / range : 18
      return `${x},${y}`
    })
    .join(' ')
})
</script>

<template>
  <section class="panel metric-card">
    <header>
      <h2>{{ title }}</h2>
      <span>元</span>
    </header>
    <strong>{{ formatMoney(value) }}</strong>
    <div v-if="previousMonth !== undefined || previousYear !== undefined" class="metric-comparison">
      <span
        >同比 <b>{{ change(previousYear) }}</b></span
      >
      <span
        >环比 <b>{{ change(previousMonth) }}</b></span
      >
    </div>
    <svg
      v-if="points"
      class="metric-sparkline"
      viewBox="0 0 120 36"
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      <polyline
        :points="points"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        vector-effect="non-scaling-stroke"
      />
    </svg>
  </section>
</template>

<style scoped>
.metric-card {
  min-width: 0;
  padding: 14px 16px 8px;
}
.metric-card header {
  display: flex;
  gap: 8px;
  justify-content: space-between;
  align-items: baseline;
}
.metric-card h2 {
  margin: 0;
  font-size: 14px;
}
.metric-card header span,
.metric-comparison {
  color: var(--erp-text-2);
  font-size: 12px;
}
.metric-card strong {
  display: block;
  margin: 6px 0;
  font-size: clamp(20px, 2.2vw, 28px);
  line-height: 1.3;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.metric-comparison {
  display: flex;
  gap: 4px 12px;
  flex-wrap: wrap;
}
.metric-comparison b {
  color: var(--erp-text-1);
  font-variant-numeric: tabular-nums;
}
.metric-sparkline {
  display: block;
  width: 100%;
  height: 30px;
  color: var(--el-color-primary);
}
[data-trade='purchase'] .metric-sparkline {
  color: #0d9488;
}
[data-trade='receivable'] .metric-sparkline {
  color: #ad6800;
}
[data-trade='inventory'] .metric-sparkline {
  color: #722ed1;
}
</style>
