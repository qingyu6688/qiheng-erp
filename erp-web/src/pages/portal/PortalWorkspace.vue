<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { portalFile, portalRead, portalWrite } from '../../api/portal'
import { ApiError, isCancelled, messageOf } from '../../api/http'
import type { PageResult } from '../../types/api'
import {
  portalViews,
  type PortalAnswer,
  type PortalRow,
  type PortalStatement,
  type PortalView,
} from '../../types/portal'

const emit = defineEmits<{ expired: [] }>()
const route = useRoute()
const view = computed(() => (route.name ?? 'home') as PortalView)
const titled = computed(() => portalViews.find((item) => item.key === view.value)?.label ?? '概况')
const rows = ref<PortalRow[]>([])
const summary = ref<Record<string, unknown>>()
const statement = ref<PortalStatement>()
const detail = ref<PortalRow>()
const cursor = ref<string | null>(null)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const openingTicket = ref(false)
const reply = ref('')
const satisfaction = ref(5)
const question = ref('')
const conversation = ref<string>()
const responses = ref<{ question: string; answer: PortalAnswer; rated?: boolean }[]>([])
const assistantEnabled = ref(false)
const ticket = reactive({ title: '', category: 'other', content: '' })
const today = new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' })
const span = reactive({ dateFrom: today.slice(0, 7) + '-01', dateTo: today })
let scope = new AbortController()

const labels: Record<string, string> = {
  documentNo: '编号',
  businessDate: '业务日期',
  date: '日期',
  orderDate: '订单日期',
  shipmentDate: '发货日期',
  invoiceDate: '开票日期',
  title: '标题',
  status: '状态',
  fulfillmentStatus: '履约进度',
  settlementStatus: '结算进度',
  trackingNo: '物流单号',
  deliveryMethod: '交付方式',
  totalAmount: '金额',
  amount: '发生额',
  receiptAmount: '收款',
  runningBalance: '期末余额',
  direction: '方向',
  sourceNo: '来源单据',
  openAmount: '未结金额',
  remainingAmount: '未结金额',
  content: '内容',
  createdAt: '创建时间',
  pendingOrders: '待收货订单',
  pendingOrderCount: '待收货订单',
  receivableAmount: '未付款金额',
  pendingReceiptCount: '待签收发货',
  openOrderCount: '未履约订单',
  unpaidAmount: '未付款金额',
  outstandingAmount: '未付款金额',
  openingBalance: '期初余额',
  closingBalance: '期末余额',
  debitAmount: '应收发生额',
  creditAmount: '收款发生额',
  skuName: '商品',
  qty: '数量',
  signedQty: '签收数量',
  unitName: '单位',
}
const statuses: Record<string, string> = {
  draft: '草稿',
  pending: '待处理',
  approved: '已审核',
  cancelled: '已取消',
  closed: '已关闭',
  submitted: '已提交',
  posted: '已发货',
  signed: '已签收',
  signed_with_diff: '签收有差异',
  open: '待处理',
  assigned: '已分配',
  processing: '处理中',
  waiting_customer: '待客户回复',
  resolved: '已解决',
  issued: '已开具',
  red_issued: '已开红票',
  confirmed: '已确认',
  none: '未开始',
  partial: '部分完成',
  full: '已完成',
  finished: '已完成',
}
const columnsByView: Record<PortalView, string[]> = {
  home: ['documentNo', 'businessDate', 'status', 'trackingNo'],
  orders: [
    'documentNo',
    'businessDate',
    'status',
    'fulfillmentStatus',
    'settlementStatus',
    'totalAmount',
  ],
  shipments: ['documentNo', 'businessDate', 'status', 'trackingNo'],
  statements: ['businessDate', 'sourceNo', 'debitAmount', 'creditAmount', 'runningBalance'],
  invoices: ['documentNo', 'invoiceDate', 'status', 'totalAmount'],
  assistant: [],
  tickets: ['documentNo', 'title', 'status', 'createdAt'],
}
const columns = computed(() => columnsByView[view.value])
const detailLines = computed(() => list(detail.value?.lines))
const replies = computed(() => list(detail.value?.replies))
const visibleRows = computed(() =>
  view.value === 'statements' ? (statement.value?.items ?? []) : rows.value,
)
const visibleSummary = computed(() =>
  view.value === 'statements' ? statement.value?.summary : summary.value,
)
const headline = computed(() =>
  Object.entries(visibleSummary.value ?? {}).filter(([key, value]) => labels[key] && isScalar(value)),
)

function list(value: unknown): PortalRow[] {
  return Array.isArray(value)
    ? value.filter((item): item is PortalRow => item !== null && typeof item === 'object')
    : []
}

function isScalar(value: unknown): boolean {
  return typeof value === 'string' || typeof value === 'number'
}

