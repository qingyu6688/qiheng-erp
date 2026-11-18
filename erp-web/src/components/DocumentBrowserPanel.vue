<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Download, Search } from '@element-plus/icons-vue'
import { allRecords } from '../api/admin'
import { customFields, exportOrderFields, formatField } from '../api/platform-rules'
import type { CustomField } from '../api/platform-rules'
import { browseDocuments } from '../api/document-browser'
import { isCancelled, messageOf } from '../api/http'
import { documentSpec, documentStateLabel, documentStateTag } from '../types/document-ui'
import { today, type SalesDocument } from '../types/sales'
import type { DataRow } from '../types/admin'
import { formatMoney, formatDateTime } from '../utils/format'
import { documentHeaderCellStyle } from '../utils/document-table-style'
import { useSessionStore } from '../stores/session'
import { businessDateRange, dateShortcuts } from '../utils/business-date-range'
import {
  arrangeDocumentColumns,
  useDocumentListPreferences,
  type DocumentListColumnPreference,
} from '../hooks/useDocumentListPreferences'
import QueryForm from './QueryForm.vue'
import DocumentBatchBar from './DocumentBatchBar.vue'
import StatusTag from './StatusTag.vue'
import TableToolbar from './TableToolbar.vue'

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
const showAmount = computed(
  () =>
    ![
      'stock-transfers',
      'stock-counts',
      'document-reversals',
      'purchase-requisitions',
      'stock-adjustments',
    ].includes(props.kind),
)
const partyHeading = computed(() => {
  if (props.kind === 'stock-transfers') return '调拨路线'
  return hasPartner.value ? partnerLabel.value + ' / 账户' : '仓库 / 经办'
})
const {
  expanded,
  hidden,
  columnOrder,
  columnFixed,
  setExpanded,
  toggleColumn,
  moveColumn,
  fixColumn,
  resetColumns,
} = useDocumentListPreferences(() => {
  const id = session.user?.id
  return id && session.companyId
    ? `erp.document-list.${id}.${session.companyId}.${props.kind}`
    : undefined
})
const rows = ref<SalesDocument[]>([]),
  partners = ref<DataRow[]>([]),
  warehouses = ref<DataRow[]>([]),
  loading = ref(false),
  error = ref(''),
  total = ref<number>(),
  counts = ref<Record<string, number>>({}),
  next = ref<string | null>(null)
const batchKind = computed(() => {
  const kind = props.kind
  return kind === 'sales-orders' || kind === 'purchase-orders' || kind === 'receipts' ? kind : undefined
})
const selected = ref<SalesDocument[]>([])
const selectedIds = computed(() => new Set(selected.value.map((row) => row.id)))
const selectionTable = ref<{
  clearSelection: () => void
  toggleAllSelection: () => void
  toggleRowSelection: (row: SalesDocument) => void
}>()
function clearSelection(): void {
  selectionTable.value?.clearSelection?.()
  selected.value = []
}
const canApproveSelection = computed(() =>
  session.can(spec.value.permission + (props.kind === 'receipts' ? ':confirm' : ':approve')) &&
  selected.value.some((row) => row.status === (props.kind === 'receipts' ? 'pending_confirm' : 'pending')),
)
const cursors = ref<string[]>([''])
const defaultPageSize = 20
const pageSizes = [defaultPageSize, 50, 100]
const pageSize = ref(defaultPageSize)
const firstRowNumber = computed(() => (cursors.value.length - 1) * pageSize.value + 1)
const activeQuery = shallowRef<Record<string, unknown>>()
const exporting = ref(false)
const fieldColumns = ref<CustomField[]>([]),
  customFilters = reactive<Record<string, string>>({})
