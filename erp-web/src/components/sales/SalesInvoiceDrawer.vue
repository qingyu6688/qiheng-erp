<script setup lang="ts">
import { computed, inject, onBeforeUnmount, reactive, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  invoiceRegistration,
  manualInvoiceReds,
  createManualInvoiceRed,
  commandManualInvoiceRed,
} from '../../api/manual-invoice-reds'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import type {
  InvoiceRegistration,
  ManualInvoiceRed,
  ManualInvoiceRedInput,
} from '../../types/manual-invoice-reds'
import { manualRedStatusLabels } from '../../types/manual-invoice-reds'
import { today } from '../../types/sales'
import { useSessionStore } from '../../stores/session'
import { registerCompanyLeaveGuard } from '../../utils/unsaved'
import FinancialAmount from '../FinancialAmount.vue'
import StatusTag from '../StatusTag.vue'
import ElectronicInvoiceDrawer from '../einvoice/ElectronicInvoiceDrawer.vue'

const props = defineProps<{ id: string }>()
const emit = defineEmits<{ close: []; changed: [] }>()
const session = useSessionStore(),
  readOnlyPreview = inject('erp.readOnlyPreview', false)
const blue = ref<InvoiceRegistration>(),
  reds = ref<ManualInvoiceRed[]>([]),
  historyNext = ref<string | null>(null)
const loading = ref(false),
  busy = ref(false),
  error = ref(''),
  electronicId = ref<string>()
const source = shallowRef<InvoiceRegistration>()
const form = reactive({ showing: false, redInvoiceNo: '', redInvoiceDate: today(), reason: '' })
type Pending =
  | { kind: 'create'; input: ManualInvoiceRedInput; key: string }
  | { kind: 'command'; id: string; action: 'confirm' | 'cancel'; version: number; key: string }
