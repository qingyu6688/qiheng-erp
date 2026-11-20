<script setup lang="ts">
import { computed, defineAsyncComponent, inject, onScopeDispose, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { crmCyclePage, crmCycleRead, crmCycleSave } from '../../api/crm-cycle'
import { isCancelled, messageOf } from '../../api/http'
import WorkflowProgressPanel from '../../components/workflow/WorkflowProgressPanel.vue'
import { useSessionStore } from '../../stores/session'
import type { DataRow } from '../../types/admin'
import type {
  CrmCycleKind,
  CrmCycleMode,
  CrmCycleRow,
  CrmCycleStatistics,
} from '../../types/crm-cycle'
import type { WorkflowBusinessType } from '../../types/workflow'
import CrmCycleEditor from './CrmCycleEditor.vue'

type StageAction = 'advance' | 'win' | 'lose' | 'invalidate'
const props = defineProps<{ kind: CrmCycleKind }>()
const readOnlyPreview = inject('erp.readOnlyPreview', false)
const router = useRouter()
const route = useRoute()
const session = useSessionStore()
const EChart = defineAsyncComponent(() => import('../../components/EChart.vue'))
const rows = ref<DataRow[]>([])
const result = ref<CrmCycleStatistics>()
const selected = ref<CrmCycleRow>()
const drawer = ref(false)
const editor = ref<{
  mode: CrmCycleMode
  source?: CrmCycleRow
  action?: StageAction
  receiptId?: string
  partnerId?: string
}>()
const loading = ref(false)
const opening = ref(false)
const error = ref('')
const nextCursor = ref<string | null>(null)
const pageCursors = ref<string[]>([])
const statisticsKind = ref('performance')
const tab = ref<'plans' | 'receipts'>('plans')
const status = ref('')
const day = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
const dateFrom = ref(`${day.slice(0, 7)}-01`)
const dateTo = ref(day)
const year = ref(Number(day.slice(0, 4)))
let revision = 0
let detailRevision = 0

const settings: Record<
  CrmCycleKind,
  { title: string; permission: string; create?: string; mode?: CrmCycleMode }
> = {
  businesses: {
    title: '商机',
    permission: 'crm_business:manage',
    create: '新建商机',
    mode: 'business',
  },
  contracts: {
    title: '合同',
    permission: 'crm_contract:manage',
    create: '新建合同',
    mode: 'contract',
  },
  receivables: {
    title: '合同回款',
    permission: 'crm_receivable:manage',
    create: '新建回款计划',
    mode: 'plan',
  },
  'performance-targets': {
    title: '业绩目标',
    permission: 'crm_target:manage',
    create: '维护业绩目标',
    mode: 'target',
  },
  statistics: { title: '客户统计', permission: '' },
}
const labels: Record<string, string> = {
  name: '名称',
  documentNo: '合同号',
  partnerName: '客户',
  partnerId: '客户编号',
  ownerUserId: '负责人',
  ownerDepartmentId: '部门',
  expectedAmount: '预计金额',
  expectedDate: '预计成交日',
  stageName: '阶段',
  stageId: '阶段编号',
  winRate: '设定赢率',
  signDate: '签约日',
  totalAmount: '合同金额',
  contractNo: '合同号',
  planDate: '计划回款日',
  periodNo: '期次',
  planAmount: '计划金额',
  receivedAmount: '已确认金额',
  pendingAmount: '待确认金额',
  receiptNo: '收款单号',
  amount: '金额',
  receiptStatus: '收款状态',
  receivedDate: '到账日期',
  targetType: '范围',
  targetId: '员工或部门',
  year: '年份',
  month: '月份',
  kind: '目标口径',
  monthKey: '月份',
  contractAmount: '合同额',
  receivableAmount: '回款额',
  customerCount: '客户数',
  newCustomerCount: '新增客户',
  soldCustomerCount: '已成交客户',
  businessCount: '商机数',
  wonCount: '赢单数',
  lostCount: '输单数',
  level: '级别',
  industry: '行业',
  source: '来源',
  areaId: '地区',
  skuName: '商品',
  skuId: '商品编号',
  unitName: '单位',
  unitId: '单位编号',
  qty: '数量',
  unitPrice: '单价',
  remainingQty: '剩余可生成量',
  method: '跟进方式',
  followUpCount: '跟进次数',
  advancedCount: '累计推进数',
  reachedCount: '到达阶段数',
  conversionRate: '阶段转化率',
  status: '状态',
  result: '结果',
}
const statuses: Record<string, string> = {
  open: '跟进中',
  won: '赢单',
  lost: '输单',
  invalid: '无效',
  draft: '草稿',
  pending: '待审批',
  approved: '已审批',
  rejected: '已驳回',
  cancelled: '已取消',
  finished: '已完成',
  partial: '部分回款',
  overdue: '逾期',
  pending_confirm: '待确认',
  confirmed: '已确认',
  reversed: '已冲销',
}
const kinds = [
  { value: 'performance', label: '业绩完成' },
  { value: 'customers', label: '客户总量' },
  { value: 'funnel', label: '销售漏斗' },
  { value: 'profiles', label: '客户画像' },
  { value: 'products', label: '产品销量' },
  { value: 'employees', label: '员工排行' },
  { value: 'follow-ups', label: '跟进统计' },
]
const statusOptions = computed(() =>
  props.kind === 'contracts'
    ? ['draft', 'pending', 'approved', 'rejected', 'cancelled']
    : ['open', 'won', 'lost', 'invalid'],
)
const detailEnabled = computed(() => props.kind === 'businesses' || props.kind === 'contracts')
function routeReference(value: unknown): string | undefined {
  return typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value) ? value : undefined
}
const receiptId = computed(() => routeReference(route.query.receiptId))
const partnerId = computed(() => routeReference(route.query.partnerId))
const pagePermission = computed(() => settings[props.kind].permission)
const canWrite = computed(() => pagePermission.value && session.can(pagePermission.value))
const columns = computed(() => {
  switch (props.kind) {
    case 'businesses':
      return ['name', 'partnerName', 'stageName', 'expectedAmount', 'expectedDate', 'result']
    case 'contracts':
      return ['documentNo', 'name', 'partnerName', 'signDate', 'totalAmount', 'status']
    case 'receivables':
      return tab.value === 'plans'
        ? [
            'contractNo',
            'periodNo',
            'planDate',
            'planAmount',
            'receivedAmount',
            'pendingAmount',
            'status',
          ]
        : ['receiptNo', 'contractNo', 'receivedDate', 'amount', 'receiptStatus']
    case 'performance-targets':
      return ['year', 'month', 'targetType', 'targetId', 'kind', 'amount']
    case 'statistics':
      return [...new Set(rows.value.flatMap((row) => Object.keys(row)))].filter(
        (key) => labels[key],
      )
    default:
      return []
  }
})
const chartOption = computed(() => {
  const source = rows.value.slice(0, 25)
  if (statisticsKind.value === 'funnel' && source.length)
    return {
      tooltip: { trigger: 'item' },
      series: [
        {
          type: 'funnel',
          sort: 'none',
          gap: 5,
          data: source.map((row) => ({
            name: String(row.stageName),
            value: Number(row.reachedCount ?? row.businessCount ?? 0),
          })),
        },
      ],
    }
  const chartFields = [
    'contractAmount',
    'receivableAmount',
    'customerCount',
    'businessCount',
    'amount',
    'followUpCount',
  ]
  const fields = chartFields.filter((key) => source.some((row) => row[key] !== undefined))
  if (!fields.length || source.length < 2) return undefined
  return {
    color: ['#2663db', '#008778', '#9a5b23'],
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0 },
    grid: { left: 16, right: 22, top: 18, bottom: 40, containLabel: true },
    xAxis: {
      type: 'category',
      data: source.map((row, index) =>
        String(
          row.stageName ?? row.monthKey ?? row.skuName ?? row.level ?? row.method ?? index + 1,
        ),
      ),
    },
    yAxis: { type: 'value' },
    series: fields.map((field) => ({
      name: labels[field],
      type: 'bar',
      data: source.map((row) => Number(row[field] ?? 0)),
    })),
  }
})

