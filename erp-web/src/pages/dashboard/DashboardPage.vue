<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElDatePicker } from 'element-plus/es/components/date-picker/index.mjs'
import { ElEmpty } from 'element-plus/es/components/empty/index.mjs'
import { ElSkeleton } from 'element-plus/es/components/skeleton/index.mjs'
import 'element-plus/theme-chalk/el-date-picker.css'
import 'element-plus/theme-chalk/el-empty.css'
import 'element-plus/theme-chalk/el-skeleton.css'
import {
  dashboardData,
  dashboardLayout,
  dashboardMetrics,
  saveDashboardLayout,
} from '../../api/dashboard'
import { isCancelled, messageOf } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import type { DashboardData, DashboardLayout, DashboardMetric } from '../../types/dashboard'
import { formatDateTimeSeconds } from '../../utils/format'
import { businessMonth, chartOption, displayValue, hasPlotData, moveMetric } from './model'

const EChart = defineAsyncComponent(() => import('../../components/EChart.vue'))
const REFRESH_INTERVAL = 300_000
const ROTATION_INTERVAL = 15_000
const session = useSessionStore()
const workspace = ref<HTMLElement>()
const month = ref(businessMonth())
const available = ref<DashboardMetric[]>([])
const layout = ref<DashboardLayout>()
const results = ref<DashboardData>()
const draft = ref<string[]>()
const dragging = ref<string>()
const loading = ref(false)
const saving = ref(false)
const failure = ref('')
const screen = ref(false)
const page = ref(0)
let revision = 0
let rotation: ReturnType<typeof setInterval> | undefined
let refreshTimer: ReturnType<typeof setInterval> | undefined

const display = computed(() => {
  const items = results.value?.items ?? []
  return screen.value && items.length ? [items[page.value % items.length]!] : items
})
const sourceLabel = { replica: '报表备库', primary: '主库', mixed: '主库与报表备库' }

function name(code: string): string {
  return available.value.find((item) => item.code === code)?.name ?? code
}

function reportTarget(code: string): { path: string; query?: Record<string, string> } {
  const path = available.value.find((item) => item.code === code)?.detailPath ?? '/reports'
  if (results.value && ['sales', 'gross_margin', 'purchase'].includes(code)) {
    return { path, query: { dateFrom: results.value.dateFrom, dateTo: results.value.dateTo } }
  }
  if (results.value && code === 'receivables')
    return { path, query: { agingDate: results.value.dateTo } }
  return { path }
}

async function loadWorkspace(): Promise<void> {
  const id = ++revision
  layout.value = undefined
  results.value = undefined
  draft.value = undefined
  available.value = []
  failure.value = ''
  loading.value = true
  try {
    const [metrics, saved] = await Promise.all([dashboardMetrics(), dashboardLayout()])
    if (id !== revision) return
    available.value = metrics
    layout.value = saved
    await loadData(false)
  } catch (error: unknown) {
    if (!isCancelled(error) && id === revision) failure.value = messageOf(error)
  } finally {
    if (id === revision) loading.value = false
  }
}

async function loadData(refresh = false): Promise<void> {
  if (!layout.value) return
  const id = ++revision
  loading.value = true
  failure.value = ''
  if (!refresh) results.value = undefined
  try {
    if (!layout.value.metrics.length) return
    const response = await dashboardData(month.value, layout.value.metrics, refresh)
    if (id === revision) results.value = response
  } catch (error: unknown) {
    if (!isCancelled(error) && id === revision) failure.value = messageOf(error)
  } finally {
    if (id === revision) loading.value = false
  }
}

async function save(): Promise<void> {
  if (!draft.value || !layout.value || saving.value) return
  const company = session.companyId
  saving.value = true
  failure.value = ''
  try {
    const saved = await saveDashboardLayout(layout.value.version, draft.value)
    if (company !== session.companyId) return
    layout.value = saved
    draft.value = undefined
    await loadData()
  } catch (error: unknown) {
    if (!isCancelled(error) && company === session.companyId) failure.value = messageOf(error)
  } finally {
    saving.value = false
  }
}

function drop(target: string): void {
  if (draft.value && dragging.value) {
    const offset = draft.value.indexOf(target) - draft.value.indexOf(dragging.value)
    draft.value = moveMetric(draft.value, dragging.value, offset)
  }
  dragging.value = undefined
}

function startDrag(event: DragEvent, code: string): void {
  dragging.value = code
  event.dataTransfer?.setData('text/plain', code)
}

function changeVisibility(code: string, checked: boolean): void {
  if (draft.value)
    draft.value = checked ? [...draft.value, code] : draft.value.filter((value) => value !== code)
}

function shift(code: string, offset: number): void {
  if (draft.value) draft.value = moveMetric(draft.value, code, offset)
}

