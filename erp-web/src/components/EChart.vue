<script setup lang="ts">
import { onMounted, onScopeDispose, ref, watch } from 'vue'
import { init, use } from 'echarts/core'
import type { EChartsCoreOption, EChartsType } from 'echarts/core'
import { BarChart, FunnelChart, LineChart } from 'echarts/charts'
import { AriaComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([BarChart, FunnelChart, LineChart, AriaComponent, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{ option: EChartsCoreOption; label: string }>()
const element = ref<HTMLElement>()
let chart: EChartsType | undefined
let observer: ResizeObserver | undefined
onMounted(() => {
  if (!element.value) return
  chart = init(element.value)
  chart.setOption(props.option)
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(element.value)
})
watch(() => props.option, option => chart?.setOption(option, { notMerge: true }))
onScopeDispose(() => {
  observer?.disconnect()
  chart?.dispose()
})
</script>

<template>
  <div ref="element" class="trade-chart" role="img" :aria-label="label" />
</template>

<style scoped>
.trade-chart { height: 300px; width: 100%; }
</style>
