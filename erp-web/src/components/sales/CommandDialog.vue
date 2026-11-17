<script setup lang="ts">
import WorkflowStarterSelector from '../workflow/WorkflowStarterSelector.vue'
import type { WorkflowSelections, WorkflowBusinessType } from '../../types/workflow'

import { computed, ref } from 'vue'
import { commandResult, document as getDocument, salesCommand } from '../../api/sales'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { actionLabels, salesSpecs, today } from '../../types/sales'
import type { SalesDocument, SalesKind } from '../../types/sales'
import { quantityDifference } from '../../types/document-ui'
import { formatQuantity } from '../../utils/format'
import ReceiptAttachmentPicker from './ReceiptAttachmentPicker.vue'
const props = defineProps<{ kind: SalesKind; document: SalesDocument; action: string }>()
const workflowType = computed<WorkflowBusinessType | undefined>(() =>
  props.action === 'submit'
    ? props.kind === 'sales-orders'
      ? 'sales_order'
      : props.kind === 'refund-requests'
        ? 'refund_request'
        : undefined
    : undefined,
)
const selections = ref<WorkflowSelections>({}),
  workflowReady = ref(false)
const emit = defineEmits<{ close: []; saved: [warnings?: { message: string }[]] }>()
const action = computed(() => props.action.replaceAll('-', '_'))
const label = computed(
  () => actionLabels[props.action] ?? actionLabels[action.value] ?? props.action,
)
const reason = ref(''),
  date = ref(today()),
  bank = ref(''),
  busy = ref(false),
  error = ref(''),
  uncertain = ref(false),
  conflict = ref(false)
const key = newIdempotencyKey()
const attachmentIds = ref<string[]>([]),
  attachmentBusy = ref(false),
  retryOriginal = ref(false)
