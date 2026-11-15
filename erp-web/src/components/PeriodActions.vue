<script setup lang="ts">
import { ref } from 'vue'
import { checkPeriod, changePeriod } from '../api/period'
import type { PeriodCheck } from '../api/period'
import { commandResult } from '../api/sales'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import type { DataRow } from '../types/admin'
import { useSessionStore } from '../stores/session'
const props = defineProps<{ period: DataRow }>()
const emit = defineEmits<{ changed: [] }>()
const session = useSessionStore()
const action = ref<'check' | 'close' | 'reopen'>('check'), visible = ref(false), report = ref<PeriodCheck>()
const busy = ref(false), checking = ref(false), error = ref(''), reason = ref(''), uncertain = ref(false), conflict = ref(false), retryOriginal = ref(false)
const labels: Record<string, string> = { unposted_documents: '未过账单据', pending_money: '待确认资金', inventory_reconciliation: '库存对账', money_reconciliation: '资金往来对账', cost_residue: '成本尾差' }
const documentLabels: Record<string, string> = { shipment: '发货单', goods_receipt: '收货单', sales_return: '销售退货', purchase_return: '采购退货', stock_transfer: '调拨单', stock_count: '盘点单', stock_adjustment: '库存调整', document_reversal: '冲销单', receipt: '收款单', payment: '付款单', refund_request: '退款申请', account_transfer: '账户转账', opening_entry: '往来及资金期初' }
let key = newIdempotencyKey()
let submitted: { version: number; reason?: string } | undefined
async function inspect(): Promise<void> {
  checking.value = true; error.value = ''
  try { report.value = await checkPeriod(props.period.id) } catch (cause: unknown) { if (!isCancelled(cause)) error.value = messageOf(cause) } finally { checking.value = false }
}
function open(next: 'check' | 'close' | 'reopen'): void {
  action.value = next; visible.value = true; report.value = undefined; error.value = ''; reason.value = ''; uncertain.value = false; conflict.value = false; retryOriginal.value = false; submitted = undefined; key = newIdempotencyKey()
  if (next !== 'reopen') void inspect()
}
async function save(): Promise<void> {
  if (action.value === 'check' || busy.value || uncertain.value || conflict.value) return
  if (props.period.version === undefined) { error.value = '期间版本缺失，请刷新后重试'; return }
  if (action.value === 'reopen' && !reason.value.trim()) { error.value = '请填写重新打开期间的原因'; return }
  busy.value = true; error.value = ''
  try {
    submitted ??= { version: props.period.version, reason: action.value === 'reopen' ? reason.value : undefined }
    await changePeriod(props.period.id, action.value, submitted, key)
    visible.value = false; emit('changed')
  } catch (cause: unknown) {
    if (!isCancelled(cause)) {
      error.value = messageOf(cause)
      if (cause instanceof ApiError) { uncertain.value = cause.status === 0 || cause.code === 'REQUEST_IN_PROGRESS'; conflict.value = cause.code === 'VERSION_CONFLICT' }
      if (!uncertain.value) { submitted = undefined; retryOriginal.value = false }
    }
  } finally { busy.value = false }
}
async function verify(): Promise<void> {
  busy.value = true
  try {
    const result = await commandResult('period.' + action.value, key)
    if (result.status === 'succeeded') { visible.value = false; emit('changed') }
  } catch (cause: unknown) { error.value = messageOf(cause); if (cause instanceof ApiError && cause.status === 404) { uncertain.value = false; retryOriginal.value = true } } finally { busy.value = false }
}
</script>
<template>
  <div class="row-actions">
    <button v-if="session.can('period:close')" class="text-link" @click="open('check')">关账预检查</button>
    <button v-if="period.status === 'open' && session.can('period:close')" class="text-link" @click="open('close')">关账</button>
    <button v-if="period.status === 'closed' && session.can('period:reopen')" class="text-link" @click="open('reopen')">重新打开</button>
  </div>
  <el-dialog v-model="visible" :title="action === 'reopen' ? '重新打开期间' : action === 'close' ? '关闭会计期间' : '关账预检查'" width="760px" :close-on-click-modal="false" :show-close="!busy">
    <p>会计期间 {{ period.period }}</p>
    <p v-if="checking">正在检查…</p>
    <template v-if="action !== 'reopen'">
      <el-table v-if="report" :data="report.items" row-key="check">
        <el-table-column label="检查项" width="150"><template #default="{ row }">{{ labels[row.check] ?? row.check }}</template></el-table-column>
        <el-table-column label="结果" width="90"><template #default="{ row }"><el-tag :type="row.passed ? 'success' : 'danger'">{{ row.passed ? '通过' : '未通过' }}</el-tag></template></el-table-column>
        <el-table-column prop="count" label="待处理数" width="100"/><el-table-column label="说明"><template #default="{ row }">{{ row.message }}<p v-for="document in row.documents ?? []" :key="document.documentType + (document.documentId ?? '')">{{ documentLabels[document.documentType] ?? '相关单据' }} {{ document.documentNo }}</p></template></el-table-column>
      </el-table>
      <p>检查通过后才可关账；关账时会复核最新单据与账务。已关闭期间的更正应记入开放期间。</p>
    </template>
    <el-form-item v-else label="重开原因" for="period-reopen-reason"><el-input id="period-reopen-reason" v-model="reason" type="textarea" maxlength="200" :disabled="busy || uncertain || conflict || retryOriginal"/></el-form-item>
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <p v-if="uncertain">操作结果确认中，请查询原请求结果。</p><p v-if="conflict">期间已更新，请关闭并刷新。</p>
    <p v-if="retryOriginal">尚未查到已提交结果，将使用原内容重试。</p>
    <template #footer>
      <el-button :disabled="busy" @click="visible = false">关闭</el-button>
      <el-button v-if="action !== 'reopen' && !uncertain" :loading="checking" :disabled="busy || conflict" @click="inspect">重新检查</el-button>
      <el-button v-if="uncertain" type="primary" :loading="busy" @click="verify">查询结果</el-button>
      <el-button v-else-if="action !== 'check'" type="primary" :loading="busy" :disabled="checking || conflict || (action === 'close' && !report?.passed)" @click="save">{{ action === 'close' ? '确认关账' : '确认重新打开' }}</el-button>
    </template>
  </el-dialog>
</template>
