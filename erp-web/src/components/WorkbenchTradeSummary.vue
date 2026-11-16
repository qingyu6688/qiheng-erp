<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import type { EChartsCoreOption } from 'echarts/core'
import { tradeSummary } from '../api/workbench'
import type { TradeSummary } from '../api/workbench'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'
import { formatDateTime, formatMoney } from '../utils/format'
import EChart from './EChart.vue'

const session = useSessionStore()
const data = ref<TradeSummary>()
const loading = ref(false)
const error = ref('')
let revision = 0
let controller = new AbortController()
const cards = computed(() => [
  { key: 'sales', title: '销售确认金额', amounts: data.value?.sales },
  { key: 'purchase', title: '采购收货金额', amounts: data.value?.purchase },
].filter(card => card.amounts))
const option = computed<EChartsCoreOption>(() => ({
  animation: false,
  color: ['#2563eb', '#0d9488'],
  aria: { enabled: true, label: { description: '近十二个月销售确认金额与采购收货金额折线图' } },
  tooltip: { trigger: 'axis' },
  legend: { top: 4 },
  grid: { left: 24, right: 24, top: 44, bottom: 24, containLabel: true },
  xAxis: { type: 'category', data: data.value?.trend.map(point => point.month) ?? [] },
  yAxis: { type: 'value', name: '元' },
  series: cards.value.map(card => ({
    name: card.key === 'sales' ? '销售' : '采购',
    type: 'line',
    showSymbol: false,
    data: data.value?.trend.map(point => Number(card.key === 'sales' ? point.sales : point.purchase)) ?? [],
  })),
}))
async function load(): Promise<void> {
  const current = ++revision
  controller.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  data.value = undefined
  if (!session.companyId || !session.can('report:sales_summary') && !session.can('report:purchase_summary')) {
    loading.value = false
    return
  }
  try {
    const value = await tradeSummary(controller.signal)
    if (current === revision) data.value = value
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
}
watch(() => [session.company, session.can('report:sales_summary'), session.can('report:purchase_summary')], () => { void load() }, { immediate: true })
onScopeDispose(() => { ++revision; controller.abort() })
</script>

<template>
  <section class="workbench-trade" aria-label="销售与采购金额">
    <el-alert v-if="error" :title="error" type="error" :closable="false">
      <el-button text @click="load">重新加载金额</el-button>
    </el-alert>
    <p v-if="loading" role="status" class="muted">正在加载销售与采购金额…</p>
    <template v-if="data">
      <div class="trade-cards">
        <section v-for="card in cards" :key="card.key" class="panel trade-card" :data-trade="card.key">
          <h2>{{ card.title }}</h2>
          <span class="muted">本月</span>
          <strong class="trade-month">{{ formatMoney(card.amounts?.month) }}</strong>
          <dl class="trade-periods">
            <div><dt>今日</dt><dd>{{ formatMoney(card.amounts?.today) }}</dd></div>
            <div><dt>昨日</dt><dd>{{ formatMoney(card.amounts?.yesterday) }}</dd></div>
            <div><dt>本年</dt><dd>{{ formatMoney(card.amounts?.year) }}</dd></div>
          </dl>
        </section>
      </div>
      <section class="panel trade-trend">
        <div class="panel-heading">
          <h2>近 12 个月金额趋势</h2>
          <span class="muted">数据截至 {{ formatDateTime(data.asOf) }}{{ data.dataSource === 'replica' ? ' · 备库' : '' }}</span>
        </div>
        <EChart :option="option" label="近十二个月销售确认金额与采购收货金额折线图" />
      </section>
    </template>
  </section>
</template>

<style scoped>
.workbench-trade { margin-bottom: 24px; }
.trade-cards { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; margin-bottom: 20px; }
.trade-card { padding: 22px; }
.trade-card h2 { font-size: 15px; margin: 0 0 20px; }
.trade-month { display: block; font-size: 28px; margin: 8px 0 20px; font-variant-numeric: tabular-nums; }
.trade-periods { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); margin: 0; gap: 12px; }
.trade-periods dt { color: #64748b; font-size: 12px; }
.trade-periods dd { margin: 8px 0 0; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.trade-trend { padding: 20px 12px 8px; }
@media (max-width: 720px) { .trade-cards { grid-template-columns: 1fr; } }
</style>
