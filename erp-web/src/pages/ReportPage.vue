<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { report, createExport } from '../api/reporting'
import { allRecords } from '../api/admin'
import { isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import {
  reportCell,
  reportLabels,
  reportPermission,
  reportSpecs,
  documentLink,
  reportToday,
} from '../types/reporting'
import type { ReportResult } from '../types/reporting'
import type { DataRow } from '../types/admin'
import { useSessionStore } from '../stores/session'
import { formatDateTime } from '../utils/format'
import BusinessUserSelect from '../components/BusinessUserSelect.vue'
const PurchasePriceAdjustmentDrawer = defineAsyncComponent(
  () => import('../components/procurement/PurchasePriceAdjustmentDrawer.vue'),
)
const priceDetailId = ref<string>()
const props = defineProps<{ type: string }>(),
  session = useSessionStore(),
  router = useRouter(),
  route = useRoute()
const spec = computed(() => reportSpecs.find((item) => item.type === props.type)!),
  result = ref<ReportResult>(),
  loading = ref(false),
  exporting = ref(false),
  error = ref(''),
  notice = ref('')
const dateFrom = ref(''),
  dateTo = ref(''),
  agingDate = ref(''),
  agingBasis = ref<'business' | 'due'>('business'),
  warehouseId = ref(''),
  skuId = ref(''),
  partnerId = ref(''),
  cashAccountId = ref(''),
  businessUserId = ref(''),
  groupBy = ref('standard')
const warehouses = ref<DataRow[]>([]),
  skus = ref<DataRow[]>([]),
  partners = ref<DataRow[]>([]),
  accounts = ref<DataRow[]>([])
const cursors = ref<(string | null)[]>([null]),
  detail = computed(
    () =>
      ['inventory-flow', 'sales-summary', 'purchase-summary'].includes(props.type) &&
      route.query.detail === 'true',
  )
let revision = 0,
  exportKey = '',
  exportPayload = ''
let pageRequests = new AbortController(),
  reportRequest: AbortController | undefined
const warehouseReport = computed(
  () =>
    !['receivable-aging', 'payable-aging', 'partner-statement', 'cash-journal'].includes(
      props.type,
    ),
)
const partnerReport = computed(
  () => !['inventory-summary', 'inventory-flow', 'cash-journal'].includes(props.type),
)
const commercialSummary = computed(
  () => ['sales-summary', 'purchase-summary'].includes(props.type) && !detail.value,
)
const commercialPeople = computed(() =>
  [
    'sales-summary',
    'purchase-summary',
    'gross-margin',
    'receivable-aging',
    'payable-aging',
  ].includes(props.type),
)
const agingReport = computed(() => ['receivable-aging', 'payable-aging'].includes(props.type))
const businessGroups = computed(() => ['sales-summary', 'gross-margin'].includes(props.type))
const groupFields: Record<string, string[]> = {
  customer: ['partnerName'],
  sku: ['skuCode', 'skuName'],
  department: ['departmentName'],
}
const brandGroup = computed(() =>
  ['inventory-summary', 'sales-summary', 'purchase-summary', 'gross-margin'].includes(props.type),
)
const agingBounds = computed<number[]>(() => {
  const value = result.value?.summary.agingBounds
  return Array.isArray(value) && value.every((v) => typeof v === 'number')
    ? value
    : [30, 60, 90, 180]
})
function columnLabel(field: string): string {
  if (field.startsWith('aging') && /^aging[0-9]+$/.test(field)) {
    const index = Number(field.slice(5)),
      lower = index === 0 ? 0 : agingBounds.value[index - 1]! + 1,
      upper = agingBounds.value[index]
    return upper === undefined ? lower + '天及以上' : lower + '—' + upper + '天'
  }
  return reportLabels[field] || (field === 'departmentName' ? '部门' : field)
}
const columns = computed(() =>
  (brandGroup.value && groupBy.value === 'brand' && !detail.value
    ? props.type === 'inventory-summary'
      ? ['brandName', 'onHandQty', 'reservedQty', 'blockedQty', 'availableQty', 'costAmount']
      : [
          'brandName',
          'qty',
          'amount',
          'untaxedAmount',
          'taxAmount',
          'costAmount',
          ...(props.type === 'gross-margin' ? ['grossMargin'] : ['sourceCount']),
        ]
    : businessGroups.value && groupFields[groupBy.value] && !detail.value
      ? [
          ...groupFields[groupBy.value]!,
          'qty',
          'amount',
          'untaxedAmount',
          'taxAmount',
          'costAmount',
          ...(props.type === 'gross-margin' ? ['grossMargin'] : ['sourceCount']),
        ]
      : agingReport.value
        ? [
            groupBy.value === 'business_user'
              ? 'businessUserName'
              : groupBy.value === 'department'
                ? 'departmentName'
                : 'partnerName',
            ...(groupBy.value === 'standard'
              ? [
                  'businessUserName',
                  'departmentName',
                  'sourceNo',
                  'agingDate',
                  'dueDate',
                  'overdueDays',
                ]
              : []),
            'openAmount',
            ...Array.from({ length: agingBounds.value.length + 1 }, (_, i) => 'aging' + i),
          ]
        : commercialPeople.value && groupBy.value === 'business_user' && !detail.value
          ? [
              'businessUserName',
              'qty',
              'amount',
              'untaxedAmount',
              'taxAmount',
              'costAmount',
              ...(props.type === 'gross-margin' ? ['grossMargin'] : []),
            ]
          : detail.value && props.type === 'inventory-flow'
            ? [
                'businessDate',
                'warehouseName',
                'skuCode',
                'skuName',
                'sourceNo',
                'qty',
                'blockedQty',
                'costAmount',
                'postingAction',
              ]
            : detail.value
              ? ['sourceNo', ...spec.value.fields]
              : spec.value.fields
  ).filter(
    (field) =>
      (!detail.value || field !== 'sourceCount') &&
      (!/cost|grossMargin/i.test(field) || session.can('cost:view')),
  ),
)
async function switchReport(next: string): Promise<void> {
  await router.push({
    path: '/reports/' + next,
    query: {
      dateFrom: dateFrom.value,
      dateTo: dateTo.value,
      ...(warehouseReport.value &&
      !['receivable-aging', 'payable-aging', 'partner-statement', 'cash-journal'].includes(next) &&
      warehouseId.value
        ? { warehouseId: warehouseId.value }
        : {}),
    },
  })
}
function query(): Record<string, unknown> {
  return {
    dateFrom: dateFrom.value || undefined,
    dateTo: dateTo.value || undefined,
    agingDate: agingReport.value ? agingDate.value || undefined : undefined,
    agingBasis: agingReport.value ? agingBasis.value : undefined,
    warehouseId: warehouseId.value || undefined,
    skuId: skuId.value || undefined,
    partnerId: partnerId.value || undefined,
    cashAccountId: cashAccountId.value || undefined,
    detail: detail.value || undefined,
    businessUserId: commercialPeople.value ? businessUserId.value || undefined : undefined,
    groupBy:
      (commercialPeople.value || brandGroup.value) && !detail.value ? groupBy.value : undefined,
    feesOnly: route.query.feesOnly === 'true' || undefined,
  }
}
async function load(): Promise<void> {
  reportRequest?.abort()
  reportRequest = new AbortController()
  const current = ++revision
  loading.value = true
  error.value = ''
  try {
    const data = await report(
      props.type,
      { ...query(), cursor: cursors.value.at(-1) ?? undefined, limit: 20 },
      reportRequest.signal,
    )
    if (current === revision) result.value = data
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
}
function search(): void {
  cursors.value = [null]
  result.value = undefined
  void load()
}
function page(forward: boolean): void {
  if (forward && result.value?.nextCursor) cursors.value.push(result.value.nextCursor)
  else if (!forward && cursors.value.length > 1) cursors.value.pop()
  else return
  result.value = undefined
  void load()
}
function link(row: Record<string, unknown>): string | undefined {
  if (
    ['price_adjustment', 'price_adjustment_line', 'price_adjustment_cost'].includes(
      String(row.sourceType),
    ) &&
    !priceSourceId(row)
  )
    return undefined
  if (
    session.can('stock_misc_doc:view') &&
    typeof row.sourceUrl === 'string' &&
    /^\/stock-misc-(in|out)\/[1-9][0-9]*$/.test(row.sourceUrl)
  )
    return row.sourceUrl
  return documentLink(row.sourceType, row.sourceId)
}
function priceSourceId(row: Record<string, unknown>): string | undefined {
  if (
    !session.can('price_adjustment:view') ||
    !session.can('cost:view') ||
    !session.can('goods_receipt:view')
  )
    return undefined
  return ['price_adjustment', 'price_adjustment_line', 'price_adjustment_cost'].includes(
    String(row.sourceType),
  ) &&
    typeof row.sourceId === 'string' &&
    /^[1-9][0-9]*$/.test(row.sourceId)
    ? row.sourceId
    : undefined
}
function flow(row: Record<string, unknown>): void {
  void router.push({
    path: '/reports/inventory-flow',
    query: {
      warehouseId: String(row.warehouseId),
      skuId: String(row.skuId),
      detail: props.type === 'inventory-flow' ? 'true' : undefined,
      dateFrom: dateFrom.value,
      dateTo: dateTo.value,
    },
  })
}
function documents(row: Record<string, unknown>): void {
  if (typeof row.detailUrl === 'string' && row.detailUrl.startsWith('/reports/' + props.type + '?'))
    void router.push(row.detailUrl)
}
async function exportRows(): Promise<void> {
  if (exporting.value) return
  const payload = JSON.stringify([props.type, query()])
  if (payload !== exportPayload) {
    exportPayload = payload
    exportKey = newIdempotencyKey()
  }
  const company = session.companyId,
    type = props.type,
    active = (): boolean =>
      company === session.companyId &&
      type === props.type &&
      payload === JSON.stringify([props.type, query()])
  exporting.value = true
  error.value = ''
  notice.value = ''
  try {
    const task = await createExport(props.type, query(), exportKey, pageRequests.signal)
    if (!active()) return
    exportPayload = ''
    notice.value = '导出任务已创建'
    await router.push({ path: '/file-jobs', query: { tab: 'export', jobId: task.taskId } })
  } catch (cause: unknown) {
    if (active() && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    exporting.value = false
  }
}
async function initialize(): Promise<void> {
  const initializing = ++revision
  pageRequests.abort()
  pageRequests = new AbortController()
  reportRequest?.abort()
  result.value = undefined
  cursors.value = [null]
  exportKey = ''
  exportPayload = ''
  notice.value = ''
  warehouseId.value = typeof route.query.warehouseId === 'string' ? route.query.warehouseId : ''
  skuId.value = typeof route.query.skuId === 'string' ? route.query.skuId : ''
  partnerId.value = typeof route.query.partnerId === 'string' ? route.query.partnerId : ''
  cashAccountId.value =
    typeof route.query.cashAccountId === 'string' ? route.query.cashAccountId : ''
  businessUserId.value =
    typeof route.query.businessUserId === 'string' ? route.query.businessUserId : ''
  const group = String(route.query.groupBy)
  groupBy.value =
    (group === 'business_user' && commercialPeople.value) ||
    (group === 'department' && agingReport.value) ||
    (group === 'brand' && brandGroup.value) ||
    (Object.hasOwn(groupFields, group) && businessGroups.value)
      ? group
      : 'standard'
  const local = reportToday()
  dateFrom.value =
    typeof route.query.dateFrom === 'string' ? route.query.dateFrom : local.slice(0, 8) + '01'
  dateTo.value = typeof route.query.dateTo === 'string' ? route.query.dateTo : local
  agingDate.value = typeof route.query.agingDate === 'string' ? route.query.agingDate : local
  agingBasis.value = route.query.agingBasis === 'due' ? 'due' : 'business'
  warehouses.value = []
  skus.value = []
  partners.value = []
  accounts.value = []
  const company = session.companyId
  await Promise.allSettled([
    warehouseReport.value && session.can('warehouse:view')
      ? allRecords('warehouses', true).then((rows) => {
          if (initializing === revision) warehouses.value = rows
        })
      : Promise.resolve(),
    warehouseReport.value && session.can('sku:view')
      ? allRecords('skus').then((rows) => {
          if (initializing === revision) skus.value = rows
        })
      : Promise.resolve(),
    partnerReport.value && session.can('partner:view')
      ? allRecords('partners').then((rows) => {
          if (initializing === revision) partners.value = rows
        })
      : Promise.resolve(),
    props.type === 'cash-journal' && session.can('cash_account:view')
      ? allRecords('cash-accounts', true).then((rows) => {
          if (initializing === revision) accounts.value = rows
        })
      : Promise.resolve(),
  ]).then((outcomes) => {
    const failed = outcomes.find((item) => item.status === 'rejected')
    if (initializing === revision && failed?.status === 'rejected' && !isCancelled(failed.reason))
      notice.value = '部分筛选资料未加载，可刷新后重试'
  })
  if (initializing === revision && company === session.companyId) await load()
}
watch(
  () => [props.type, session.companyId, route.query],
  () => {
    void initialize()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  revision++
  pageRequests.abort()
  reportRequest?.abort()
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>{{ spec.title }}{{ detail ? '明细' : '' }}</h1>
      <p v-if="agingReport">按截止日查看应收或应付未清金额。</p>
      <p v-else-if="spec.snapshot">按当前数据快照统计。</p>
      <p v-else>按业务日期统计，退货与冲销按实际方向计入。</p>
    </div>
    <div class="sales-actions">
      <el-select aria-label="切换业务报表" :model-value="type" @change="switchReport"
        ><el-option
          v-for="item in reportSpecs.filter((item) => session.can(reportPermission(item.type)))"
          :key="item.type"
          :value="item.type"
          :label="item.title" /></el-select
      ><el-button
        v-if="session.can(reportPermission(type, 'export'))"
        :loading="exporting"
        @click="exportRows"
        >导出报表</el-button
      >
    </div>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" /><el-alert
    v-if="notice"
    :title="notice"
    type="info"
    :closable="false"
  />
  <section class="panel table-panel">
    <form class="filters" @submit.prevent="search">
      <template v-if="commercialPeople || brandGroup"
        ><div v-if="commercialPeople" class="filter-field">
          <label>业务员</label
          ><BusinessUserSelect v-model="businessUserId" include-unassigned label="业务员筛选" />
        </div>
        <div v-if="!detail" class="filter-field">
          <label>汇总方式</label
          ><el-select v-model="groupBy" aria-label="汇总方式"
            ><el-option
              value="standard"
              :label="
                agingReport
                  ? '按未清项'
                  : type === 'inventory-summary'
                    ? '按仓库与商品'
                    : '按往来与商品'
              "
            /><el-option
              v-if="agingReport || businessGroups"
              value="department"
              label="按部门"
            /><el-option v-if="commercialPeople" value="business_user" label="按业务员" />
            <el-option v-if="businessGroups" value="customer" label="按客户" />
            <el-option v-if="businessGroups" value="sku" label="按商品" />
            <el-option v-if="brandGroup" value="brand" label="按品牌" />
          </el-select></div
      ></template>
      <div v-if="agingReport" class="filter-field">
        <label for="report-aging-basis">账龄口径</label>
        <select id="report-aging-basis" v-model="agingBasis" class="el-input__inner">
          <option value="business">按业务日期</option>
          <option value="due">按到期日</option>
        </select>
      </div>
      <div v-if="agingReport" class="filter-field">
        <label for="report-aging-cutoff">截止日期</label>
        <input
          id="report-aging-cutoff"
          v-model="agingDate"
          class="el-input__inner"
          type="date"
          :max="reportToday()"
          required
        />
      </div>
      <template v-else-if="!spec.snapshot"
        ><div class="filter-field">
          <label for="report-date-from">开始日期</label
          ><input
            id="report-date-from"
            v-model="dateFrom"
            class="el-input__inner"
            type="date"
            required
          />
        </div>
        <div class="filter-field">
          <label for="report-date-to">结束日期</label
          ><input
            id="report-date-to"
            v-model="dateTo"
            class="el-input__inner"
            type="date"
            required
          /></div
      ></template>
      <div v-if="warehouses.length" class="filter-field">
        <label for="report-warehouse">仓库</label
        ><el-select id="report-warehouse" v-model="warehouseId" clearable filterable
          ><el-option
            v-for="row in warehouses"
            :key="row.id"
            :value="row.id"
            :label="row.name ?? row.code"
        /></el-select>
      </div>
      <div v-if="skus.length" class="filter-field">
        <label for="report-sku">商品</label
        ><el-select id="report-sku" v-model="skuId" clearable filterable
          ><el-option
            v-for="row in skus"
            :key="row.id"
            :value="row.id"
            :label="[row.code, row.name].filter(Boolean).join(' · ')"
        /></el-select>
      </div>
      <div v-if="partners.length" class="filter-field">
        <label for="report-partner">往来单位</label
        ><el-select id="report-partner" v-model="partnerId" clearable filterable
          ><el-option
            v-for="row in partners"
            :key="row.id"
            :value="row.id"
            :label="row.name ?? row.code"
        /></el-select>
      </div>
      <div v-if="type === 'cash-journal' && accounts.length" class="filter-field">
        <label for="report-account">资金账户</label
        ><el-select id="report-account" v-model="cashAccountId" clearable filterable
          ><el-option
            v-for="row in accounts"
            :key="row.id"
            :value="row.id"
            :label="row.name ?? row.code"
        /></el-select>
      </div>
      <el-button native-type="submit" :loading="loading">查询</el-button>
    </form>
    <p v-if="result?.visibilityBasis" class="muted">{{ result.visibilityBasis }}</p>
    <p v-if="result" class="muted">
      数据时间 {{ formatDateTime(result.asOf)
      }}<template v-if="result.dataSource === 'replica'"> · 只读备库</template
      ><template v-if="result.statisticDate"> · 统计日 {{ result.statisticDate }}</template>
    </p>
    <dl v-if="result?.summary" class="report-summary">
      <template v-for="(value, field) in result.summary" :key="field"
        ><template
          v-if="
            reportLabels[field] && (session.can('cost:view') || !/cost|grossMargin/i.test(field))
          "
          ><dt>{{ reportLabels[field] }}</dt>
          <dd>{{ reportCell(field, value) }}</dd></template
        ></template
      >
    </dl>
    <el-table :data="result?.items ?? []" :aria-busy="loading"
      ><el-table-column
        v-for="field in columns"
        :key="field"
        :label="columnLabel(field)"
        min-width="140"
        ><template #default="{ row }"
          ><button
            v-if="field === 'sourceNo' && priceSourceId(row)"
            type="button"
            class="text-link"
            @click="priceDetailId = priceSourceId(row)"
          >
            {{ reportCell(field, row[field]) }}</button
          ><router-link
            v-else-if="field === 'sourceNo' && link(row)"
            class="text-link"
            :to="link(row)!"
            >{{ reportCell(field, row[field]) }}</router-link
          ><span v-else>{{ reportCell(field, row[field]) }}</span></template
        ></el-table-column
      >
      <el-table-column label="追溯" min-width="100"
        ><template #default="{ row }"
          ><el-button
            v-if="(commercialSummary || groupBy === 'business_user') && row.detailUrl"
            text
            @click="documents(row)"
            >单据明细</el-button
          ><el-button
            v-else-if="
              !detail &&
              row.skuId &&
              row.warehouseId &&
              ['inventory-summary', 'inventory-flow'].includes(type) &&
              session.can('report:inventory_flow')
            "
            text
            @click="flow(row)"
            >库存流水</el-button
          ><el-button
            v-else-if="priceSourceId(row)"
            text
            @click="priceDetailId = priceSourceId(row)"
            >查看原单</el-button
          ><router-link v-else-if="link(row)" class="text-link" :to="link(row)!"
            >查看原单</router-link
          ></template
        ></el-table-column
      ><template #empty>{{
        loading ? '正在统计…' : error ? '统计失败，请重试' : '此条件下暂无数据'
      }}</template>
    </el-table>
    <footer class="table-footer">
      <span>第 {{ cursors.length }} 页</span>
      <div>
        <el-button :disabled="loading || cursors.length === 1" @click="page(false)"
          >上一页</el-button
        ><el-button :disabled="loading || !result?.nextCursor" @click="page(true)"
          >下一页</el-button
        >
      </div>
    </footer>
  </section>
  <PurchasePriceAdjustmentDrawer
    v-if="priceDetailId"
    :key="String(session.companyId) + '-' + priceDetailId"
    :id="priceDetailId"
    @close="priceDetailId = undefined"
  />
</template>
<style scoped>
.muted {
  margin: 12px 24px;
}
.report-summary {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  padding: 12px 24px;
}
.report-summary dt {
  color: var(--el-text-color-secondary);
}
.report-summary dd {
  margin: 0 20px 0 0;
  font-variant-numeric: tabular-nums;
}
</style>