const pending = shallowRef<Pending>()
const locked = computed(() => busy.value || Boolean(pending.value))
const baseline = ref('')
const dirty = computed(
  () =>
    form.showing &&
    baseline.value !== JSON.stringify([form.redInvoiceNo, form.redInvoiceDate, form.reason]),
)
const atRisk = computed(() => locked.value || dirty.value)
const current = computed(() => reds.value.find((red) => red.status !== 'cancelled'))
const canCreate = computed(
  () =>
    !readOnlyPreview &&
    blue.value?.invoiceType === 'sales' &&
    !blue.value.einvoiceRequestId &&
    !blue.value.manualRedId &&
    !current.value &&
    session.can('invoice_red:view') &&
    session.can('invoice_red:create'),
)
const sourceChanged = computed(
  () => source.value && blue.value && source.value.version !== blue.value.version,
)
const controller = new AbortController()
let revision = 0
function mayLeave(): boolean {
  if (locked.value) {
    error.value = '本次红字请求尚未完成或结果未确认，请先重试确认结果。'
    return false
  }
  return !dirty.value || window.confirm('红字登记尚未保存，确定离开？')
}
function close(done: () => void = () => emit('close')): void {
  if (mayLeave()) done()
}
const removeGuard = registerCompanyLeaveGuard(mayLeave, atRisk)
onBeforeRouteLeave(mayLeave)
function beforeUnload(event: BeforeUnloadEvent): void {
  if (atRisk.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
async function load(): Promise<void> {
  const token = ++revision
  loading.value = true
  error.value = ''
  try {
    const invoice = await invoiceRegistration(props.id, controller.signal)
    const history =
      !invoice.einvoiceRequestId && session.can('invoice_red:view')
        ? await manualInvoiceReds(props.id, controller.signal)
        : { items: [], nextCursor: null }
    if (token !== revision) return
    blue.value = invoice
    reds.value = history.items
    historyNext.value = history.nextCursor
  } catch (cause: unknown) {
    if (token === revision && !isCancelled(cause)) {
      error.value = messageOf(cause)
      blue.value = undefined
      reds.value = []
      historyNext.value = null
    }
  } finally {
    if (token === revision) loading.value = false
  }
}
async function moreHistory(): Promise<void> {
  if (!historyNext.value || locked.value) return
  const token = revision
  busy.value = true
  try {
    const page = await manualInvoiceReds(props.id, controller.signal, historyNext.value)
    if (token !== revision) return
    reds.value.push(...page.items)
    historyNext.value = page.nextCursor
  } catch (cause: unknown) {
    if (token === revision && !isCancelled(cause)) {
      error.value = messageOf(cause)
      if (cause instanceof ApiError && [403, 404].includes(cause.status)) {
        blue.value = undefined
        reds.value = []
        historyNext.value = null
      }
    }
  } finally {
    busy.value = false
  }
}
function start(): void {
  if (!canCreate.value || locked.value || !blue.value) return
  source.value = blue.value
  Object.assign(form, { showing: true, redInvoiceNo: '', redInvoiceDate: today(), reason: '' })
  baseline.value = JSON.stringify([form.redInvoiceNo, form.redInvoiceDate, form.reason])
}

function stopEditing(): void {
  if (mayLeave()) form.showing = false
}
async function save(): Promise<void> {
  const original = source.value
  if (!original || locked.value || readOnlyPreview) return
  if (sourceChanged.value) {
    error.value = '原票已更新，请返回详情核对后重新登记'
    return
  }
  if (!form.redInvoiceNo.trim() || form.redInvoiceNo.trim().length > 64) {
    error.value = '请填写红字票号，最多64字'
    return
  }
  if (
    !/^\d{4}-\d{2}-\d{2}$/.test(form.redInvoiceDate) ||
    form.redInvoiceDate < original.invoiceDate ||
    form.redInvoiceDate > today()
  ) {
    error.value = '红字日期不能早于原票或晚于今天'
    return
  }
  if (!form.reason.trim() || form.reason.trim().length > 500) {
    error.value = '请填写红字原因，最多500字'
    return
  }
  pending.value = {
    kind: 'create',
    key: newIdempotencyKey(),
    input: {
      invoiceId: original.id,
      sourceVersion: original.version,
      redInvoiceNo: form.redInvoiceNo.trim(),
      redInvoiceDate: form.redInvoiceDate,
      amount: original.amount,
      taxAmount: original.taxAmount,
      reason: form.reason.trim(),
    },
  }
  await write()
}
async function act(red: ManualInvoiceRed, action: 'confirm' | 'cancel'): Promise<void> {
  if (locked.value || readOnlyPreview || !red.allowedActions.includes(action)) return
  try {
    await ElMessageBox.confirm(
      action === 'confirm'
        ? '确认已核对实际红字票及原票全部来源？确认后释放整张蓝字发票的占用。'
        : '确认撤回这条待确认红字登记？原蓝字占用保持不变。',
      action === 'confirm' ? '财务确认红字' : '撤回红字登记',
      { type: 'warning' },
    )
  } catch (cause: unknown) {
    if (cause !== 'cancel' && cause !== 'close') error.value = messageOf(cause)
    return
  }
  pending.value = {
    kind: 'command',
    id: red.id,
    action,
    version: red.version,
    key: newIdempotencyKey(),
  }
  await write()
}
async function write(): Promise<void> {
  const command = pending.value
  if (!command || busy.value || readOnlyPreview) return
  busy.value = true
  error.value = ''
  try {
    if (command.kind === 'create')
      await createManualInvoiceRed(command.input, command.key, controller.signal)
    else
      await commandManualInvoiceRed(
        command.id,
        command.action,
        command.version,
        command.key,
        controller.signal,
      )
    pending.value = undefined
    form.showing = false
    source.value = undefined
    emit('changed')
    await load()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) {
      error.value = messageOf(cause)
      if (
        cause instanceof ApiError &&
        cause.status >= 400 &&
        cause.status < 500 &&
        cause.code !== 'REQUEST_IN_PROGRESS'
      )
        pending.value = undefined
      if (cause instanceof ApiError && [403, 404].includes(cause.status)) {
        blue.value = undefined
        reds.value = []
      }
    }
  } finally {
    busy.value = false
  }
}
function electronicChanged(): void {
  emit('changed')
  void load()
}
watch(
  () => [props.id, session.companyId],
  () => {
    blue.value = undefined
    reds.value = []
    void load()
  },
  { immediate: true },
)
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => {
  revision++
  controller.abort()
  removeGuard()
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <el-drawer
    class="sales-invoice-drawer"
    :model-value="true"
    :title="blue ? '原票及红字 · ' + blue.invoiceNo : '原票及红字详情'"
    direction="rtl"
    size="min(1000px, 96vw)"
    append-to-body
    :before-close="close"
    :show-close="!locked"
    :close-on-press-escape="!locked"
    :close-on-click-modal="false"
  >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert
      v-if="pending"
      title="红字请求结果尚未确认，请重试本次请求，不要重新登记。"
      type="warning"
      :closable="false"
    />
    <p v-if="loading" role="status">正在读取原票及红字记录…</p>
    <template v-if="blue">
      <StatusTag :label="blue.status === 'registered' ? '蓝字占用中' : '蓝字登记已作废'" />
      <dl class="red-summary">
        <div>
          <dt>原票号</dt>
          <dd>{{ blue.invoiceNo }}</dd>
        </div>
        <div>
          <dt>往来单位</dt>
          <dd>{{ blue.partnerName }}</dd>
        </div>
        <div>
          <dt>原票日期</dt>
          <dd>{{ blue.invoiceDate }}</dd>
        </div>
        <div class="red-total">
          <dt>整票含税金额（元）</dt>
          <dd><FinancialAmount :value="blue.amount" /></dd>
        </div>
        <div>
          <dt>整票税额（元）</dt>
          <dd><FinancialAmount :value="blue.taxAmount" /></dd>
        </div>
      </dl>
      <el-alert
        v-if="
          blue.invoiceType === 'sales' &&
          !blue.einvoiceRequestId &&
          blue.status === 'voided' &&
          !current
        "
        title="普通作废登记不代表实际红字已完成；已有真实红字票时，可以补录并由另一人确认。"
        type="info"
        :closable="false"
      />
      <h3>原票全部来源</h3>
      <p class="red-help">整票红字涉及以下全部应收来源，确认前请逐项核对。</p>
      <el-table :data="blue.lines" row-key="id" empty-text="暂无来源明细" max-height="360">
        <el-table-column label="原单 / 商品" min-width="260">
          <template #default="{ row }">
            <strong>{{ row.skuName ?? '应收来源' }}</strong>
            <div class="red-help">{{ row.sourceNo ?? row.receivableId ?? '历史来源' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="票面分配金额（元）" min-width="180" align="right"
          ><template #default="{ row }"><FinancialAmount :value="row.amount" /></template
        ></el-table-column>
      </el-table>
      <section v-if="blue.einvoiceRequestId" class="red-section">
        <h3>数电红字</h3>
        <p class="red-help">数电票通过原开票请求申请整票红字，服务商完成前不会释放开票占用。</p>
        <el-button
          v-if="session.can('einv:view')"
          type="primary"
          @click="electronicId = blue.einvoiceRequestId"
          >查看数电及红字进度</el-button
        >
      </section>
      <section v-else-if="session.can('invoice_red:view')" class="red-section">
        <h3>手工红字记录</h3>
        <el-empty v-if="!reds.length && !form.showing" description="尚未登记实际红字发票" />
        <article v-for="red in reds" :key="red.id" class="red-record">
          <div class="red-record-heading">
            <strong>{{ red.redInvoiceNo }}</strong><StatusTag
              :label="manualRedStatusLabels[red.status]"
              :tone="red.status === 'confirmed' ? 'success' : 'neutral'"
            />
          </div>
          <dl class="red-summary">
            <div>
              <dt>红字日期</dt>
              <dd>{{ red.redInvoiceDate }}</dd>
            </div>
            <div>
              <dt>含税金额（元）</dt>
              <dd><FinancialAmount :value="red.amount" /></dd>
            </div>
            <div>
              <dt>税额（元）</dt>
              <dd><FinancialAmount :value="red.taxAmount" /></dd>
            </div>
          </dl>
          <p>{{ red.reason }}</p>
          <p v-if="red.confirmedAt" class="red-help">确认时间：{{ red.confirmedAt }}</p>
          <el-alert
            v-if="red.status === 'pending'"
            title="待另一位财务人员确认，原票占用尚未释放。"
            type="info"
            :closable="false"
          />
          <div v-if="!readOnlyPreview" class="red-actions">
            <el-button
              v-if="red.allowedActions.includes('confirm')"
              type="primary"
              :disabled="locked"
              @click="act(red, 'confirm')"
              >财务确认红字</el-button
            >
            <el-button
              v-if="red.allowedActions.includes('cancel')"
              :disabled="locked"
              @click="act(red, 'cancel')"
              >撤回登记</el-button
            >
          </div>
        </article>
        <el-button v-if="historyNext" :disabled="locked" @click="moreHistory"
          >更多历史记录</el-button
        >
        <el-form
          v-if="form.showing && source"
          label-position="top"
          :disabled="locked"
          @submit.prevent="save"
        >
          <h3>登记实际整票红字</h3>
          <el-alert
            title="请登记已经取得的红字发票；含税金额及税额必须与整张原票一致，登记本身不会释放开票占用。"
            type="info"
            :closable="false"
          />
          <el-alert
            v-if="sourceChanged"
            title="原票已更新，请返回详情核对后重新登记。"
            type="warning"
            :closable="false"
          />
          <div class="red-fields">
            <el-form-item label="红字发票号" required
              ><el-input v-model="form.redInvoiceNo" maxlength="64" aria-label="手工红字发票号"
            /></el-form-item>
            <el-form-item label="红字日期" required
              ><el-date-picker
                v-model="form.redInvoiceDate"
                type="date"
                value-format="YYYY-MM-DD"
                aria-label="手工红字日期"
            /></el-form-item>
          </div>
          <el-form-item label="红字原因" required
            ><el-input
              v-model="form.reason"
              type="textarea"
              maxlength="500"
              show-word-limit
              aria-label="手工红字原因"
          /></el-form-item>
          <el-button type="primary" :disabled="locked || Boolean(sourceChanged)" @click="save"
            >登记并等待财务确认</el-button
          >
          <el-button :disabled="locked" @click="stopEditing">返回详情</el-button>
        </el-form>
      </section>
    </template>
    <template #footer>
      <el-button v-if="pending && !busy" type="primary" @click="write">重试本次请求</el-button>
      <el-button v-if="canCreate && !form.showing" :disabled="locked" type="primary" @click="start"
        >登记实际红字</el-button
      >
      <el-button :disabled="locked || loading" @click="load">刷新原票及红字</el-button>
      <el-button :disabled="locked" @click="close()">关闭</el-button>
    </template>
  </el-drawer>
  <ElectronicInvoiceDrawer
    v-if="electronicId"
    :key="electronicId"
    :id="electronicId"
    @close="electronicId = undefined"
    @changed="electronicChanged"
  />
</template>

<style scoped>
:global(.sales-invoice-drawer .el-drawer__header) {
  align-items: flex-start;
}
:global(.sales-invoice-drawer .el-drawer__title) {
  min-width: 0;
  overflow-wrap: anywhere;
}
.red-summary,
.red-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
  margin-block: 20px;
}
.red-summary dt {
  color: var(--erp-text-2);
  font-size: 12px;
  margin-bottom: 8px;
}
.red-summary dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.red-total {
  font-size: 22px;
  font-weight: 650;
}
.red-total dt {
  font-weight: 400;
}
.red-help {
  color: var(--erp-text-2);
  line-height: 1.7;
}
.red-section {
  margin-top: 24px;
}
.red-record {
  border-bottom: 1px solid var(--el-border-color-light);
  padding-block: 18px;
}
.red-record-heading,
.red-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.red-record-heading strong {
  min-width: 0;
  overflow-wrap: anywhere;
}
.red-actions {
  margin-top: 12px;
}
.red-fields .el-date-editor {
  width: 100%;
}
@media (max-width: 600px) {
  .red-summary,
  .red-fields {
    grid-template-columns: 1fr;
  }
}
</style>