async function fullscreen(): Promise<void> {
  try {
    if (screen.value) await document.exitFullscreen()
    else await workspace.value?.requestFullscreen()
  } catch {
    failure.value = '当前浏览器无法切换全屏。'
  }
}

function syncFullscreen(): void {
  screen.value = document.fullscreenElement === workspace.value
}

watch(screen, (enabled) => {
  clearInterval(rotation)
  clearInterval(refreshTimer)
  page.value = 0
  if (!enabled) return
  rotation = setInterval(() => {
    page.value++
  }, ROTATION_INTERVAL)
  refreshTimer = setInterval(() => {
    if (!loading.value) void loadData(true)
  }, REFRESH_INTERVAL)
})
watch(() => session.companyId, loadWorkspace, { immediate: true })
onMounted(() => document.addEventListener('fullscreenchange', syncFullscreen))
onBeforeUnmount(() => {
  revision++
  clearInterval(rotation)
  clearInterval(refreshTimer)
  document.removeEventListener('fullscreenchange', syncFullscreen)
})
</script>

<template>
  <div
    ref="workspace"
    class="dashboard"
    :class="{ 'dashboard--screen': screen }"
    :aria-busy="loading"
  >
    <header class="page-heading dashboard-heading">
      <div>
        <h1>经营驾驶舱</h1>
        <p v-if="results" class="dashboard-period">
          统计期间 {{ results.dateFrom }} 至 {{ results.dateTo }}
        </p>
      </div>
      <div class="dashboard-tools">
        <el-date-picker
          v-model="month"
          type="month"
          value-format="YYYY-MM"
          aria-label="统计月份"
          :teleported="false"
          :clearable="false"
          :disabled-date="(date: Date) => businessMonth(date) > businessMonth()"
          @change="loadData()"
        />
        <el-button :loading="loading" @click="layout ? loadData(true) : loadWorkspace()"
          >刷新数据</el-button
        >
        <el-button
          v-if="layout && available.length && !screen"
          :disabled="loading"
          @click="draft = [...layout.metrics]"
          >配置布局</el-button
        >
        <el-button :disabled="!results?.items.length" @click="fullscreen">{{
          screen ? '退出全屏' : '全屏轮播'
        }}</el-button>
      </div>
    </header>

    <el-alert v-if="failure" :title="failure" type="error" :closable="false" />
    <el-skeleton v-if="loading && !results" :rows="4" animated />
    <el-empty
      v-else-if="!results?.items.length && !failure"
      description="暂无已选或有权限查看的指标"
    />

    <section
      v-for="item in display"
      :key="item.code"
      class="panel dashboard-block"
      :aria-label="name(item.code)"
    >
      <header class="dashboard-block-heading">
        <div>
          <h2>{{ name(item.code) }}</h2>
          <p>{{ item.note }}</p>
        </div>
        <div class="dashboard-stamp">
          <router-link v-if="!screen" :to="reportTarget(item.code)">查看报表</router-link>
          <time :datetime="item.asOf"
            >数据截至 {{ formatDateTimeSeconds(item.asOf) }} ·
            {{ sourceLabel[item.dataSource] }}</time
          >
        </div>
      </header>
      <dl v-if="item.values.length" class="dashboard-values">
        <div v-for="value in item.values" :key="value.label">
          <dt>{{ value.label }}</dt>
          <dd>{{ displayValue(value) }}</dd>
        </div>
      </dl>
      <div v-if="item.plots.length" class="dashboard-plots">
        <figure v-for="plot in item.plots" :key="plot.name">
          <figcaption>{{ plot.name }}</figcaption>
          <EChart v-if="hasPlotData(plot)" :option="chartOption(plot, screen)" :label="plot.name" />
          <el-empty v-else description="本期无统计数据" :image-size="56" />
          <details v-if="hasPlotData(plot) && !screen">
            <summary>查看数值</summary>
            <dl class="dashboard-plot-values">
              <div v-for="(point, index) in plot.points" :key="index">
                <dt>{{ point.label }}</dt>
                <dd>{{ displayValue(point) }}</dd>
              </div>
            </dl>
          </details>
        </figure>
      </div>
    </section>

    <el-dialog
      :model-value="draft !== undefined"
      title="配置驾驶舱布局"
      width="min(560px, 95vw)"
      :close-on-click-modal="false"
      @close="draft = undefined"
    >
      <el-alert v-if="failure" :title="failure" type="error" :closable="false" />
      <div v-for="metric in available" :key="metric.code">
        <el-checkbox
          :model-value="draft?.includes(metric.code)"
          @change="(enabled: unknown) => changeVisibility(metric.code, enabled === true)"
          >{{ metric.name }}</el-checkbox
        >
      </div>
      <ol v-if="draft?.length" class="layout-order">
        <li
          v-for="(code, index) in draft"
          :key="code"
          :draggable="!saving"
          @dragstart="startDrag($event, code)"
          @dragover.prevent
          @drop.prevent="drop(code)"
          @dragend="dragging = undefined"
        >
          <span>{{ name(code) }}</span>
          <el-button
            size="small"
            :disabled="index === 0 || saving"
            :aria-label="'上移' + name(code)"
            @click="shift(code, -1)"
            >上移</el-button
          >
          <el-button
            size="small"
            :disabled="index === draft.length - 1 || saving"
            :aria-label="'下移' + name(code)"
            @click="shift(code, 1)"
            >下移</el-button
          >
        </li>
      </ol>
      <template #footer
        ><el-button @click="draft = undefined">取消</el-button
        ><el-button type="primary" :loading="saving" @click="save">保存布局</el-button></template
      >
    </el-dialog>
  </div>
