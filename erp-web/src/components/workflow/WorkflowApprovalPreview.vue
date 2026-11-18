<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Document } from '@element-plus/icons-vue'
import { isCancelled, messageOf } from '../../api/http'
import { workflowSourcePreview } from '../../api/workflow'
import type { GenericWorkflowRequest, WorkflowInstance, WorkflowTask } from '../../types/workflow'
import { actionLabels, businessLabels, businessRoutes, workflowStatus } from '../../types/workflow'
import { formatDateTime, formatMoney, formatQuantity } from '../../utils/format'
import DocumentFilesPanel from '../DocumentFilesPanel.vue'

const props = defineProps<{
  detail?: WorkflowInstance
  application?: GenericWorkflowRequest
  activeTaskId?: string
  userId?: string
  loading: boolean
  busy: boolean
}>()
const emit = defineEmits<{ choose: [action: string, task?: WorkflowTask] }>()

const source = ref<Record<string, unknown>>()
const sourceLoading = ref(false)
const sourceError = ref('')
watch(
  () => props.detail,
  async (detail, _, onCleanup) => {
    const abort = new AbortController()
    onCleanup(() => abort.abort())
    source.value = undefined
    sourceError.value = ''
    sourceLoading.value = false
    if (!detail || detail.businessType === 'wf_generic_request') return
    sourceLoading.value = true
    try {
      const document = await workflowSourcePreview(
        detail.businessType,
        detail.businessId,
        abort.signal,
      )
      if (!abort.signal.aborted) source.value = document
    } catch (cause: unknown) {
      if (!abort.signal.aborted && !isCancelled(cause)) sourceError.value = messageOf(cause)
    } finally {
      if (!abort.signal.aborted) sourceLoading.value = false
    }
  },
  { immediate: true },
)

function value(input: unknown): string | undefined {
  if (typeof input === 'string' && input.trim()) return input
  if (typeof input === 'number') return String(input)
  return undefined
}
function name(input: unknown): string | undefined {
  return input && typeof input === 'object' && 'name' in input ? value(input.name) : undefined
}
const summary = computed(() => {
  const row = source.value
  const data = props.detail?.summary
  const amount = value(data?.totalAmount)
  const creditExposure = value(data?.creditExposure)
  const creditLimit = value(data?.creditLimit)
  const fields: [string, string | null | undefined][] = [
    ['发起人', props.detail?.starterName],
    ['发起时间', props.detail?.startedAt && formatDateTime(props.detail.startedAt)],
    ['客户', name(row?.customer) ?? value(row?.customerName)],
    ['供应商', name(row?.supplier) ?? value(row?.supplierName)],
    ['往来单位', name(row?.partner) ?? value(row?.partnerName)],
    ['业务日期', value(row?.businessDate) ?? value(row?.voucherDate)],
    ['交货日期', value(row?.deliveryDate)],
    ['销售员', value(row?.salesUserName)],
    ['采购员', value(row?.buyerUserName)],
    ['仓库', name(row?.warehouse) ?? value(row?.warehouseName)],
    ['金额', amount && formatMoney(amount)],
    [
      '信用占用',
      creditExposure &&
        [formatMoney(creditExposure), creditLimit && formatMoney(creditLimit)]
          .filter(Boolean)
          .join(' / '),
    ],
    [
      '最低售价',
      data?.belowMinPrice === true
        ? '低于最低售价'
        : data?.belowMinPrice === false
          ? '均未低于最低售价'
          : undefined,
    ],
    ['来源单号', value(row?.sourceNo)],
    ['申请原因', value(row?.reason)],
    ['备注', value(row?.remark)],
  ]
  return fields.flatMap(([label, content]) => (content ? [{ label, content }] : []))
})

