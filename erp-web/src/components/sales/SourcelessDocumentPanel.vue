<script setup lang="ts">
import { computed, defineAsyncComponent, inject, onScopeDispose, ref, watch } from 'vue'
import { document as readDocument } from '../../api/sales'
import { returnCostPreview } from '../../api/sourceless-returns'
import { workflowInstance, workflowProgress } from '../../api/workflow'
import { isCancelled, messageOf } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import { useSourcelessMutation } from '../../hooks/useSourcelessMutation'
import type { SourcelessKind } from '../../hooks/useSourcelessMutation'
import type { SalesDocument } from '../../types/sales'
import { actionLabels, today } from '../../types/sales'
import type { ReturnCostPreview } from '../../types/sourceless-returns'
import type {
  WorkflowInstance,
  WorkflowProgress,
  WorkflowSelections,
  WorkflowTask,
} from '../../types/workflow'
import { workflowStatus } from '../../types/workflow'
import { documentStateLabel, documentStateTag } from '../../types/document-ui'
import { formatQuantity } from '../../utils/format'
import FinancialAmount from '../FinancialAmount.vue'
import StatusTag from '../StatusTag.vue'
import SalesReturnSourceTag from './SalesReturnSourceTag.vue'
import SourcelessReturnForm from './SourcelessReturnForm.vue'
import SourcelessRefundForm from './SourcelessRefundForm.vue'
import WorkflowStarterSelector from '../workflow/WorkflowStarterSelector.vue'

const SourcePreview = defineAsyncComponent(() => import('./SourcelessSourcePreview.vue'))
const props = defineProps<{ kind: SourcelessKind; id?: string }>()
const emit = defineEmits<{ close: []; saved: [id: string] }>()
const session = useSessionStore(),
  readOnly = inject('erp.readOnlyPreview', false)
const currentId = ref(props.id),
  value = ref<SalesDocument>(),
  editing = ref(!props.id && !readOnly),
  dirty = ref(false)
const loading = ref(false),
  readError = ref(''),
  flowError = ref(''),
  previewBusy = ref(false),
  preview = ref<ReturnCostPreview>()
const instance = ref<WorkflowInstance>(),
  progress = ref<WorkflowProgress>(),
  sourceId = ref<string>()
const action = ref(''),
  comment = ref(''),
  bankSerialNo = ref(''),
  paidDate = ref(today())
const selections = ref<WorkflowSelections>({}),
  workflowReady = ref(false)
let revision = 0,
  controller = new AbortController(),
  previewRevision = 0
const isReturn = props.kind === 'sales-returns'
const base = isReturn ? 'sales_return' : 'refund_request'
const title = isReturn ? '无原单销售退货' : '退货信用额退款'
const canRead = computed(
  () => session.can(`${base}:view`) && (isReturn || session.can('sales_return:view')),
)
const canCost = computed(() => isReturn && session.can('cost:view'))
const mutation = useSourcelessMutation(
  props.kind,
  () => currentId.value,
  dirty,
  (id) => {
    currentId.value = id
    editing.value = false
    dirty.value = false
    action.value = ''
    preview.value = undefined
    emit('saved', id)
    void load()
  },
  () => emit('close'),
)
const availableActions = computed(() =>
  (readOnly ? [] : (value.value?.allowedActions ?? []))
    .map((item) => (item === 'register-paid' ? 'register_paid' : item))
    .filter((item) => !['update', 'approve', 'reject'].includes(item) && mutation.can(item)),
)
const tasks = computed(() => instance.value?.tasks ?? progress.value?.tasks ?? [])
const ownTasks = computed(() =>
  readOnly
    ? []
    : (instance.value?.tasks ?? []).filter(
        (task) => task.canHandle && task.status === 'pending' && mutation.can('approve'),
      ),
)
const error = computed(() => mutation.error.value || readError.value)
const total = computed(() => money(isReturn ? value.value?.creditAmount : value.value?.amount))
function text(input: unknown): string {
  return typeof input === 'string' ? input : ''
}
function money(input: unknown): string | undefined {
  return typeof input === 'string' ? input : undefined
}