function field(row: PortalRow, key: string): string {
  const value =
    key === 'documentNo'
      ? (row.documentNo ?? row.orderNo ?? row.shipmentNo ?? row.invoiceNo ?? row.no)
      : key === 'trackingNo'
        ? (row.logisticsNo ?? row.trackingNo)
        : key === 'invoiceDate'
          ? (row.issuedAt ?? row.invoiceDate)
          : key === 'totalAmount'
            ? (row.totalAmount ?? row.amount)
            : key === 'signedQty'
              ? (row.signedQty ?? row.signedBaseQty)
              : row[key]
  if (!isScalar(value)) return '—'
  const text = String(value)
  return key === 'status' || key.endsWith('Status') ? (statuses[text] ?? text) : text
}

async function perform(action: (current: AbortSignal) => Promise<void>): Promise<void> {
  const current = scope.signal
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await action(current)
  } catch (cause: unknown) {
    if (current.aborted || isCancelled(cause)) return
    if (cause instanceof ApiError && cause.status === 401) emit('expired')
    else error.value = messageOf(cause)
  } finally {
    if (!current.aborted) busy.value = false
  }
}

async function fetchList(current: AbortSignal, more: boolean): Promise<void> {
  const result = await portalRead<PageResult<PortalRow>>(
    '/' + view.value,
    { cursor: more ? cursor.value : undefined },
    current,
  )
  if (current.aborted) return
  rows.value = more ? [...rows.value, ...result.items] : result.items
  cursor.value = result.nextCursor
}

async function refresh(current: AbortSignal): Promise<void> {
  switch (view.value) {
    case 'home': {
      const result = await portalRead<Record<string, unknown>>('/dashboard', undefined, current)
      if (!current.aborted) {
        rows.value = list(result.recentShipments)
        summary.value = result
      }
      break
    }
    case 'statements': {
      const period = { ...span }
      statement.value = undefined
      const result = await portalRead<PortalStatement>('/statements', period, current)
      if (!current.aborted && period.dateFrom === span.dateFrom && period.dateTo === span.dateTo) {
        statement.value = result
      }
      break
    }
    case 'assistant': {
      const config = await portalRead<{ enabled: boolean }>('/assistant/status', undefined, current)
      if (!current.aborted) assistantEnabled.value = config.enabled
      break
    }
    default:
      await fetchList(current, false)
  }
}

function reset(): void {
  scope.abort()
  scope = new AbortController()
  rows.value = []
  detail.value = undefined
  summary.value = undefined
  statement.value = undefined
  cursor.value = null
  openingTicket.value = false
  reply.value = ''
  question.value = ''
  conversation.value = undefined
  responses.value = []
  assistantEnabled.value = false
  void perform(refresh)
}

function sendQuestion(): void {
  const text = question.value.trim()
  if (!text || busy.value) return
  void perform(async (current) => {
    const answer = await portalWrite<PortalAnswer>(
      '/assistant',
      { text, conversationId: conversation.value },
      current,
    )
    if (current.aborted) return
    conversation.value = answer.conversationId
    responses.value.push({ question: text, answer })
    question.value = ''
  })
}

function rateAnswer(
  entry: { question: string; answer: PortalAnswer; rated?: boolean },
  vote: 'up' | 'down',
): void {
  if (entry.rated || busy.value) return
  void perform(async (current) => {
    const { answerId, conversationId } = entry.answer
    const result = await portalWrite<PortalAnswer>(
      '/assistant/feedback',
      { answerId, conversationId, vote },
      current,
    )
    if (current.aborted) return
    entry.rated = true
    if (result.handedOff) entry.answer = result
    else notice.value = result.message
  })
}

function visibleSource(path: string | undefined | null): string | undefined {
  return portalViews.some((view) => view.path === path && view.key !== 'assistant')
    ? (path ?? undefined)
    : undefined
}

function open(row: PortalRow): void {
  if (!row.id) return
  const path = view.value === 'home' ? 'shipments' : view.value
  reply.value = ''
  void perform(async (current) => {
    const result = await portalRead<PortalRow>(
      '/' + path + '/' + encodeURIComponent(row.id!),
      undefined,
      current,
    )
    if (!current.aborted) detail.value = unwrap(result)
  })
}

function unwrap(result: PortalRow): PortalRow {
  const record = result.order ?? result.shipment ?? result.ticket
  return record !== null && typeof record === 'object'
    ? { ...(record as PortalRow), lines: result.lines, replies: result.replies }
    : result
}

function download(path: string, name: string, params?: Record<string, unknown>): void {
  void perform(async (current) => {
    const file = await portalFile(path, params, current)
    if (current.aborted) return
    const link = document.createElement('a')
    link.href = URL.createObjectURL(file)
    link.download = /ofd/i.test(file.type) ? name.replace(/\.pdf$/, '.ofd') : name
    link.click()
    const objectUrl = link.href
    setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
  })
}