const lines = computed(() => {
  const entries: unknown = source.value?.lines
  if (!Array.isArray(entries)) return []
  return entries.flatMap((entry: unknown) => {
    if (entry === null || typeof entry !== 'object') return []
    const row = entry as Record<string, unknown>
    const item =
      name(row.sku) ??
      value(row.skuName) ??
      value(row.itemName) ??
      name(row.account) ??
      value(row.accountName) ??
      value(row.summary)
    const qty = value(row.qty ?? row.countedQty ?? row.receivedQty ?? row.baseQty)
    const price = value(row.unitPrice)
    const amount = value(
      row.receivableAmount ?? row.payableAmount ?? row.totalAmount ?? row.lineAmount,
    )
    const debit = value(row.debitAmount ?? row.debit)
    const credit = value(row.creditAmount ?? row.credit)
    return [
      { item, qty, price, amount, debit, credit, unit: name(row.unit) ?? value(row.unitName) },
    ]
  })
})
const numericColumns = computed(
  () =>
    [
      { key: 'qty', label: '数量' },
      { key: 'price', label: '单价' },
      { key: 'amount', label: '金额' },
      { key: 'debit', label: '借方金额' },
      { key: 'credit', label: '贷方金额' },
    ] as const,
)
const visibleColumns = computed(() =>
  numericColumns.value.filter(({ key }) => lines.value.some((line) => line[key] !== undefined)),
)

const ownTasks = computed(
  () =>
    props.detail?.tasks.filter(
      (task) =>
        task.assigneeId === props.userId && ['pending', 'waiting_sign'].includes(task.status),
    ) ?? [],
)
const visibleTasks = computed(() =>
  props.activeTaskId
    ? ownTasks.value.filter((task) => task.id === props.activeTaskId)
    : ownTasks.value,
)
const handleable = computed(() => visibleTasks.value.filter((task) => task.canHandle !== false))
function moreActions(task: WorkflowTask): { value: string; label: string }[] {
  if (task.ownerId) return []
  const actions = []
  if (task.status === 'pending') {
    actions.push({ value: 'delegate', label: '委派' })
    if (!task.parentTaskId) actions.push({ value: 'add-sign', label: '加签' })
  }
  if (
    !task.parentTaskId &&
    props.detail?.tasks.some(
      (entry) =>
        entry.parentTaskId === task.id &&
        entry.addedBy === props.userId &&
        entry.status === 'pending',
    )
  )
    actions.push({ value: 'remove-sign', label: '减签' })
  return actions
}
</script>