async function load(): Promise<void> {
  const token = ++revision,
    id = currentId.value
  ++previewRevision
  controller.abort()
  controller = new AbortController()
  loading.value = false
  previewBusy.value = false
  value.value = undefined
  instance.value = undefined
  progress.value = undefined
  preview.value = undefined
  readError.value = ''
  flowError.value = ''
  if (!canRead.value) {
    readError.value = '当前没有查看该单据及来源的权限。'
    return
  }
  if (!id) return
  loading.value = true
  try {
    const data = await readDocument(props.kind, id, controller.signal)
    if (token !== revision) return
    if (isReturn ? data.returnMode !== 'sourceless' : data.refundMode !== 'sourceless_credit')
      throw new Error('此单据采用原单方式，请从对应列表查看。')
    value.value = data
    const flowId = data.wfInstanceId ?? data.workflowInstanceId
    if (flowId && flowId !== '0') {
      try {
        const summary = await workflowProgress(base, id)
        if (token !== revision) return
        progress.value = summary
        if (!readOnly && summary.canOpen) {
          const flow = await workflowInstance(flowId)
          if (token === revision) instance.value = flow
        }
      } catch (cause: unknown) {
        if (token === revision && !isCancelled(cause)) flowError.value = messageOf(cause)
      }
    }
  } catch (cause: unknown) {
    if (token === revision && !isCancelled(cause)) readError.value = messageOf(cause)
  } finally {
    if (token === revision) loading.value = false
  }
}
watch(
  () => props.id,
  (id) => {
    if (id && id !== currentId.value) {
      currentId.value = id
      editing.value = false
      void load()
    }
  },
)
watch(
  () => [
    session.companyId,
    session.user?.id,
    canRead.value,
    canCost.value,
    session.user?.permissions?.join('|'),
  ],
  () => {
    editing.value = !currentId.value && !readOnly
    dirty.value = false
    action.value = ''
    void load()
  },
  { immediate: true, flush: 'sync' },
)
onScopeDispose(() => {
  revision++
  previewRevision++
  controller.abort()
})

function close(): void {
  if (mutation.mayLeave()) emit('close')
}
function cancelEdit(): void {
  if (!mutation.mayLeave()) return
  dirty.value = false
  if (!currentId.value) emit('close')
  else editing.value = false
}
function edit(): void {
  if (
    !value.value?.allowedActions.includes('update') ||
    !mutation.can('update') ||
    mutation.locked.value
  )
    return
  preview.value = undefined
  editing.value = true
}
async function previewCost(): Promise<void> {
  const id = currentId.value,
    token = ++previewRevision
  if (!id || !canCost.value || editing.value || mutation.locked.value) return
  previewBusy.value = true
  readError.value = ''
  try {
    const result = await returnCostPreview(id, controller.signal)
    if (token === previewRevision && canCost.value && id === currentId.value) preview.value = result
  } catch (cause: unknown) {
    if (token === previewRevision && !isCancelled(cause)) readError.value = messageOf(cause)
  } finally {
    if (token === previewRevision) previewBusy.value = false
  }
}
function choose(name: string): void {
  if (mutation.locked.value || !availableActions.value.includes(name)) return
  action.value = name
  comment.value = ''
  bankSerialNo.value = ''
  paidDate.value = today()
  selections.value = {}
  workflowReady.value = false
}
async function execute(): Promise<void> {
  if (!value.value || !action.value || mutation.locked.value) return
  const version = value.value.version
  if (action.value === 'submit' && !workflowReady.value) return
  if (action.value === 'register_paid') {
    if (!bankSerialNo.value.trim() || !paidDate.value) {
      readError.value = '请填写实际付款日期和银行流水号。'
      return
    }
    await mutation.command('register_paid', {
      version,
      bankSerialNo: bankSerialNo.value.trim(),
      paidDate: paidDate.value,
    })
  } else
    await mutation.command(action.value, {
      version,
      reason: comment.value.trim() || undefined,
      ...(action.value === 'submit' ? { workflowAssignees: selections.value } : {}),
    })
}
async function review(task: WorkflowTask, choice: 'approve' | 'reject'): Promise<void> {
  if (choice === 'reject' && !comment.value.trim()) {
    readError.value = '驳回前请填写处理意见。'
    return
  }
  await mutation.review(task, choice, comment.value.trim())
}
async function end(choice: 'cancel' | 'terminate'): Promise<void> {
  if (!instance.value || !comment.value.trim()) {
    readError.value = '请填写取消或终止原因。'
    return
  }
  await mutation.end(instance.value, choice, comment.value.trim())
}
defineExpose({ mayLeave: mutation.mayLeave })
</script>