let submittedPayload: Record<string, unknown> | undefined
const rows = ref(
  (props.document.lines ?? []).map((line) => ({
    id: line.id,
    name: line.sku?.name ?? line.skuName ?? String(line.lineNo ?? line.id),
    qty: action.value === 'sign' ? String(line.baseQty ?? line.qty ?? '0') : '0',
    shipped: String(line.baseQty ?? line.qty ?? '0'),
    remaining: line.progress?.shippableQty ?? '0',
    disposition: 'returned',
    pending: line.diffDisposition === 'pending',
    availableSerials: line.stockSelection?.serials ?? [],
    serials: [] as string[],
  })),
)
const operation = computed(() => {
  const resource = salesSpecs.find((s) => s.path === props.kind)!.permission
  const name = action.value === 'changes' ? 'change' : action.value
  return resource + '.' + name
})
function body(): Record<string, unknown> {
  const value: Record<string, unknown> = {
    version: props.document.version,
    ...(workflowType.value && Object.keys(selections.value).length
      ? { workflowAssignees: selections.value }
      : {}),
  }
  if (reason.value) {
    value.reason = reason.value
    value.comment = reason.value
  }
  if (action.value === 'sign') {
    value.signDate = date.value
    value.lines = rows.value.map((r) => ({
      lineId: r.id,
      signedQty: r.qty,
      serials: r.availableSerials.length && Number(r.qty) > 0 && quantityDifference(r.shipped, r.qty) !== '0' ? r.serials : undefined,
    }))
    value.attachmentIds = [...attachmentIds.value]
  }
  if (action.value === 'resolve_diff') {
    value.businessDate = date.value
    value.lines = rows.value
      .filter((r) => r.pending)
      .map((r) => ({ lineId: r.id, disposition: r.disposition, reason: reason.value }))
  }
  if (action.value === 'close_lines') {
    value.lines = rows.value
      .filter((r) => r.qty !== '0' && r.qty !== '')
      .map((r) => ({ lineId: r.id, closeQty: r.qty }))
  }
  if (action.value === 'register_paid') {
    value.paidDate = date.value
    value.bankSerialNo = bank.value
  }
  return value
}
async function submit(): Promise<void> {
  if (busy.value || attachmentBusy.value || (workflowType.value && !workflowReady.value)) return
  busy.value = true
  error.value = ''
  submittedPayload ??= body()
  try {
    const result = await salesCommand(
      props.kind,
      props.document.id,
      props.action,
      submittedPayload,
      key,
    )
    emit('saved', result?.warnings ?? [])
  } catch (cause: unknown) {
    if (!isCancelled(cause)) {
      error.value = messageOf(cause)
      if (cause instanceof ApiError) {
        uncertain.value = cause.status === 0 || cause.code === 'REQUEST_IN_PROGRESS'
        conflict.value = cause.code === 'VERSION_CONFLICT'
      }
      if (!uncertain.value && !retryOriginal.value) submittedPayload = undefined
    }
  } finally {
    busy.value = false
  }
}
async function verify(): Promise<void> {
  if (action.value === 'delete') {
    try {
      const current = await getDocument(props.kind, props.document.id)
      uncertain.value = false
      conflict.value = current.version !== props.document.version
      error.value = conflict.value ? '草稿已更新，请关闭并刷新' : '草稿仍存在，可使用原版本重新删除'
    } catch (cause: unknown) {
      if (cause instanceof ApiError && cause.status === 404) emit('saved')
      else error.value = messageOf(cause)
    }
    return
  }
  try {
    const result = await commandResult(operation.value, key)
    if (result.status === 'succeeded') emit('saved', result.response?.warnings ?? [])
  } catch (cause: unknown) {
    error.value = messageOf(cause)
    if (cause instanceof ApiError && cause.status === 404) {
      uncertain.value = false
      retryOriginal.value = true
    }
  }
}
</script>
<template>
  <el-dialog
    :model-value="true"
    :title="label"
    :width="action === 'sign' ? 'min(820px,96vw)' : '600px'"
    :close-on-click-modal="false"
    :show-close="!busy && !attachmentBusy"
    :close-on-press-escape="!busy && !attachmentBusy"
    @close="emit('close')"
  >
    <fieldset class="form-fieldset" :disabled="busy || uncertain || conflict || retryOriginal">
      <WorkflowStarterSelector
        v-if="workflowType"
        v-model="selections"
        :business-type="workflowType"
        :business-id="document.id"
        :disabled="busy || uncertain || retryOriginal"
        @ready="workflowReady = $event"
      />
      <p>单据 {{ document.documentNo ?? document.invoiceNo ?? document.id }}</p>
      <el-form label-position="top">
        <el-form-item
          v-if="['sign', 'resolve_diff', 'register_paid'].includes(action)"
          :label="
            action === 'sign' ? '签收日期' : action === 'register_paid' ? '退款日期' : '业务日期'
          "
          for="command-date"
          ><el-input id="command-date" v-model="date" type="date"
        /></el-form-item>
        <el-form-item v-if="action === 'register_paid'" label="银行流水号" for="command-bank"
          ><el-input id="command-bank" v-model="bank" maxlength="64"
        /></el-form-item>
        <template v-if="action === 'sign' || action === 'close_lines'">
          <p>以下数量按商品的基础单位填写。</p>
          <div class="sign-lines">
            <div v-for="row in rows" :key="row.id" class="relation-row">
              <label :for="'command-line-' + row.id"
                >{{ row.name }}{{ action === 'close_lines' ? ' · 可关闭 ' + row.remaining : ''
                }}<small v-if="action === 'sign'"
                  >已发 {{ formatQuantity(row.shipped) }}</small
                ></label
              ><el-input
                :id="'command-line-' + row.id"
                v-model="row.qty"
                :aria-label="action === 'sign' ? '签收数量' : '关闭数量'"
              /><span v-if="action === 'sign'" class="sign-difference"
                >差异 {{ quantityDifference(row.shipped, row.qty) }}</span
              ><el-button v-if="action === 'close_lines'" text @click="row.qty = row.remaining"
                >全部关闭</el-button
              >
              <el-select
                v-if="action === 'sign' && row.availableSerials.length && Number(row.qty) > 0 && Number(row.qty) < Number(row.shipped)"
                v-model="row.serials"
                class="sign-serials"
                aria-label="签收序列号"
                placeholder="请选择实际签收的序列号"
                multiple filterable
              >
                <el-option v-for="serial in row.availableSerials" :key="serial" :label="serial" :value="serial" />
              </el-select>
            </div>
          </div>
          <el-alert
            v-if="action === 'sign'"
            type="info"
            title="应收金额在确认签收后由服务器按累计数量计算。"
            :closable="false"
          />
          <ReceiptAttachmentPicker
            v-if="action === 'sign'"
            v-model="attachmentIds"
            :shipment-id="document.id"
            :disabled="busy || uncertain || conflict || retryOriginal"
            @busy="attachmentBusy = $event"
          />
        </template>
        <template v-if="action === 'resolve_diff'"
          ><div v-for="row in rows.filter((r) => r.pending)" :key="row.id" class="relation-row">
            <span>{{ row.name }}</span><el-select
              v-model="row.disposition"
              aria-label="差异处理方式"
              ><el-option label="拒收退回（冻结待检）" value="returned" /><el-option
                label="运输损失"
                value="lost"
            /></el-select></div
        ></template>
        <el-form-item
          v-if="
            [
              'reject',
              'cancel',
              'close_lines',
              'resolve_diff',
              'revoke_approval',
              'approve',
            ].includes(action)
          "
          label="意见 / 原因"
          for="command-reason"
          ><el-input id="command-reason" v-model="reason" type="textarea" maxlength="200"
        /></el-form-item>
      </el-form>
    </fieldset>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <p v-if="conflict">单据已有更新，请关闭并刷新后重试。</p>
    <p v-if="uncertain">结果确认中，请查询原请求结果。</p>
    <p v-if="retryOriginal">
      尚未查到已提交结果，将按原提交内容{{ action === 'sign' ? '和回单' : '' }}重试。
    </p>
    <template #footer
      ><el-button :disabled="busy || attachmentBusy" @click="emit('close')">关闭</el-button
      ><el-button v-if="uncertain" type="primary" @click="verify">查询结果</el-button
      ><el-button
        v-else
        type="primary"
        :disabled="conflict || attachmentBusy || Boolean(workflowType && !workflowReady)"
        :loading="busy"
        @click="submit"
        >确认{{ label }}</el-button
      ></template
    >
  </el-dialog>
</template>
<style scoped>
.sign-lines .relation-row {
  display: grid;
  grid-template-columns: minmax(150px, 1fr) 150px 100px;
  gap: 16px;
  padding: 12px 0;
  border-bottom: 1px solid var(--el-border-color-light);
}
.sign-serials { grid-column: 1 / -1; }
.sign-lines label small {
  display: block;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
.sign-difference {
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-secondary);
}
@media (max-width: 600px) {
  .sign-lines .relation-row {
    grid-template-columns: 1fr 100px;
  }
  .sign-difference {
    grid-column: 2;
  }
}
</style>