<template>
  <section class="panel approval-preview" aria-label="待办详情" :aria-busy="loading">
    <p v-if="loading" class="preview-empty" role="status">正在读取审批进度…</p>
    <div v-else-if="!detail" class="preview-empty">
      <Document aria-hidden="true" />
      <p>选择待办以查看单据和审批进度</p>
    </div>
    <template v-else>
      <header class="preview-header">
        <div>
          <h2>{{ detail.title }}</h2>
          <p>{{ businessLabels[detail.businessType] }} · {{ detail.businessNo }}</p>
        </div>
        <el-tag size="small" :type="detail.status === 'approved' ? 'success' : 'info'">
          {{ workflowStatus[detail.status] ?? detail.status }}
        </el-tag>
        <router-link
          v-if="businessRoutes[detail.businessType]"
          class="text-link source-link"
          :to="'/' + businessRoutes[detail.businessType] + '/' + detail.businessId"
          >查看来源单据</router-link
        >
      </header>
      <div class="preview-body">
        <el-alert
          v-if="detail.automaticProcessingFailed"
          type="warning"
          :closable="false"
          title="自动处理失败。系统会退避重试，也可按权限处理当前任务或终止流程。"
        />
        <el-alert
          v-if="detail.summary.belowMinPrice"
          type="warning"
          :closable="false"
          title="本单低于最低售价；需要审批时，必须由具备价格审批权限的人员完成人工复核。"
        />
        <el-alert
          v-if="detail.summary.overCreditLimit"
          type="warning"
          :closable="false"
          title="本单触发信用超额或超期规则。"
        />
        <p
          v-if="visibleTasks.some((task) => task.canHandle === false)"
          class="preview-note"
          role="status"
        >
          当前权限或经办复核规则限制了任务处理。
        </p>
        <section>
          <h3>单据摘要</h3>
          <dl class="preview-fields">
            <div v-for="entry in summary" :key="entry.label">
              <dt>{{ entry.label }}</dt>
              <dd>{{ entry.content }}</dd>
            </div>
          </dl>
          <p v-if="sourceLoading" class="muted" role="status">正在读取来源单据…</p>
          <p v-else-if="sourceError" class="muted" role="status">{{ sourceError }}</p>
        </section>
        <section v-if="application" class="workflow-application-detail">
          <h3>申请表单</h3>
          <dl v-if="application.fields.length" class="preview-fields">
            <div v-for="item in application.fields" :key="item.field.key">
              <dt>{{ item.field.label }}</dt>
              <dd>
                {{
                  typeof item.value === 'boolean' ? (item.value ? '是' : '否') : (item.value ?? '—')
                }}
              </dd>
            </div>
          </dl>
          <DocumentFilesPanel
            owner-type="wf_generic_request"
            :owner-id="application.id"
            :readonly="!application.canUpload"
          />
        </section>
        <section v-if="lines.length">
          <h3>单据明细</h3>
          <div class="preview-table-wrap">
            <table class="preview-lines" aria-label="单据明细">
              <thead>
                <tr>
                  <th scope="col">项目</th>
                  <th
                    v-for="column in visibleColumns"
                    :key="column.key"
                    scope="col"
                    class="amount-cell"
                  >
                    {{ column.label }}
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(line, index) in lines" :key="index">
                  <td>{{ line.item ?? `第 ${index + 1} 行` }}</td>
                  <td v-for="column in visibleColumns" :key="column.key" class="amount-cell">
                    {{
                      column.key === 'qty'
                        ? formatQuantity(line.qty, line.unit)
                        : formatMoney(line[column.key])
                    }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
        <section>
          <h3>审批轨迹</h3>
          <ol class="preview-timeline">
            <li v-for="item in detail.actions" :key="item.id">
              <div>
                <strong
                  >{{ item.actorName }} · {{ actionLabels[item.action] ?? item.action }}</strong
                ><time :datetime="item.createdAt">{{ formatDateTime(item.createdAt) }}</time>
              </div>
              <p v-if="item.comment">{{ item.comment }}</p>
            </li>
            <li
              v-for="task in detail.tasks.filter((task) =>
                ['pending', 'waiting_sign'].includes(task.status),
              )"
              :key="'task-' + task.id"
              class="pending-step"
            >
              <div>
                <strong
                  >{{ task.nodeName }} · {{ task.assigneeName ?? task.assigneeId
                  }}{{ task.assigneeId === userId ? '（你）' : '' }}</strong
                >
                <span>{{ workflowStatus[task.status] }}</span>
              </div>
              <p v-if="task.dueAt">截止 {{ formatDateTime(task.dueAt) }}</p>
            </li>
          </ol>
          <p v-if="!detail.actions.length && !detail.tasks.length" class="muted">暂无审批记录</p>
        </section>
      </div>
      <footer
        v-if="handleable.length || detail.canCancel || detail.canTerminate"
        class="preview-actions"
        aria-label="审批操作"
      >
        <div v-for="task in handleable" :key="task.id" class="flow-task-action preview-task-action">
          <div class="preview-task-name">
            <strong>{{ task.nodeName }}</strong>
            <p v-if="task.ownerId || task.priceApproval" class="muted">
              {{ task.ownerId ? '反馈后回到原处理人' : '人工价格审批' }}
            </p>
          </div>
          <div class="row-actions">
            <el-button
              v-if="task.status === 'pending' && !task.ownerId"
              :disabled="busy"
              @click="emit('choose', 'transfer', task)"
              >转办</el-button
            >
            <el-dropdown
              v-if="moreActions(task).length"
              trigger="click"
              placement="top-end"
              :disabled="busy"
              @command="emit('choose', $event, task)"
            >
              <el-button :disabled="busy">更多</el-button>
              <template #dropdown
                ><el-dropdown-menu
                  ><el-dropdown-item
                    v-for="item in moreActions(task)"
                    :key="item.value"
                    :command="item.value"
                    >{{ item.label }}</el-dropdown-item
                  ></el-dropdown-menu
                ></template
              >
            </el-dropdown>
            <el-button
              v-if="task.status === 'pending' && task.nodeKind === 'approver' && !task.ownerId"
              type="danger"
              plain
              :disabled="busy"
              @click="emit('choose', 'reject', task)"
              >驳回</el-button
            >
            <el-button
              v-if="task.status === 'pending'"
              type="primary"
              :disabled="busy"
              @click="emit('choose', 'approve', task)"
            >
              {{
                task.ownerId ? '反馈委派意见' : task.nodeKind === 'handler' ? '完成办理' : '同意'
              }}
            </el-button>
          </div>
        </div>
        <div
          v-if="detail.canCancel || detail.canTerminate"
          class="row-actions preview-instance-actions"
        >
          <el-button v-if="detail.canCancel" :disabled="busy" @click="emit('choose', 'cancel')"
            >撤销申请</el-button
          >
          <el-button
            v-if="detail.canTerminate"
            type="danger"
            plain
            :disabled="busy"
            @click="emit('choose', 'terminate')"
            >终止流程</el-button
          >
        </div>
      </footer>
    </template>
  </section>