<template>
  <section class="sourceless-panel" :aria-busy="loading || mutation.busy.value">
    <div class="page-heading">
      <div>
        <h2>{{ value?.documentNo ?? title }}</h2>
        <SalesReturnSourceTag v-if="isReturn && value" :mode="value.returnMode" /><StatusTag
          v-else-if="value"
          label="退货信用额退款"
          tone="info"
        />
      </div>
      <el-button :disabled="mutation.locked.value" @click="close">关闭</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-if="mutation.locked.value" class="request-recovery" role="status">
      <strong>{{ mutation.busy.value ? '正在处理原请求' : '原请求结果尚未确认' }}</strong><span
        >已保留本次参数与原请求，请先确认结果。</span
      ><el-button :loading="mutation.busy.value" @click="mutation.verify">确认原请求结果</el-button
      ><el-button :disabled="mutation.busy.value" @click="mutation.retry">使用原请求重试</el-button>
    </div>
    <p v-if="loading" role="status">正在加载单据…</p>
    <template v-if="canRead">
      <SourcelessReturnForm
        v-if="editing && isReturn && !readOnly && mutation.can(currentId ? 'update' : 'create')"
        :key="value?.id ?? 'new'"
        :original="value"
        :locked="mutation.locked.value"
        @dirty="dirty = $event"
        @save="mutation.saveReturn"
        @cancel="cancelEdit"
      />
      <SourcelessRefundForm
        v-else-if="
          editing && !isReturn && !readOnly && mutation.can(currentId ? 'update' : 'create')
        "
        :key="value?.id ?? 'new'"
        :original="value"
        :locked="mutation.locked.value"
        @dirty="dirty = $event"
        @save="mutation.saveRefund"
        @cancel="cancelEdit"
        @source="sourceId = $event"
      />
      <template v-else-if="value">
        <div class="document-facts">
          <StatusTag
            :label="documentStateLabel(kind, value.status)"
            :tone="documentStateTag(kind, value.status)"
          /><span>{{ text(value.customerName) || value.partnerName }}</span
          ><span>{{ text(value.warehouseName) || text(value.cashAccountName) }}</span
          ><span>{{ value.businessDate }}</span>
        </div>
        <div class="financial-summary">
          <div>
            <span>{{ isReturn ? '客户信用额' : '申请退款金额' }}</span><FinancialAmount
              :value="total"
            />
          </div>
          <div v-if="isReturn">
            <span>不含税退货金额</span><FinancialAmount :value="value.untaxedAmount" />
          </div>
          <div v-if="isReturn">
            <span>退回税额</span><FinancialAmount :value="value.taxAmount" />
          </div>
          <div v-if="canCost">
            <span>冻结入库成本</span><FinancialAmount :value="value.costAmount" />
          </div>
        </div>
        <p>
          <strong>{{ isReturn ? '退货原因：' : '退款原因：' }}</strong>{{ value.reason }}
        </p>
        <p v-if="value.legacyReference"><strong>历史依据：</strong>{{ value.legacyReference }}</p>
        <div class="panel-actions">
          <el-button
            v-if="!readOnly && value.allowedActions.includes('update') && mutation.can('update')"
            :disabled="mutation.locked.value"
            @click="edit"
            >更正草稿</el-button
          ><el-button
            v-if="canCost && ['draft', 'rejected', 'pending', 'approved'].includes(value.status)"
            :loading="previewBusy"
            :disabled="mutation.locked.value"
            @click="previewCost"
            >预览当前成本</el-button
          ><el-button
            v-for="name in availableActions"
            :key="name"
            :type="['post', 'register_paid'].includes(name) ? 'primary' : undefined"
            :disabled="mutation.locked.value"
            @click="choose(name)"
            >{{
              name === 'submit' && value.status === 'approved'
                ? '重新提交审批'
                : (actionLabels[name] ?? name)
            }}</el-button
          ><el-button :disabled="mutation.locked.value || editing" @click="load"
            >刷新单据</el-button
          >
        </div>
        <el-table v-if="isReturn" :data="value.lines ?? []" row-key="id"
          ><el-table-column label="商品 / 单位" min-width="170"
            ><template #default="{ row }"
              >{{ row.skuName }}
              <div class="muted">{{ row.unitName }}</div></template
            ></el-table-column
          ><el-table-column label="退货数量" min-width="115" align="right"
            ><template #default="{ row }">{{ formatQuantity(row.qty) }}</template></el-table-column
          ><el-table-column label="含税单价" min-width="135" align="right"
            ><template #default="{ row }"
              ><FinancialAmount :value="row.unitPrice" /></template></el-table-column
          ><el-table-column label="信用金额" min-width="145" align="right"
            ><template #default="{ row }"
              ><FinancialAmount :value="money(row.creditAmount)" /></template></el-table-column
          ><el-table-column label="质检" min-width="105"
            ><template #default="{ row }"
              ><StatusTag
                :label="
                  row.qualityResult === 'passed'
                    ? '合格'
                    : row.qualityResult === 'failed'
                      ? '不合格'
                      : '待检'
                "
                :tone="
                  row.qualityResult === 'passed'
                    ? 'success'
                    : row.qualityResult === 'failed'
                      ? 'danger'
                      : 'warning'
                " /></template></el-table-column
          ><el-table-column v-if="canCost" label="冻结成本及依据" min-width="200"
            ><template #default="{ row }"
              ><FinancialAmount :value="row.costAmount" /><SalesReturnSourceTag
                mode="sourceless"
                :cost-source="row.costBasis?.source"
              />
              <div v-if="row.manualCostReason" class="muted">
                {{ row.manualCostReason }}
              </div></template
            ></el-table-column
          ><el-table-column label="批次 / 序列号" min-width="160"
            ><template #default="{ row }"
              >{{ row.stockSelection?.batchNo ?? '—' }}
              <div class="muted">{{ row.stockSelection?.serials?.join('、') }}</div></template
            ></el-table-column
          ></el-table
        >
        <template v-else
          ><h3>所选退货来源</h3>
          <p class="muted">此处显示当前未清余额；提交后本申请已预占的部分不再计入可用余额。</p>
          <el-table :data="value.creditSources ?? []" row-key="id"
            ><el-table-column label="退货单 / 商品" min-width="230"
              ><template #default="{ row }"
                ><el-button
                  text
                  :disabled="mutation.locked.value"
                  @click="sourceId = row.sourceId"
                  >{{ row.sourceNo }}</el-button
                >
                <div>{{ row.skuName }}</div></template
              ></el-table-column
            ><el-table-column
              label="退回仓库"
              prop="warehouseName"
              min-width="140" /><el-table-column label="原信用额" min-width="150" align="right"
              ><template #default="{ row }"
                ><FinancialAmount :value="row.amount" /></template></el-table-column
            ><el-table-column label="当前未清余额" min-width="160" align="right"
              ><template #default="{ row }"
                ><FinancialAmount :value="row.openAmount" /></template></el-table-column></el-table
        ></template>
        <section v-if="preview && canCost" class="cost-preview">
          <h3>当前成本预览 · <FinancialAmount :value="preview.totalCost" /></h3>
          <p class="muted">{{ preview.notice }}</p>
          <el-table :data="preview.items" row-key="lineId"
            ><el-table-column label="来源" min-width="190"
              ><template #default="{ row }"
                ><SalesReturnSourceTag
                  mode="sourceless"
                  :cost-source="row.source" /></template></el-table-column
            ><el-table-column
              label="基本数量"
              prop="baseQuantity"
              min-width="130" /><el-table-column label="基本单位成本" min-width="150" align="right"
              ><template #default="{ row }"
                ><FinancialAmount :value="row.unitCost" /></template></el-table-column
            ><el-table-column label="入库成本" min-width="150" align="right"
              ><template #default="{ row }"
                ><FinancialAmount :value="row.costAmount" /></template></el-table-column
          ></el-table>
        </section>
        <section v-if="tasks.length || flowError || instance" class="approval-section">
          <h3>审批记录</h3>
          <el-alert v-if="flowError" :title="flowError" type="warning" :closable="false" /><el-table
            :data="tasks"
            row-key="id"
            ><el-table-column label="环节" prop="nodeName" min-width="160" /><el-table-column
              label="处理人"
              prop="assigneeName"
              min-width="130" /><el-table-column label="状态" min-width="110"
              ><template #default="{ row }">{{
                workflowStatus[row.status] ?? row.status
              }}</template></el-table-column
            ><el-table-column label="处理意见" prop="comment" min-width="200"
          /></el-table>
          <template
            v-if="!readOnly && (ownTasks.length || instance?.canCancel || instance?.canTerminate)"
            ><el-input
              v-model="comment"
              aria-label="审批处理意见"
              type="textarea"
              maxlength="500"
              :disabled="mutation.locked.value"
              placeholder="驳回、取消或终止前请填写原因"
            />
            <div class="panel-actions">
              <template v-for="task in ownTasks" :key="task.id"
                ><el-button
                  type="primary"
                  :disabled="mutation.locked.value"
                  @click="review(task, 'approve')"
                  >同意 · {{ task.nodeName }}</el-button
                ><el-button :disabled="mutation.locked.value" @click="review(task, 'reject')"
                  >驳回</el-button
                ></template
              ><el-button
                v-if="instance?.canCancel"
                :disabled="mutation.locked.value"
                @click="end('cancel')"
                >取消当前审批</el-button
              ><el-button
                v-if="instance?.canTerminate"
                :disabled="mutation.locked.value"
                @click="end('terminate')"
                >终止当前审批</el-button
              >
            </div></template
          >
        </section>
        <section v-if="action && !readOnly" class="action-confirm">
          <h3>{{ actionLabels[action] ?? action }}</h3>
          <p>
            {{
              action === 'post'
                ? '将按已审核依据入库并形成客户信用额，过账前会重新核对成本。'
                : action === 'register_paid'
                  ? '确认银行已完成退款后，再登记实际付款信息。'
                  : action === 'submit'
                    ? '提交后由另一人审批，批准不等于过账或付款。'
                    : '请核对当前单据后确认操作。'
            }}
          </p>
          <WorkflowStarterSelector
            v-if="action === 'submit'"
            v-model="selections"
            :business-type="base"
            :business-id="value.id"
            :disabled="mutation.locked.value"
            @ready="workflowReady = $event"
          /><template v-if="action === 'register_paid'"
            ><el-input
              v-model="paidDate"
              type="date"
              aria-label="实际退款日期"
              :disabled="mutation.locked.value" /><el-input
              v-model="bankSerialNo"
              aria-label="银行流水号"
              maxlength="100"
              :disabled="mutation.locked.value"
          /></template>
          <div class="panel-actions">
            <el-button
              type="primary"
              :disabled="mutation.locked.value || (action === 'submit' && !workflowReady)"
              @click="execute"
              >确认{{ actionLabels[action] ?? action }}</el-button
            ><el-button :disabled="mutation.locked.value" @click="action = ''">暂不操作</el-button>
          </div>
        </section>
      </template>
    </template>
    <SourcePreview v-if="sourceId" :id="sourceId" @close="sourceId = undefined" />
  </section>
</template>

<style scoped>
.document-facts,
.panel-actions,
.request-recovery {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-block: 16px;
}
.request-recovery {
  padding: 14px;
  border: 1px solid var(--erp-primary-3);
  background: var(--erp-primary-1);
}
.financial-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 24px 40px;
  padding-block: 20px;
  border-block: 1px solid var(--erp-border-1);
  margin-block: 18px;
}
.financial-summary > div {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.financial-summary > div > span:first-child {
  color: var(--erp-text-2);
  font-size: 13px;
}
.financial-summary :deep(.financial-amount) {
  font-size: 24px;
}
.cost-preview,
.approval-section,
.action-confirm {
  margin-block-start: 24px;
}
.action-confirm {
  padding: 16px;
  border: 1px solid var(--erp-border-1);
}
.action-confirm > :deep(.el-input) {
  margin-block-end: 12px;
}
</style>