function confirmStatement(): void {
  const digest = statement.value?.digest
  if (!digest || !window.confirm('确认此期间对账单？')) return
  void perform(async (current) => {
    await portalWrite('/statements/confirm', { ...span, digest }, current)
    if (current.aborted) return
    await refresh(current)
    if (!current.aborted) notice.value = '对账单已确认。'
  })
}

function createTicket(): void {
  void perform(async (current) => {
    await portalWrite('/tickets', ticket, current)
    if (current.aborted) return
    openingTicket.value = false
    Object.assign(ticket, { title: '', category: 'other', content: '' })
    await fetchList(current, false)
    if (!current.aborted) notice.value = '工单已提交。'
  })
}

function sendReply(): void {
  const id = detail.value?.id
  if (!id || !reply.value.trim()) return
  void perform(async (current) => {
    await portalWrite(
      '/tickets/' + encodeURIComponent(id) + '/reply',
      { content: reply.value.trim() },
      current,
    )
    const refreshed = await portalRead<PortalRow>(
      '/tickets/' + encodeURIComponent(id),
      undefined,
      current,
    )
    if (!current.aborted) {
      detail.value = unwrap(refreshed)
      reply.value = ''
    }
  })
}

function rateTicket(): void {
  const id = detail.value?.id
  if (!id) return
  void perform(async (current) => {
    const path = '/tickets/' + encodeURIComponent(id)
    await portalWrite(path + '/rate', { satisfaction: satisfaction.value }, current)
    const result = await portalRead<PortalRow>(path, undefined, current)
    if (!current.aborted) {
      detail.value = unwrap(result)
      notice.value = '评价已提交。'
    }
  })
}

watch(span, () => {
  statement.value = undefined
  error.value = ''
  notice.value = ''
})
watch(view, reset, { immediate: true })
onScopeDispose(() => scope.abort())
</script>

