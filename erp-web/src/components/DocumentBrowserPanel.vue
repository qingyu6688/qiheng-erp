<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { allRecords } from '../api/admin'
import { browseDocuments } from '../api/document-browser'
import { isCancelled, messageOf } from '../api/http'
import { documentSpec, documentStateLabel, documentStateTag } from '../types/document-ui'
import type { SalesDocument } from '../types/sales'
import type { DataRow } from '../types/admin'
import { formatMoney, formatDateTime } from '../utils/format'
import { useSessionStore } from '../stores/session'

const props = defineProps<{ kind: string }>()
const route = useRoute(),
  router = useRouter(),
  session = useSessionStore()
const spec = computed(() => documentSpec(props.kind)),
  order = computed(() => ['sales-orders', 'purchase-orders'].includes(props.kind))
const hasPartner = computed(
  () =>
    ![
      'purchase-requisitions',
      'stock-adjustments',
      'stock-transfers',
      'stock-counts',
      'document-reversals',
      'account-transfers',
    ].includes(props.kind),
)
const hasWarehouse = computed(
  () =>
    ![
      'receipts',
      'payments',
      'refund-requests',
      'invoice-registrations',
      'opening-entries',
      'account-transfers',
      'purchase-requisitions',
      'document-reversals',
    ].includes(props.kind),
)
const partnerLabel = computed(() =>
  props.kind.startsWith('purchase') || props.kind === 'goods-receipts'
    ? '供应商'
    : props.kind.startsWith('sales') || props.kind === 'shipments'
      ? '客户'
      : '往来单位',
)
const rows = ref<SalesDocument[]>([]),
  partners = ref<DataRow[]>([]),
  warehouses = ref<DataRow[]>([]),
  loading = ref(false),
  error = ref(''),
  total = ref<number>(),
  counts = ref<Record<string, number>>({}),
  next = ref<string | null>(null)
const cursors = ref<string[]>([''])
const filter = reactive({
  keyword: '',
  status: '',
  partnerId: '',
  warehouseId: '',
  dateFrom: '',
  dateTo: '',
  fulfillmentStatus: '',
  settlementStatus: '',
  signOverdue: false,
})
let internalNavigation = false
let revision = 0,
  controller = new AbortController()
