<script setup lang="ts">
import { computed, inject, onBeforeUnmount, reactive, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { invoiceDetail, invoiceAction, downloadInvoice } from '../../api/e-invoice'
import type { InvoiceDetail, InvoiceFile } from '../../api/e-invoice'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import { registerCompanyLeaveGuard } from '../../utils/unsaved'
import FinancialAmount from '../FinancialAmount.vue'
import StatusTag from '../StatusTag.vue'

const props = defineProps<{ id: string }>()
const emit = defineEmits<{ close: []; changed: [] }>()
const session = useSessionStore(),
  readOnlyPreview = inject('erp.readOnlyPreview', false)
const detail = ref<InvoiceDetail>(),
  error = ref(''),
  loading = ref(false),
  busy = ref(false)
const form = reactive({ showing: false, reason: '' })
const pending = shallowRef<{ version: number; reason: string; key: string }>()
const locked = computed(() => busy.value || Boolean(pending.value))
const atRisk = computed(() => locked.value || (form.showing && Boolean(form.reason.trim())))
const labels: Record<InvoiceDetail['status'], string> = {
  draft: '草稿',
  submitted: '开具处理中',
  issued: '已开具',
  red_submitted: '红字处理中',
  red_issued: '红字已完成',
  failed: '开具失败',
}
const controller = new AbortController()
let revision = 0,
  timer: ReturnType<typeof setTimeout> | undefined
function mayLeave(): boolean {
  if (locked.value) {
    error.value = '请求结果尚未确认，请先重试本次请求或刷新确认进度。'
    return false
  }
  return !atRisk.value || window.confirm('红字原因尚未提交，确定离开？')
}
function close(done: () => void = () => emit('close')): void {
  if (mayLeave()) done()
}
function cancelReason(): void {
  if (!mayLeave()) return
  form.showing = false
  form.reason = ''
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
  const current = ++revision
  clearTimeout(timer)
  loading.value = true
  error.value = ''
  try {
    const result = await invoiceDetail(props.id, controller.signal)
    if (current !== revision) return
    const previous = detail.value?.status
    detail.value = result
    if (pending.value && ['red_submitted', 'red_issued'].includes(result.status)) {
      pending.value = undefined
      form.showing = false
      form.reason = ''
    }
    if (previous && previous !== result.status) emit('changed')
    if (['submitted', 'red_submitted'].includes(result.status) || pending.value)
      timer = setTimeout(() => {
        void load()
      }, 5000)
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) {
      error.value = messageOf(cause)
      if (cause instanceof ApiError && [403, 404].includes(cause.status)) detail.value = undefined
    }
  } finally {
    if (current === revision) loading.value = false
  }
}
async function submit(): Promise<void> {
  if (!detail.value || locked.value || readOnlyPreview) return
  if (!form.reason.trim() || form.reason.trim().length > 200) {
    error.value = '请填写红字原因，最多200字'
    return
  }
  pending.value = {
    version: detail.value.version,
    reason: form.reason.trim(),
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
    await invoiceAction(
      props.id,
      'red-issue',
      command.version,
      command.key,
      command.reason,
      controller.signal,
    )
    pending.value = undefined
    form.showing = false
    form.reason = ''
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
      if (cause instanceof ApiError && [403, 404].includes(cause.status)) detail.value = undefined
    }
  } finally {
    busy.value = false
  }
}
async function download(file: InvoiceFile): Promise<void> {
  if (!detail.value || locked.value) return
  busy.value = true
  try {
    await downloadInvoice(detail.value, file)
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
watch(
  () => [props.id, session.companyId],
  () => {
    detail.value = undefined
    void load()
  },
  { immediate: true },
)
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => {
  revision++
  clearTimeout(timer)
  controller.abort()
  removeGuard()
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <el-drawer
    :model-value="true"
    title="数电发票及红字进度"
    direction="rtl"
    size="min(960px, 96vw)"
    append-to-body
    :before-close="close"
    :show-close="!locked"
    :close-on-press-escape="!locked"
    :close-on-click-modal="false"
  >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert
      v-if="pending"
      title="红字申请结果尚未确认，请保留本次请求并重试，或刷新确认原请求进度。"
      type="warning"
      :closable="false"
    />
    <p v-if="loading" role="status">正在读取数电发票…</p>
    <template v-if="detail">
      <StatusTag
        :label="labels[detail.status]"
        :tone="detail.status === 'red_issued' ? 'success' : 'neutral'"
      />
      <dl class="invoice-summary">
        <div>
          <dt>原票号</dt>
          <dd>{{ detail.invoiceNo ?? '待开具' }}</dd>
        </div>
        <div>
          <dt>购方</dt>
          <dd>{{ detail.buyerName }}</dd>
        </div>
        <div>
          <dt>含税金额</dt>
          <dd><FinancialAmount :value="detail.amount" /></dd>
        </div>
        <div>
          <dt>税额</dt>
          <dd><FinancialAmount :value="detail.taxAmount" /></dd>
        </div>
        <div>
          <dt>红字票号</dt>
          <dd>{{ detail.redInvoiceNo ?? '尚未完成' }}</dd>
        </div>
      </dl>
      <el-alert v-if="detail.error" :title="detail.error" type="warning" :closable="false" />
      <el-alert
        v-if="detail.status === 'red_submitted'"
        title="服务商处理中，页面会刷新进度；处理完成前不能作为已完成红字。"
        type="info"
        :closable="false"
      />
      <h3>整票商品明细</h3>
      <el-table :data="detail.lines" row-key="id" empty-text="暂无商品明细">
        <el-table-column prop="goodsName" label="商品" min-width="180" />
        <el-table-column prop="quantity" label="数量" min-width="100" align="right" />
        <el-table-column label="未税金额（元）" min-width="150" align="right"
          ><template #default="{ row }"><FinancialAmount :value="row.untaxedAmount" /></template
        ></el-table-column>
        <el-table-column label="税额（元）" min-width="140" align="right"
          ><template #default="{ row }"><FinancialAmount :value="row.taxAmount" /></template
        ></el-table-column>
      </el-table>
      <div class="invoice-files">
        <el-button
          v-for="file in detail.files"
          :key="file.variant + file.fileFormat"
          :disabled="locked"
          @click="download(file)"
          >{{ file.variant === 'red' ? '红字' : '原票' }}
          {{ file.fileFormat.toUpperCase() }}</el-button
        >
      </div>
      <el-form v-if="form.showing" label-position="top" :disabled="locked" @submit.prevent="submit">
        <el-alert
          title="此操作对整张发票申请红字，包含以上全部商品。"
          type="warning"
          :closable="false"
        />
        <el-form-item label="红字原因" required
          ><el-input
            v-model="form.reason"
            type="textarea"
            maxlength="200"
            aria-label="数电红字原因"
            show-word-limit
        /></el-form-item>
        <el-button type="primary" :disabled="locked" @click="submit">确认申请整票红字</el-button>
        <el-button :disabled="locked" @click="cancelReason">返回详情</el-button>
      </el-form>
    </template>
    <template #footer>
      <el-button v-if="pending && !busy" type="primary" @click="write">重试本次请求</el-button>
      <el-button
        v-if="
          !readOnlyPreview &&
          detail?.status === 'issued' &&
          session.can('einv:red_issue') &&
          !form.showing
        "
        :disabled="locked"
        type="primary"
        @click="form.showing = true"
        >申请整票红字</el-button
      >
      <el-button :disabled="busy" @click="load">刷新进度</el-button>
      <el-button :disabled="locked" @click="close()">关闭</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.invoice-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
  margin-block: 22px;
}
.invoice-summary dt {
  color: var(--erp-text-2);
  font-size: 12px;
  margin-bottom: 8px;
}
.invoice-summary dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.invoice-files {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-block: 18px;
}
@media (max-width: 600px) {
  .invoice-summary {
    grid-template-columns: 1fr;
  }
}
</style>