<template>
  <main>
    <h1>{{ titled }}</h1>
    <section class="portal-panel" :aria-busy="busy">
      <p v-if="error" class="portal-error" role="alert">{{ error }}</p>
      <p v-if="notice" role="status">{{ notice }}</p>
      <form v-if="view === 'statements'" class="portal-toolbar" @submit.prevent="perform(refresh)">
        <label
          >开始日期<input
            v-model="span.dateFrom"
            required
            type="date"
            :disabled="busy"
        /></label>
        <label
          >结束日期<input
            v-model="span.dateTo"
            required
            type="date"
            :disabled="busy"
        /></label>
        <button class="portal-primary" :disabled="busy" type="submit">查询</button>
        <button
          type="button"
          :disabled="busy || !statement"
          @click="download('/statements/pdf', '客户对账单.pdf', span)"
        >
          下载对账单
        </button>
        <button
          type="button"
          :disabled="busy || !statement?.digest || Boolean(statement.confirmation)"
          @click="confirmStatement"
        >
          {{ statement?.confirmation ? '已确认' : '确认对账' }}
        </button>
      </form>
      <template v-if="view === 'assistant'">
        <p v-if="!assistantEnabled && !busy">在线客服暂未开放，可在客服工单中提交问题。</p>
        <div v-else class="portal-dialogue" aria-live="polite">
          <article v-for="(entry, index) in responses" :key="entry.answer.answerId">
            <p class="portal-note"><strong>我</strong> {{ entry.question }}</p>
            <p class="portal-note"><strong>客服</strong> {{ entry.answer.message }}</p>
            <ul v-if="entry.answer.items.length">
              <li v-for="(row, i) in entry.answer.items" :key="row.id ?? i">
                {{ field(row, 'documentNo') }} · {{ field(row, 'status') }}
              </li>
            </ul>
            <p v-if="entry.answer.summary.closingBalance !== undefined">
              期末余额 {{ entry.answer.summary.closingBalance }}
            </p>
            <RouterLink
              v-if="visibleSource(entry.answer.sourcePath)"
              :to="visibleSource(entry.answer.sourcePath)!"
              >查看来源</RouterLink
            >
            <div
              v-if="
                index === responses.length - 1 &&
                !entry.rated &&
                !entry.answer.refused &&
                !entry.answer.handedOff
              "
              class="portal-toolbar"
            >
              <button type="button" :disabled="busy" @click="rateAnswer(entry, 'up')">
                有帮助
              </button>
              <button type="button" :disabled="busy" @click="rateAnswer(entry, 'down')">
                未解决
              </button>
            </div>
          </article>
        </div>
        <form
          v-if="assistantEnabled"
          class="portal-form portal-detail"
          @submit.prevent="sendQuestion"
        >
          <label
            >咨询订单、物流、对账或发票<textarea
              v-model.trim="question"
              required
              maxlength="1000"
              rows="3"
            />
          </label>
          <button type="submit" class="portal-primary" :disabled="busy || !question">发送</button>
        </form>
      </template>
      <template v-if="view === 'tickets'">
        <button type="button" class="portal-primary" @click="openingTicket = !openingTicket">
          {{ openingTicket ? '取消' : '提交工单' }}
        </button>
        <form v-if="openingTicket" class="portal-form portal-detail" @submit.prevent="createTicket">
          <label>标题<input v-model.trim="ticket.title" required maxlength="120" /></label>
          <label
            >类别<select v-model="ticket.category">
              <option value="order">订单</option>
              <option value="logistics">物流</option>
              <option value="invoice">发票</option>
              <option value="quality">质量</option>
              <option value="complaint">投诉</option>
              <option value="other">其他</option>
            </select></label
          >
          <label
            >问题描述<textarea v-model.trim="ticket.content" required maxlength="2000" rows="4" />
          </label>
          <button class="portal-primary" :disabled="busy" type="submit">提交</button>
        </form>
      </template>
      <div v-if="headline.length" class="portal-summary">
        <dl v-for="[key, value] in headline" :key="key">
          <dt>{{ labels[key] }}</dt>
          <dd>{{ value }}</dd>
        </dl>
      </div>
      <p v-if="busy && !visibleRows.length" role="status">正在加载…</p>
      <p v-else-if="view === 'statements' && !statement && !error" role="status">
        请选择日期并查询对账单。
      </p>
      <p v-else-if="view !== 'assistant' && !visibleRows.length && !error" role="status">暂无记录。</p>
      <div v-if="visibleRows.length" class="portal-scroll">
        <table class="portal-table">
          <thead>
            <tr>
              <th v-for="key in columns" :key="key" scope="col">{{ labels[key] }}</th>
              <th v-if="view !== 'statements'" scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in visibleRows" :key="row.id ?? index">
              <td v-for="key in columns" :key="key">{{ field(row, key) }}</td>
              <td v-if="view !== 'statements'">
                <button
                  v-if="row.id && view !== 'invoices'"
                  type="button"
                  :disabled="busy"
                  @click="open(row)"
                >
                  查看
                </button>
              <button
                v-else-if="row.id && row.invoiceKind !== 'registration'"
                  type="button"
                  :disabled="busy"
                  @click="
                    download(
                      '/invoices/' + encodeURIComponent(row.id) + '/file',
                      '发票-' + field(row, 'documentNo') + '.pdf',
                    )
                  "
                >
                  下载
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <button
        v-if="cursor"
        :disabled="busy"
        type="button"
        @click="perform((current) => fetchList(current, true))"
      >
        加载更多
      </button>
    </section>
    <section v-if="detail" class="portal-panel portal-detail" aria-label="详情">
      <div class="portal-toolbar">
        <h2>{{ field(detail, 'documentNo') }}</h2>
        <button type="button" @click="detail = undefined">关闭详情</button>
      </div>
      <div class="portal-summary">
        <dl v-for="key in columns.filter((value) => value !== 'documentNo')" :key="key">
          <dt>{{ labels[key] }}</dt>
          <dd>{{ field(detail, key) }}</dd>
        </dl>
      </div>
      <div v-if="detailLines.length" class="portal-scroll">
        <table class="portal-table">
          <thead>
            <tr>
              <th v-for="key in ['skuName', 'qty', 'unitName', 'signedQty']" :key="key" scope="col">
                {{ labels[key] }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(line, index) in detailLines" :key="line.id ?? index">
              <td v-for="key in ['skuName', 'qty', 'unitName', 'signedQty']" :key="key">
                {{ field(line, key) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <template v-if="view === 'tickets'">
        <p v-if="typeof detail.content === 'string'" class="portal-note">{{ detail.content }}</p>
        <ul v-if="replies.length">
          <li v-for="(item, index) in replies" :key="item.id ?? index">
            <p class="portal-note">{{ field(item, 'content') }}</p>
          </li>
        </ul>
        <form
          v-if="!['resolved', 'closed'].includes(detail.status ?? '')"
          class="portal-form"
          @submit.prevent="sendReply"
        >
          <label>回复<textarea v-model.trim="reply" required maxlength="2000" rows="3" /></label>
          <button class="portal-primary" type="submit" :disabled="busy || !reply">发送</button>
        </form>
        <form v-else-if="!detail.satisfaction" class="portal-toolbar" @submit.prevent="rateTicket">
          <label
            >满意度<select v-model.number="satisfaction">
              <option v-for="value in 5" :key="value" :value="value">{{ value }} 分</option>
            </select></label
          >
          <button type="submit" :disabled="busy">提交评价</button>
        </form>
      </template>
    </section>
  </main>
</template>