</template>

<style scoped>
.dashboard {
  display: grid;
  gap: 16px;
  padding-bottom: 16px;
}
.dashboard-heading,
.dashboard-block-heading {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin: 0;
}
.dashboard-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.dashboard-tools .el-button {
  margin-left: 0;
}
.dashboard-period,
.dashboard-block-heading p {
  color: var(--el-text-color-secondary);
  margin: 6px 0 0;
}
.dashboard-block {
  min-width: 0;
  padding: 20px;
}
.dashboard-block h2 {
  font-size: 19px;
  margin: 0;
}
.dashboard-stamp {
  display: grid;
  gap: 4px;
  justify-items: end;
  font-size: 0.85rem;
  color: var(--el-text-color-secondary);
}
.dashboard-stamp a {
  color: var(--el-color-primary);
}
.dashboard-values {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(145px, 1fr));
  gap: 14px;
  margin: 20px 0 12px;
}
.dashboard-values > div {
  border-inline-start: 2px solid var(--el-border-color);
  padding-inline-start: 12px;
}
.dashboard-values dt {
  color: var(--el-text-color-secondary);
  font-size: 0.9rem;
}
.dashboard-values dd {
  margin: 5px 0 0;
  font-size: 1.35rem;
  font-weight: 620;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.dashboard-plots {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 290px), 1fr));
  gap: 18px;
}
.dashboard-plots figure {
  margin: 14px 0 0;
  min-width: 0;
}
.dashboard-plots figcaption {
  margin: 6px 0;
  font-weight: 550;
}
.dashboard-plot-values {
  display: grid;
  gap: 3px;
  max-height: 180px;
  overflow: auto;
}
.dashboard-plot-values div {
  display: flex;
  gap: 6px;
  justify-content: space-between;
}
.layout-order {
  padding-left: 24px;
}
.layout-order li {
  margin: 6px 0;
  padding: 4px;
  cursor: grab;
}
.layout-order span {
  display: inline-block;
  width: 8em;
}
.dashboard--screen {
  --erp-bg: #232324;
  --erp-fill-1: #232324;
  --erp-fill-2: #2c2c2e;
  --erp-border-1: #333335;
  --erp-border-2: #333335;
  --erp-text-1: #f6f6f6;
  --erp-text-2: #c5c5c5;
  --erp-text-3: #c5c5c5;
  --erp-primary-6: #3c7eff;
  --el-bg-color: var(--erp-bg);
  --el-bg-color-overlay: var(--erp-bg);
  --el-bg-color-page: #17171a;
  --el-fill-color-blank: var(--erp-bg);
  --el-fill-color-light: var(--erp-fill-2);
  --el-fill-color-lighter: var(--erp-fill-2);
  --el-fill-color: var(--erp-fill-2);
  --el-text-color-primary: var(--erp-text-1);
  --el-text-color-regular: var(--erp-text-2);
  --el-text-color-secondary: var(--erp-text-2);
  --el-text-color-placeholder: var(--erp-text-3);
  --el-border-color: var(--erp-border-1);
  --el-border-color-light: var(--erp-border-1);
  --el-border-color-lighter: var(--erp-border-1);
  --el-color-primary: var(--erp-primary-6);
  --el-color-primary-light-3: #6599ff;
  --el-color-primary-light-5: #283d65;
  --el-color-primary-light-9: #202e49;
  box-sizing: border-box;
  overflow: auto;
  padding: clamp(16px, 4vw, 60px);
  align-content: start;
  background: var(--el-bg-color-page);
  color: var(--erp-text-1);
}
.dashboard--screen .dashboard-block {
  background: var(--erp-bg);
  border-color: var(--erp-border-1);
}
.dashboard--screen .dashboard-values dd {
  font-size: clamp(1.4rem, 3.5vw, 3.4rem);
}
.dashboard--screen :deep(.el-picker-panel) {
  --el-datepicker-active-color: #0e42d2;
  --el-datepicker-hover-text-color: #6599ff;
}
.dashboard--screen :deep(.el-month-table td.today .el-date-table-cell__text) {
  color: #6599ff;
}
</style>