const advancedFields = computed(() => fieldColumns.value.filter((field) => field.indexSlot))
const exportable = computed(() => fieldColumns.value.some((field) => field.importExport))
interface DocumentBrowserColumn extends DocumentListColumnPreference {
  minWidth: number
  field?: CustomField
}
const availableColumns = computed((): DocumentBrowserColumn[] => {
  const columns: DocumentBrowserColumn[] = [
    { key: 'documentNo', label: '单号', minWidth: 240, required: true, fixed: 'left' },
    { key: 'party', label: partyHeading.value, minWidth: 180 },
    { key: 'date', label: '业务日期', minWidth: 120 },
    { key: 'status', label: '状态', minWidth: 135 },
  ]
  if (order.value) columns.push({ key: 'progress', label: '履约 / 结算', minWidth: 160 })
  if (props.kind === 'shipments')
    columns.push({ key: 'signature', label: '签收日期', minWidth: 120 })
  if (props.kind === 'stock-counts')
    columns.push({ key: 'snapshot', label: '盘点快照', minWidth: 155 })
  if (showAmount.value) columns.push({ key: 'amount', label: '金额（元）', minWidth: 140 })
  for (const field of fieldColumns.value) {
    if (field.listVisible)
      columns.push({ key: 'field:' + field.fieldKey, label: field.label, minWidth: 140, field })
  }
  columns.push({ key: 'actions', label: '操作', minWidth: 90, required: true, fixed: 'right' })
  return columns
})
const arrangedColumns = computed(() =>
  arrangeDocumentColumns(availableColumns.value, columnOrder.value, columnFixed.value),
)
const mobileViewport = window.matchMedia?.('(max-width: 767px)')
const compactColumns = ref(mobileViewport?.matches ?? false)
function resizeColumns(event: MediaQueryListEvent): void {
  compactColumns.value = event.matches
}
mobileViewport?.addEventListener('change', resizeColumns)
const visibleColumns = computed(() => {
  const columns = arrangedColumns.value.filter((column) => column.required || !hidden.value.includes(column.key))
  if (!compactColumns.value) return columns
  // 手机上仅固定勾选、单号和操作，为横向浏览其他列留出空间。
  return columns.map((column) => {
    if (column.key === 'documentNo') return { ...column, minWidth: 112 }
    if (column.key === 'actions') return { ...column, minWidth: 60 }
    return { ...column, fixed: undefined }
  })
})
const hasAdvanced = computed(
  () =>
    warehouses.value.length > 0 ||
    order.value ||
    props.kind === 'shipments' ||
    advancedFields.value.length > 0,
)
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
    ...Object.fromEntries(
      Object.entries(customFilters)
        .filter(([, value]) => value)
        .map(([key, value]) => ['cf.' + key, value]),
    ),
    cursor: cursors.value.at(-1) || undefined,
    limit: pageSize.value,
    withTotal: true,
  }
}
function readQuery(): void {
  const requestedSize = Number(route.query.pageSize)
  pageSize.value = pageSizes.includes(requestedSize) ? requestedSize : defaultPageSize
  for (const key of Object.keys(customFilters)) delete customFilters[key]
  if (order.value)
    for (const [key, value] of Object.entries(route.query))
      if (key.startsWith('cf.') && typeof value === 'string') customFilters[key.slice(3)] = value
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
  clearSelection()
  const current = ++revision
  controller.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  activeQuery.value = undefined
  const parameters = query()
  try {
    const result = await browseDocuments(props.kind, parameters, controller.signal)
    if (current === revision) {
      rows.value = result.items
      next.value = result.nextCursor
      total.value = result.total
      counts.value = result.statusCounts
      activeQuery.value = parameters
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
  if (pageSize.value !== defaultPageSize) values.pageSize = String(pageSize.value)
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
  activeQuery.value = undefined
  void saveQuery()
}
function reset(): void {
  for (const key of Object.keys(customFilters)) delete customFilters[key]
  Object.assign(filter, {
    keyword: '',
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
function changePageSize(event: Event): void {
  if (!(event.currentTarget instanceof HTMLSelectElement)) return
  const next = Number(event.currentTarget.value)
  if (!pageSizes.includes(next) || next === pageSize.value) return
  pageSize.value = next
  search()
}
function useDateShortcut(event: Event): void {
  if (!(event.currentTarget instanceof HTMLSelectElement)) return
  const select = event.currentTarget
  if (Object.hasOwn(dateShortcuts, select.value)) {
    const [start, end] = businessDateRange(select.value as keyof typeof dateShortcuts, today())
    filter.dateFrom = start
    filter.dateTo = end
  }
  select.value = ''
}
function refresh(): void {
  readQuery()
  void load()
}
async function downloadFields(): Promise<void> {
  if (!activeQuery.value || exporting.value) return
  exporting.value = true
  try {
    const file = await exportOrderFields(props.kind, activeQuery.value)
    const url = URL.createObjectURL(file)
    const link = document.createElement('a')
    link.href = url
    link.download = '订单扩展字段.csv'
    link.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    exporting.value = false
  }
}
async function options(): Promise<void> {
  const kind = props.kind,
    company = session.companyId
  fieldColumns.value = []
  const result = await Promise.allSettled([
    order.value
      ? customFields(kind === 'sales-orders' ? 'sales_order' : 'purchase_order')
      : Promise.resolve([]),
    hasPartner.value && session.can('partner:view') ? allRecords('partners') : Promise.resolve([]),
    hasWarehouse.value && session.can('warehouse:view')
      ? allRecords('warehouses', true)
      : Promise.resolve([]),
  ])
  if (kind !== props.kind || company !== session.companyId) return
  fieldColumns.value = result[0].status === 'fulfilled' ? result[0].value : []
  partners.value = result[1].status === 'fulfilled' ? result[1].value : []
  warehouses.value = result[2].status === 'fulfilled' ? result[2].value : []
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
  mobileViewport?.removeEventListener('change', resizeColumns)
  revision++
  controller.abort()
})
</script>
<template>
  <section class="panel table-panel document-browser" :aria-busy="loading">
    <QueryForm
      :expanded="expanded"
      :expandable="hasAdvanced"
      :controls-id="kind + '-advanced-filters'"
      @update:expanded="setExpanded"
      @submit="search"
    >
      <div class="filter-field">
        <label :for="kind + '-search'">单号 / 备注</label>
        <el-input :id="kind + '-search'" v-model="filter.keyword" clearable />
      </div>
      <div v-if="hasPartner && partners.length" class="filter-field document-partner">
        <label :for="kind + '-partner'">{{ partnerLabel }}</label>
        <el-select :id="kind + '-partner'" v-model="filter.partnerId" filterable clearable>
          <el-option
            v-for="row in partners"
            :key="row.id"
            :value="row.id"
            :label="row.name ?? row.code"
          />
        </el-select>
      </div>
      <div class="document-date-range" role="group" aria-label="业务日期范围">
        <div class="filter-field small">
          <label :for="kind + '-from'">开始日期</label>
          <input :id="kind + '-from'" v-model="filter.dateFrom" type="date" />
        </div>
        <div class="filter-field small">
          <label :for="kind + '-to'">结束日期</label>
          <input :id="kind + '-to'" v-model="filter.dateTo" type="date" />
        </div>
        <div class="filter-field small document-date-shortcuts">
          <label class="sr-only" :for="kind + '-date-shortcut'">日期快捷项</label>
          <select :id="kind + '-date-shortcut'" @change="useDateShortcut">
            <option value="">日期快捷项</option>
            <option v-for="(label, key) in dateShortcuts" :key="key" :value="key">
              {{ label }}
            </option>
          </select>
        </div>
      </div>
      <template #actions>
        <el-button native-type="submit" type="primary" :icon="Search" :loading="loading"
          >查询</el-button
        >
        <el-button @click="reset">重置</el-button>
      </template>
      <template #advanced>
        <div v-if="hasWarehouse && warehouses.length" class="filter-field">
          <label :for="kind + '-warehouse'">仓库</label>
          <el-select :id="kind + '-warehouse'" v-model="filter.warehouseId" filterable clearable>
            <el-option
              v-for="row in warehouses"
              :key="row.id"
              :value="row.id"
              :label="row.name ?? row.code"
            />
          </el-select>
        </div>
        <template v-if="order">
          <div class="filter-field small">
            <label :for="kind + '-fulfillment'">履约</label>
            <el-select :id="kind + '-fulfillment'" v-model="filter.fulfillmentStatus" clearable>
              <el-option
                v-for="(label, value) in fulfillment"
                :key="value"
                :value="value"
                :label="label"
              />
            </el-select>
          </div>
          <div class="filter-field small">
            <label :for="kind + '-settlement'">结算</label>
            <el-select :id="kind + '-settlement'" v-model="filter.settlementStatus" clearable>
              <el-option
                v-for="(label, value) in settlements"
                :key="value"
                :value="value"
                :label="label"
              />
            </el-select>
          </div>
        </template>
        <el-checkbox v-if="kind === 'shipments'" v-model="filter.signOverdue"
          >仅签收超期</el-checkbox
        >
        <div v-for="field in advancedFields" :key="field.fieldKey" class="filter-field">
          <label :for="'browse-' + field.fieldKey">{{ field.label }}</label>
          <el-select
            v-if="field.type === 'select' || field.type === 'bool'"
            :id="'browse-' + field.fieldKey"
            v-model="customFilters[field.fieldKey]"
            clearable
          >
            <el-option
              v-for="item in field.type === 'bool' ? ['true', 'false'] : field.choices"
              :key="item"
              :value="item"
              :label="field.type === 'bool' ? (item === 'true' ? '是' : '否') : item"
            />
          </el-select>
          <el-input
            v-else
            :id="'browse-' + field.fieldKey"
            v-model="customFilters[field.fieldKey]"
            clearable
          />
        </div>
      </template>
    </QueryForm>
    <div class="document-list-head">
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
      <TableToolbar
        :columns="arrangedColumns"
        :hidden="hidden"
        :busy="loading"
        @refresh="refresh"
        @toggle-column="toggleColumn"
        @move-column="(key, direction) => moveColumn(arrangedColumns, key, direction)"
        @fix-column="fixColumn"
        @reset-columns="resetColumns"
      >
        <el-button
          v-if="exportable"
          :icon="Download"
          :disabled="!activeQuery"
          :loading="exporting"
          title="导出本页订单扩展字段"
          @click="downloadFields"
          >导出本页</el-button
        >
      </TableToolbar>
    </div>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <DocumentBatchBar
      v-if="batchKind"
      :key="`${session.companyId}:${batchKind}`"
      :kind="batchKind"
      :selected="selected"
      :can-approve="canApproveSelection"
      :can-print="session.can(spec.permission + ':print')"
      :can-open-jobs="session.can(spec.permission + ':print')"
      :disabled="loading"
      @clear="clearSelection"
      @changed="refresh"
    />
    <el-table
      ref="selectionTable"
      class="document-list-table"
      table-layout="auto"
      :header-cell-style="documentHeaderCellStyle"
      :data="rows"
      row-key="id"
      @selection-change="selected = $event"
    >
      <el-table-column
        v-if="batchKind"
        class-name="document-selection-column"
        width="44"
        min-width="44"
        fixed="left"
        align="center"
        :resizable="false"
      >
        <template #header>
          <label class="document-selection" @click.stop>
            <input
              type="checkbox"
              aria-label="选择本页全部单据"
              :checked="rows.length > 0 && selected.length === rows.length"
              :indeterminate="selected.length > 0 && selected.length < rows.length"
              :disabled="loading || rows.length === 0"
              @change="selectionTable?.toggleAllSelection()"
            />
          </label>
        </template>
        <template #default="{ row }">
          <label class="document-selection" @click.stop>
            <input
              type="checkbox"
              :aria-label="'选择单据 ' + (row.documentNo ?? row.invoiceNo ?? row.id)"
              :checked="selectedIds.has(row.id)"
              :disabled="loading"
              @change="selectionTable?.toggleRowSelection(row)"
            />
          </label>
        </template>
      </el-table-column>
      <el-table-column
        v-for="column in visibleColumns"
        :key="column.key"
        :column-key="column.key"
        :class-name="column.key === 'actions' ? 'document-actions-column' : undefined"
        :label="column.label"
        :min-width="column.minWidth"
        :width="column.key === 'actions' || (compactColumns && column.key === 'documentNo') ? column.minWidth : undefined"
        :fixed="column.fixed"
        :align="column.key === 'amount' ? 'right' : 'left'"
        :show-overflow-tooltip="column.key === 'party' || Boolean(column.field)"
      >
        <template #default="{ row }">
          <router-link
            v-if="column.key === 'documentNo'"
            class="text-link"
            :to="'/' + kind + '/' + row.id"
            >{{ row.documentNo ?? row.invoiceNo }}</router-link
          >
          <template v-else-if="column.key === 'party'">{{
            row.fromWarehouseName ? row.fromWarehouseName + ' → ' + row.toWarehouseName : name(row)
          }}</template>
          <template v-else-if="column.key === 'date'">{{
            row.businessDate ?? row.invoiceDate
          }}</template>
          <StatusTag
            v-else-if="column.key === 'status'"
            :label="documentStateLabel(kind, row.status)"
            :tone="documentStateTag(kind, row.status)"
          />
          <template v-else-if="column.key === 'progress'">
            {{
              fulfillment[row.fulfillmentStatus as keyof typeof fulfillment] ??
              row.fulfillmentStatus ??
              '—'
            }}
            /
            {{
              settlements[row.settlementStatus as keyof typeof settlements] ??
              row.settlementStatus ??
              '—'
            }}
          </template>
          <template v-else-if="column.key === 'signature'">{{ row.signDate }}</template>
          <template v-else-if="column.key === 'snapshot'">{{
            formatDateTime(String(row.snapshotAt ?? ''))
          }}</template>
          <template v-else-if="column.key === 'amount'">{{ amount(row) }}</template>
          <template v-else-if="column.field">{{
            formatField(row.extJson?.[column.field.fieldKey])
          }}</template>
          <router-link
            v-else-if="column.key === 'actions'"
            class="text-link"
            :to="'/' + kind + '/' + row.id"
            >查看</router-link
          >
        </template>
      </el-table-column>
      <template #empty
        ><div class="empty-state">
          <strong>{{
            loading ? '正在加载…' : error ? '加载失败' : '没有符合条件的单据'
          }}</strong><span v-if="!loading">{{
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
          >，第 {{ firstRowNumber }}—{{ firstRowNumber + rows.length - 1 }} 条</template
        ></span
      ><span v-else>第 {{ cursors.length }} 页</span>
      <div class="document-pagination-controls">
        <label>
          每页条数
          <select :value="pageSize" :disabled="loading" @change="changePageSize">
            <option v-for="size in pageSizes" :key="size" :value="size">{{ size }} 条</option>
          </select>
        </label>
        <el-button :disabled="loading || cursors.length === 1" @click="page(false)"
          >上一页</el-button
        ><el-button :disabled="loading || !next" @click="page(true)">下一页</el-button>
      </div>
    </footer>
  </section>
</template>

<style scoped>
/* 把限高交给滚动区，表头与数据行可留在同一张表内。 */
.document-list-table :deep(.el-scrollbar__wrap) {
  max-height: max(240px, calc(100dvh - 300px));
}

.document-list-table :deep(thead) {
  position: sticky;
  top: 0;
  z-index: calc(var(--el-table-index) + 2);
}

.document-list-table :deep(thead th) {
  background-color: var(--el-table-header-bg-color);
}

.document-list-table :deep(.document-selection-column.el-table__cell:first-child .cell) {
  padding-inline: 8px;
}

@media (max-width: 767px) {
  .document-list-table :deep(.el-table__cell.document-actions-column:last-child .cell) {
    padding-inline: 8px;
  }
}

.document-selection {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 28px;
  cursor: pointer;
}

.document-selection input {
  width: 16px;
  height: 16px;
  margin: 0;
  accent-color: var(--erp-primary-6);
}

.document-selection input:focus-visible {
  outline: 2px solid var(--erp-primary-6);
  outline-offset: 2px;
}
</style>