</template>

<style scoped>
.approval-preview {
  min-width: 0;
}
.preview-empty {
  display: grid;
  place-content: center;
  justify-items: center;
  min-height: 240px;
  padding: 24px;
  color: var(--erp-text-3);
}
.preview-empty svg {
  width: 42px;
  height: 42px;
}
.preview-header {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid var(--erp-border-1);
}
.preview-header h2 {
  margin: 0;
  font-size: 18px;
  overflow-wrap: anywhere;
}
.preview-header p {
  margin: 4px 0 0;
  color: var(--erp-text-3);
  overflow-wrap: anywhere;
}
.source-link {
  margin-inline-start: auto;
}
.preview-body {
  display: grid;
  gap: 20px;
  padding: 16px 20px;
}
.preview-body h3 {
  margin: 0 0 10px;
  font-size: 14px;
}
.preview-fields {
  display: grid;
  margin: 0;
  grid-template-columns: repeat(auto-fit, minmax(min(200px, 100%), 1fr));
  gap: 8px 16px;
}
.preview-fields > div {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.preview-fields dt,
.preview-timeline time {
  flex: none;
  color: var(--erp-text-3);
}
.preview-fields dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.preview-table-wrap {
  overflow-x: auto;
  border: 1px solid var(--erp-border-1);
  border-radius: var(--erp-radius-sm);
}
.preview-lines {
  width: 100%;
  border-collapse: collapse;
}
.preview-lines th,
.preview-lines td {
  padding: 8px 12px;
  text-align: start;
  border-bottom: 1px solid var(--erp-border-1);
}
.preview-lines th {
  font-weight: 600;
  background: var(--erp-fill-1);
}
.preview-lines tr:last-child td {
  border-bottom: 0;
}
.preview-lines .amount-cell {
  text-align: end;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.preview-timeline {
  display: grid;
  gap: 12px;
  margin: 0;
  padding-inline-start: 24px;
}
.preview-timeline li {
  padding-inline-start: 2px;
}
.preview-timeline li::marker {
  color: var(--erp-text-3);
}
.preview-timeline li.pending-step::marker {
  color: var(--erp-primary-6);
}
.preview-timeline li > div {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  justify-content: space-between;
}
.preview-timeline p,
.preview-note {
  margin: 4px 0 0;
  color: var(--erp-text-2);
  overflow-wrap: anywhere;
}
.preview-actions {
  position: sticky;
  z-index: 2;
  bottom: 0;
  padding: 4px 12px;
  background: var(--erp-bg);
  border-top: 1px solid var(--erp-border-1);
  box-shadow: var(--erp-shadow-1);
}
.preview-actions .preview-task-action {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px;
  border: 0;
  margin: 0;
}
.preview-task-name {
  overflow-wrap: anywhere;
}
.preview-instance-actions {
  justify-content: flex-end;
  padding: 8px;
}
.preview-actions :deep(.row-actions > .el-button) {
  margin-left: 0;
}
</style>