const totalCount = computed(() =>
  Object.values(counts.value).reduce((sum, value) => sum + value, 0),
)
const fulfillment = { none: '未履约', partial: '部分履约', full: '全部履约' }
const settlements = { none: '未结算', partial: '部分结算', full: '已结算' }
function amount(row: SalesDocument): string {
  return formatMoney(
    String(
      row.totalAmount ??
        row.receivableAmount ??
        row.payableAmount ??
        row.creditAmount ??
        row.debitAmount ??
        row.amount ??
        '',
    ),
  )
}
function name(row: SalesDocument): string {
  return String(
    row.customer?.name ??
      (row.supplier as { name?: string } | undefined)?.name ??
      row.customerName ??
      row.supplierName ??
      row.partnerName ??
      row.cashAccountName ??
      row.fromAccountName ??
      row.warehouseName ??
      row.requesterName ??
      '—',
  )
}
function query(): Record<string, unknown> {
  return {
    ...Object.fromEntries(
      Object.entries(filter).filter(([, value]) => value !== '' && value !== false),
    ),
    cursor: cursors.value.at(-1) || undefined,
    limit: 20,
    withTotal: true,
  }
}
function readQuery(): void {
  for (const key of [
    'keyword',
    'status',
    'partnerId',
    'warehouseId',
    'dateFrom',
    'dateTo',
    'fulfillmentStatus',
    'settlementStatus',
  ] as const)
    filter[key] = typeof route.query[key] === 'string' ? (route.query[key] as string) : ''
  if (
    !Object.hasOwn(route.query, 'status') &&
    ['receipts', 'payments', 'account-transfers'].includes(props.kind)
  )
    filter.status = 'pending_confirm'
  filter.signOverdue = route.query.signOverdue === 'true' || route.query.overdue === 'true'
  cursors.value = ['']
  const cursor = route.query.cursor,
    history = route.query.pageCursors
  const validId = (value: string): boolean =>
    /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n
  if (typeof cursor === 'string' && validId(cursor)) {
    const values = typeof history === 'string' && history.length <= 10000 ? history.split(',') : []
    if (
      values.length &&
      values.at(-1) === cursor &&
      values.every(
        (value, index) =>
          validId(value) && (index === 0 || BigInt(value) < BigInt(values[index - 1]!)),
      )
    )
      cursors.value.push(...values)
    else cursors.value.push(cursor)
  }
}
async function load(): Promise<void> {
  const current = ++revision
  controller.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  try {
    const result = await browseDocuments(props.kind, query(), controller.signal)
    if (current === revision) {
      rows.value = result.items
      next.value = result.nextCursor
      total.value = result.total
      counts.value = result.statusCounts
    }
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
}
async function saveQuery(): Promise<void> {
  const values = Object.fromEntries(
    Object.entries(query())
      .filter(([key, value]) => !['limit', 'withTotal'].includes(key) && value !== undefined)
      .map(([key, value]) => [key, String(value)]),
  )
  // 显式保留“全部”，避免收付款刷新后又回到默认待确认。
  values.status = filter.status
  if (cursors.value.length > 1) values.pageCursors = cursors.value.slice(1).join(',')
  internalNavigation = true
  try {
    await router.replace({ path: route.path, query: values })
  } finally {
    internalNavigation = false
  }
  await load()
}
function search(): void {
  cursors.value = ['']
  rows.value = []
  void saveQuery()
}
function reset(): void {
  Object.assign(filter, {
    keyword: '',
    status: '',
    partnerId: '',
    warehouseId: '',
    dateFrom: '',
    dateTo: '',
    fulfillmentStatus: '',
    settlementStatus: '',
    signOverdue: false,
  })
  search()
}
function state(value: string): void {
  filter.status = value
  search()
}
function page(forward: boolean): void {
  if (forward && next.value) cursors.value.push(next.value)
  else if (!forward && cursors.value.length > 1) cursors.value.pop()
  else return
  void saveQuery()
}
async function options(): Promise<void> {
  const kind = props.kind,
    company = session.companyId
  const result = await Promise.allSettled([
    hasPartner.value && session.can('partner:view') ? allRecords('partners') : Promise.resolve([]),
    hasWarehouse.value && session.can('warehouse:view')
      ? allRecords('warehouses', true)
      : Promise.resolve([]),
  ])
  if (kind !== props.kind || company !== session.companyId) return
  partners.value = result[0].status === 'fulfilled' ? result[0].value : []
  warehouses.value = result[1].status === 'fulfilled' ? result[1].value : []
}
watch(
  () => [props.kind, session.companyId],
  () => {
    rows.value = []
    partners.value = []
    warehouses.value = []
    readQuery()
    void options()
    void load()
  },
  { immediate: true },
)
watch(
  () => route.query,
  () => {
    if (!internalNavigation) {
      readQuery()
      void load()
    }
  },
)
onBeforeUnmount(() => {
  revision++
  controller.abort()
})
</script>
<template>
  <section class="panel table-panel document-browser" :aria-busy="loading">
    <div class="document-status-tabs" role="tablist" aria-label="按状态筛选">
      <button type="button" role="tab" :aria-selected="filter.status === ''" @click="state('')">
        全部 <span>{{ totalCount }}</span>
      </button>
      <button
        v-for="status in spec.states"
        :key="status"
        type="button"
        role="tab"
        :aria-selected="filter.status === status"
        @click="state(status)"
      >
        {{ documentStateLabel(kind, status) }} <span>{{ counts[status] ?? 0 }}</span>
      </button>
    </div>
    <form class="filters" @submit.prevent="search">
      <div class="filter-field">
        <label :for="kind + '-search'">单号 / 备注</label
        ><el-input :id="kind + '-search'" v-model="filter.keyword" clearable />
      </div>
      <div v-if="hasPartner && partners.length" class="filter-field">
        <label :for="kind + '-partner'">{{ partnerLabel }}</label
        ><el-select :id="kind + '-partner'" v-model="filter.partnerId" filterable clearable
          ><el-option
            v-for="row in partners"
            :key="row.id"
            :value="row.id"
            :label="row.name ?? row.code"
        /></el-select>
      </div>
      <div v-if="hasWarehouse && warehouses.length" class="filter-field">
        <label :for="kind + '-warehouse'">仓库</label
        ><el-select :id="kind + '-warehouse'" v-model="filter.warehouseId" filterable clearable
          ><el-option
            v-for="row in warehouses"
            :key="row.id"
            :value="row.id"
            :label="row.name ?? row.code"
        /></el-select>
      </div>
      <div class="filter-field small">
        <label :for="kind + '-from'">开始日期</label
        ><input :id="kind + '-from'" v-model="filter.dateFrom" type="date" />
      </div>
      <div class="filter-field small">
        <label :for="kind + '-to'">结束日期</label
        ><input :id="kind + '-to'" v-model="filter.dateTo" type="date" />
      </div>
      <template v-if="order"
        ><div class="filter-field small">
          <label :for="kind + '-fulfillment'">履约</label
          ><el-select :id="kind + '-fulfillment'" v-model="filter.fulfillmentStatus" clearable
            ><el-option
              v-for="(label, value) in fulfillment"
              :key="value"
              :value="value"
              :label="label"
          /></el-select>
        </div>
        <div class="filter-field small">
          <label :for="kind + '-settlement'">结算</label
          ><el-select :id="kind + '-settlement'" v-model="filter.settlementStatus" clearable
            ><el-option
              v-for="(label, value) in settlements"
              :key="value"
              :value="value"
              :label="label"
          /></el-select></div
      ></template>
      <el-checkbox v-if="kind === 'shipments'" v-model="filter.signOverdue">仅签收超期</el-checkbox
      ><el-button native-type="submit" type="primary" :loading="loading">查询</el-button
      ><el-button @click="reset">重置</el-button>
    </form>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <el-table :data="rows" row-key="id">
      <el-table-column label="单号" min-width="240"
        ><template #default="{ row }"
          ><router-link class="text-link" :to="'/' + kind + '/' + row.id">{{
            row.documentNo ?? row.invoiceNo
          }}</router-link></template
        ></el-table-column
      >
      <el-table-column
        :label="
          kind === 'stock-transfers'
            ? '调拨路线'
            : hasPartner
              ? partnerLabel + ' / 账户'
              : '仓库 / 经办'
        "
        min-width="180"
        ><template #default="{ row }">{{
          row.fromWarehouseName ? row.fromWarehouseName + ' → ' + row.toWarehouseName : name(row)
        }}</template></el-table-column
      >
      <el-table-column label="业务日期" min-width="120"
        ><template #default="{ row }">{{
          row.businessDate ?? row.invoiceDate
        }}</template></el-table-column
      >
      <el-table-column label="状态" min-width="135"
        ><template #default="{ row }"
          ><el-tag :type="documentStateTag(kind, row.status)">{{
            documentStateLabel(kind, row.status)
          }}</el-tag></template
        ></el-table-column
      >
      <el-table-column v-if="order" label="履约 / 结算" min-width="160"
        ><template #default="{ row }"
          >{{
            fulfillment[row.fulfillmentStatus as keyof typeof fulfillment] ??
            row.fulfillmentStatus ??
            '—'
          }}
          /
          {{
            settlements[row.settlementStatus as keyof typeof settlements] ??
            row.settlementStatus ??
            '—'
          }}</template
        ></el-table-column
      >
      <el-table-column
        v-if="kind === 'shipments'"
        prop="signDate"
        label="签收日期"
        min-width="120"
      />
      <el-table-column v-if="kind === 'stock-counts'" label="盘点快照" min-width="155"
        ><template #default="{ row }">{{
          formatDateTime(String(row.snapshotAt ?? ''))
        }}</template></el-table-column
      >
      <el-table-column
        v-if="
          ![
            'stock-transfers',
            'stock-counts',
            'document-reversals',
            'purchase-requisitions',
            'stock-adjustments',
          ].includes(kind)
        "
        label="金额（元）"
        min-width="140"
        align="right"
        ><template #default="{ row }">{{ amount(row) }}</template></el-table-column
      >
      <el-table-column label="操作" width="90"
        ><template #default="{ row }"
          ><router-link class="text-link" :to="'/' + kind + '/' + row.id"
            >查看</router-link
          ></template
        ></el-table-column
      >
      <template #empty
        ><div class="empty-state">
          <strong>{{ loading ? '正在加载…' : error ? '加载失败' : '没有符合条件的单据' }}</strong
          ><span v-if="!loading">{{
            error ? '请检查筛选或重新查询。' : '调整筛选条件，或新建一张单据。'
          }}</span
          ><el-button v-if="!loading" text @click="error ? load() : reset()">{{
            error ? '重新加载' : '清除筛选'
          }}</el-button>
        </div></template
      >
    </el-table>
    <footer class="table-footer">
      <span v-if="total !== undefined"
        >共 {{ total }} 条<template v-if="rows.length"
          >，第 {{ (cursors.length - 1) * 20 + 1 }}—{{
            (cursors.length - 1) * 20 + rows.length
          }}
          条</template
        ></span
      ><span v-else>第 {{ cursors.length }} 页</span>
      <div>
        <el-button :disabled="loading || cursors.length === 1" @click="page(false)"
          >上一页</el-button
        ><el-button :disabled="loading || !next" @click="page(true)">下一页</el-button>
      </div>
    </footer>
  </section>
</template>
<style scoped>
.document-status-tabs {
  display: flex;
  gap: 20px;
  padding: 0 24px;
  border-bottom: 1px solid var(--el-border-color-light);
  overflow-x: auto;
}
.document-status-tabs button {
  border: 0;
  border-bottom: 2px solid transparent;
  background: none;
  padding: 14px 0;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
  cursor: pointer;
}
.document-status-tabs button[aria-selected='true'] {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary);
  font-weight: 600;
}
.document-status-tabs span {
  font-size: 12px;
  margin-left: 4px;
}
.filters .filter-field {
  width: 190px;
}
.filters .small {
  width: 140px;
}
.filters input[type='date'] {
  height: 40px;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  padding: 0 8px;
  background: white;
  color: var(--el-text-color-primary);
  width: 100%;
}
</style>
