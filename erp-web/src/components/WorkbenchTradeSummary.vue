<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import type { EChartsCoreOption } from 'echarts/core'
import { tradeSummary } from '../api/workbench'
import type { TradeMetric, TradeSummary } from '../api/workbench'
import { ApiError, isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'
import { formatDateTime } from '../utils/format'
import EChart from './EChart.vue'
import MetricCard from './MetricCard.vue'

const props = defineProps<{ refreshKey?: number }>()
const metrics: { key: TradeMetric; title: string; series: string; color: string }[] = [
  { key: 'sales', title: '本月销售确认金额', series: '销售', color: '#2563eb' },
  { key: 'purchase', title: '本月采购收货金额', series: '采购', color: '#0d9488' },
  { key: 'receivable', title: '应收余额', series: '应收', color: '#ad6800' },
  { key: 'inventory', title: '库存金额', series: '库存', color: '#722ed1' },
]
const session = useSessionStore()
const data = ref<TradeSummary>()
const loading = ref(false)
const error = ref('')
let revision = 0
let controller = new AbortController()

const canViewTrade = computed(
  () => session.can('report:sales_summary') || session.can('report:purchase_summary'),
)
const canViewReceivable = computed(
  () => session.company?.allWarehouses && session.can('report:receivable_aging'),
)
const canViewInventory = computed(
  () => session.can('report:inventory_summary') && session.can('cost:view'),
)
const cards = computed(() =>
  metrics.flatMap((metric) => {
    const raw = data.value?.[metric.key]
    if (raw === undefined) return []
    const trend =
      data.value?.trend.flatMap((point) => {
        const value = point[metric.key]
        return value === undefined ? [] : [Number(value)]
      }) ?? []
    return [
      {
        ...metric,
        value: typeof raw === 'string' ? raw : raw.month,
        trend,
        ...data.value?.comparison?.[metric.key],
      },
    ]
  }),
)
const chartMetrics = computed(() =>
  cards.value.filter((card) => card.key === 'sales' || card.key === 'purchase'),
)
const chartLabel = computed(
  () =>
    '近十二个月' +
    chartMetrics.value.map(({ title }) => title.replace(/^本月/, '')).join('与') +
    '折线图',
)
const option = computed<EChartsCoreOption>(() => ({
  animation: false,
  color: chartMetrics.value.map((card) => card.color),
  aria: { enabled: true, label: { description: chartLabel.value } },
  tooltip: { trigger: 'axis' },
  legend: { top: 4 },
  grid: { left: 24, right: 24, top: 44, bottom: 24, containLabel: true },
  xAxis: { type: 'category', data: data.value?.trend.map((point) => point.month) ?? [] },
  yAxis: { type: 'value', name: '元' },
  series: chartMetrics.value.map((card) => ({
    name: card.series,
    type: 'line',
    showSymbol: false,
    data: card.trend,
  })),
}))
async function load(): Promise<void> {
  const current = ++revision
  controller.abort()
  controller = new AbortController()
  loading.value = false
  error.value = ''
  data.value = undefined
  if (
    !session.companyId ||
    (!canViewTrade.value && !canViewReceivable.value && !canViewInventory.value)
  )
    return
  loading.value = true
  try {
    const value = await tradeSummary(controller.signal)
    if (current === revision) data.value = value
  } catch (cause: unknown) {
    const noInventoryScope =
      !canViewTrade.value &&
      !canViewReceivable.value &&
      cause instanceof ApiError &&
      cause.status === 403
    if (current === revision && !isCancelled(cause) && !noInventoryScope)
      error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
}
watch(
  [
    () => session.companyId,
    () => session.user,
    canViewTrade,
    canViewReceivable,
    canViewInventory,
    () => props.refreshKey,
  ],
  () => {
    void load()
  },
  { immediate: true },
)
onScopeDispose(() => {
  ++revision
  controller.abort()
})
</script>

<template>
  <section v-if="error || loading || cards.length" class="workbench-trade" aria-label="经营指标">
    <el-alert v-if="error" :title="error" type="error" :closable="false">
      <el-button text @click="load">重新加载金额</el-button>
    </el-alert>
    <p v-else-if="loading" role="status" class="muted">正在加载经营指标…</p>
    <div v-else class="trade-cards">
      <MetricCard
        v-for="card in cards"
        :key="card.key"
        :data-trade="card.key"
        :title="card.title"
        :value="card.value"
        :previous-month="card.previousMonth"
        :previous-year="card.previousYear"
        :trend="card.trend"
      />
    </div>
  </section>
  <slot />
  <section v-if="data && chartMetrics.length" class="panel trade-trend">
    <div class="panel-heading">
      <h2>近 12 个月金额趋势</h2>
      <span class="muted"
        >数据截至 {{ formatDateTime(data.asOf)
        }}{{ data.dataSource === 'replica' ? ' · 备库' : '' }}</span
      >
    </div>
    <EChart :option="option" :label="chartLabel" />
  </section>
</template>

<style scoped>
.workbench-trade {
  margin-bottom: 16px;
}
.trade-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(180px, 100%), 1fr));
  gap: 12px;
}
.trade-trend {
  margin-top: 16px;
  padding: 16px 12px 8px;
}
.trade-trend .panel-heading {
  flex-wrap: wrap;
  gap: 6px 12px;
}
.trade-trend h2 {
  margin: 0;
  font-size: 16px;
}
</style>