function cell(row: DataRow, key: string): string {
  const value = String(row[key] ?? '')
  if (key === 'status' && props.kind === 'receivables' && value === 'pending') return '待回款'
  if (['status', 'result', 'receiptStatus'].includes(key)) return statuses[value] ?? (value || '—')
  if (key === 'targetType') return value === 'user' ? '员工' : '部门'
  if (key === 'kind') return value === 'contract' ? '合同额' : '回款额'
  return value || '—'
}

async function reload(reset = false): Promise<void> {
  if (reset) pageCursors.value = []
  const current = ++revision
  loading.value = true
  error.value = ''
  try {
    switch (props.kind) {
      case 'statistics': {
        const response = await crmCycleRead<CrmCycleStatistics>(
          `statistics/${statisticsKind.value}`,
          { dateFrom: dateFrom.value, dateTo: dateTo.value },
        )
        if (current === revision) {
          result.value = response
          rows.value = response.items
        }
        break
      }
      case 'performance-targets': {
        const response = await crmCycleRead<CrmCycleRow[]>('performance-targets', {
          year: year.value,
        })
        if (current === revision) rows.value = response
        break
      }
      default: {
        const path =
          props.kind === 'receivables' && tab.value === 'plans' ? 'receivable-plans' : props.kind
        const params = { cursor: pageCursors.value.at(-1), limit: 50, partnerId: partnerId.value }
        const response = await crmCyclePage(path, {
          ...params,
          ...(props.kind === 'contracts' ? { status: status.value || undefined } : {}),
          ...(props.kind === 'businesses' ? { result: status.value || undefined } : {}),
        })
        if (current === revision) {
          rows.value = response.items
          nextCursor.value = response.nextCursor ?? null
        }
      }
    }
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
}

function changePage(next: boolean): void {
  if (next) {
    if (!nextCursor.value) return
    pageCursors.value.push(nextCursor.value)
  } else pageCursors.value.pop()
  void reload()
}

async function loadDetail(id: string): Promise<void> {
  const current = ++detailRevision
  drawer.value = true
  opening.value = true
  selected.value = undefined
  try {
    const data = await crmCycleRead<CrmCycleRow>(`${props.kind}/${encodeURIComponent(id)}`)
    if (current === detailRevision) selected.value = data
  } catch (cause: unknown) {
    if (current === detailRevision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === detailRevision) opening.value = false
  }
}

function openEditor(mode: CrmCycleMode, source?: CrmCycleRow, action?: StageAction): void {
  editor.value = {
    mode,
    source,
    action,
    partnerId: partnerId.value,
    receiptId: mode === 'attach' ? receiptId.value : undefined,
  }
}
function closeDrawer(): void {
  ++detailRevision
  selected.value = undefined
  if (route.params.id) void router.replace({ path: `/crm/${props.kind}`, query: route.query })
}
async function saveDone(row: CrmCycleRow): Promise<void> {
  const action = editor.value
  editor.value = undefined
  if (action?.mode === 'order') {
    await router.push(`/sales-orders/${encodeURIComponent(row.id)}`)
    return
  }
  if ((action?.mode === 'business' || action?.mode === 'contract') && !action.source) {
    await router.push({
      path: `/crm/${props.kind}/${encodeURIComponent(row.id)}`,
      query: route.query,
    })
    return
  }
  if (action?.mode === 'attach' && receiptId.value)
    await router.replace({ query: { ...route.query, receiptId: undefined } })
  await reload()
  if (route.params.id && selected.value) await loadDetail(selected.value.id)
}

async function cancelContract(): Promise<void> {
  const source = selected.value
  const company = session.companyId
  if (!source || opening.value || !window.confirm(`确定取消合同 ${source.documentNo}？`)) return
  opening.value = true
  try {
    const updated = await crmCycleSave(`contracts/${source.id}/cancel`, { version: source.version })
    if (company === session.companyId && selected.value?.id === updated.id) {
      selected.value = updated
      await reload()
    }
  } catch (cause: unknown) {
    if (company === session.companyId && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    opening.value = false
  }
}

watch(
  () => [props.kind, session.companyId, partnerId.value],
  () => {
    editor.value = undefined
    rows.value = []
    result.value = undefined
    selected.value = undefined
    status.value = ''
    tab.value = 'plans'
    nextCursor.value = null
    void reload(true)
  },
  { immediate: true },
)
watch(
  () => [route.params.id, session.companyId],
  () => {
    if (detailEnabled.value && typeof route.params.id === 'string') void loadDetail(route.params.id)
    else {
      ++detailRevision
      drawer.value = false
    }
  },
  { immediate: true },
)
onScopeDispose(() => {
  ++revision
  ++detailRevision
})
</script>

<template>
  <div class="page-heading">
    <h1>{{ settings[kind].title }}</h1>
    <div class="crm-actions">
      <el-button v-if="kind === 'businesses' && canWrite" @click="openEditor('stage')"
        >新建阶段组</el-button
      >
      <el-button
        v-if="canWrite && settings[kind].mode"
        type="primary"
        @click="openEditor(settings[kind].mode!)"
      >
        {{ settings[kind].create }}
      </el-button>
    </div>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-alert
    v-if="kind === 'receivables' && receiptId"
    title="选择要关联这张收款单的回款计划"
    type="info"
    :closable="false"
  />
  <section class="panel crm-results">
    <div class="crm-filters">
      <el-select
        v-if="detailEnabled"
        v-model="status"
        aria-label="状态"
        clearable
        placeholder="全部状态"
        @change="reload(true)"
      >
        <el-option
          v-for="item in statusOptions"
          :key="item"
          :value="item"
          :label="statuses[item]"
        />
      </el-select>
      <el-radio-group
        v-if="kind === 'receivables' && session.can('receipt:view')"
        v-model="tab"
        @change="reload(true)"
      >
        <el-radio-button value="plans">回款计划</el-radio-button
        ><el-radio-button value="receipts">关联收款单</el-radio-button>
      </el-radio-group>
      <el-input-number
        v-if="kind === 'performance-targets'"
        v-model="year"
        aria-label="年份"
        :min="1900"
        :max="9999"
      />
      <template v-if="kind === 'statistics'">
        <el-select v-model="statisticsKind" aria-label="统计类型" @change="reload(true)">
          <el-option v-for="item in kinds" :key="item.value" v-bind="item" />
        </el-select>
        <el-input v-model="dateFrom" aria-label="起始日期" type="date" />
        <el-input v-model="dateTo" aria-label="结束日期" type="date" />
      </template>
      <el-button :loading="loading" @click="reload(true)">刷新</el-button>
    </div>
    <EChart
      v-if="kind === 'statistics' && chartOption"
      :option="chartOption"
      :label="kinds.find((item) => item.value === statisticsKind)?.label ?? '客户统计'"
    />
    <el-table :data="rows" :empty-text="loading ? '正在加载…' : '暂无记录'" v-loading="loading">
      <el-table-column v-for="key in columns" :key="key" :label="labels[key]" min-width="115">
        <template #default="{ row }">{{ cell(row, key) }}</template>
      </el-table-column>
      <el-table-column v-if="detailEnabled || kind === 'receivables'" label="操作" width="120">
        <template #default="{ row }">
          <el-button v-if="detailEnabled && readOnlyPreview" link type="primary" @click="loadDetail(row.id)">查看详情</el-button>
          <router-link
            v-else-if="detailEnabled"
            class="text-link"
            :to="{ path: `/crm/${kind}/${row.id}`, query: route.query }"
            >查看详情</router-link
          >
          <el-button
            v-else-if="
              tab === 'plans' &&
              canWrite &&
              session.can('receipt:view') &&
              row.status !== 'finished'
            "
            link
            type="primary"
            @click="openEditor('attach', row)"
            >关联收款单</el-button
          >
          <router-link
            v-else-if="tab === 'receipts'"
            class="text-link"
            :to="`/receipts/${row.receiptId}`"
            >查看收款单</router-link
          >
        </template>
      </el-table-column>
    </el-table>
    <div v-if="nextCursor || pageCursors.length" class="crm-actions crm-paging">
      <el-button :disabled="loading || !pageCursors.length" @click="changePage(false)"
        >上一页</el-button
      >
      <el-button :disabled="loading || !nextCursor" @click="changePage(true)">下一页</el-button>
    </div>
  </section>
  <section v-if="result?.targets?.length" class="panel crm-results">
    <h2>同期目标</h2>
    <el-table :data="result.targets">
      <el-table-column
        v-for="key in ['year', 'month', 'targetType', 'targetId', 'kind', 'amount']"
        :key="key"
        :label="labels[key]"
        min-width="100"
      >
        <template #default="{ row }">{{ cell(row, key) }}</template>
      </el-table-column>
    </el-table>
  </section>

  <el-drawer
    v-if="detailEnabled"
    v-model="drawer"
    :title="selected?.documentNo || selected?.name || '详情'"
    size="min(760px, 100vw)"
    @closed="closeDrawer"
  >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="opening" role="status">正在读取…</p>
    <template v-if="selected">
      <div class="crm-actions">
        <template
          v-if="
            kind === 'businesses' && canWrite && selected.editable && selected.result === 'open'
          "
        >
          <el-button @click="openEditor('business', selected)">编辑商机</el-button>
          <el-button @click="openEditor('stage-action', selected, 'advance')">推进阶段</el-button>
          <el-button type="primary" @click="openEditor('stage-action', selected, 'win')"
            >标为赢单</el-button
          >
          <el-button @click="openEditor('stage-action', selected, 'lose')">标为输单</el-button>
          <el-button @click="openEditor('stage-action', selected, 'invalidate')"
            >设为无效</el-button
          >
        </template>
        <template v-if="kind === 'contracts'">
          <el-button
            v-if="selected.allowedActions?.includes('update')"
            @click="openEditor('contract', selected)"
            >编辑合同</el-button
          >
          <el-button
            v-if="selected.allowedActions?.includes('submit')"
            type="primary"
            @click="openEditor('submit', selected)"
            >提交审批</el-button
          >
          <el-button
            v-if="selected.allowedActions?.includes('cancel')"
            :loading="opening"
            @click="cancelContract"
            >取消合同</el-button
          >
          <el-button
            v-if="selected.allowedActions?.includes('create_sales_order')"
            type="primary"
            @click="openEditor('order', selected)"
            >生成销售订单</el-button
          >
        </template>
      </div>
      <div class="crm-summary">
        <div
          v-for="key in kind === 'businesses'
            ? ['name', 'partnerId', 'result', 'expectedDate', 'expectedAmount']
            : ['name', 'partnerId', 'status', 'signDate', 'totalAmount']"
          :key="key"
        >
          <span>{{ labels[key] }}</span><strong>{{ cell(selected, key) }}</strong>
        </div>
      </div>
      <h2>商品明细</h2>
      <el-table :data="selected.lines ?? []" empty-text="暂无商品明细">
        <el-table-column
          v-for="key in kind === 'contracts'
            ? ['skuName', 'unitName', 'qty', 'unitPrice', 'totalAmount', 'remainingQty']
            : ['skuName', 'unitName', 'qty', 'unitPrice', 'amount']"
          :key="key"
          :label="labels[key]"
          min-width="100"
        >
          <template #default="{ row }">{{ cell(row, key) }}</template>
        </el-table-column>
      </el-table>
      <template v-if="kind === 'contracts'">
        <div v-if="selected.salesOrderIds?.length" class="crm-order-links">
          <h2>已生成销售订单</h2>
          <router-link
            v-for="id in selected.salesOrderIds"
            :key="id"
            class="text-link"
            :to="`/sales-orders/${id}`"
            >订单 {{ id }}</router-link
          >
        </div>
        <WorkflowProgressPanel
          :business-type="'crm_contract' as WorkflowBusinessType"
          :business-id="selected.id"
          :instance-id="selected.wfInstanceId"
        />
      </template>
    </template>
  </el-drawer>
  <CrmCycleEditor v-if="editor" v-bind="editor" @close="editor = undefined" @saved="saveDone" />
</template>

<style scoped>
.crm-results {
  margin-bottom: 16px;
  padding: 16px;
}
.crm-filters,
.crm-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.crm-filters {
  margin-bottom: 12px;
}
.crm-filters > .el-select,
.crm-filters > .el-input {
  width: min(170px, 100%);
}
.crm-paging {
  justify-content: flex-end;
  margin-top: 12px;
}
.crm-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(115px, 1fr));
  gap: 12px;
  margin-block: 16px;
}
.crm-summary div {
  display: grid;
  gap: 4px;
}
.crm-summary span {
  color: var(--el-text-color-secondary);
}
.crm-order-links .text-link {
  margin-inline-end: 10px;
}
</style>
