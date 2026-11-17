<script setup lang="ts">
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import { computed, reactive, ref, watch } from 'vue'
import { allRecords } from '../api/admin'
import {
  createInvoice,
  downloadInvoice,
  inboundInvoices,
  invoiceAction,
  invoiceCandidates,
  invoiceDetail,
  invoiceProvider,
  invoiceRequests,
  invoiceSkus,
  linkPayable,
  payableCandidates,
  saveInvoiceProvider,
  saveSkuTaxCategory,
  syncInvoices,
  testInvoiceProvider,
} from '../api/e-invoice'
import type {
  InboundInvoice,
  InvoiceCandidate,
  InvoiceDetail,
  InvoiceFile,
  InvoiceKind,
  InvoicePayable,
  InvoiceProvider,
  InvoiceRequest,
  InvoiceSku,
  InvoiceStatus,
} from '../api/e-invoice'
import { isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import { useSessionStore } from '../stores/session'
import type { DataRow } from '../types/admin'

type Tab = 'requests' | 'inbound' | 'provider' | 'skus'
type Dialog = 'create' | 'detail' | 'match' | 'tax'

const labels: Record<InvoiceStatus, string> = {
  draft: '待确认',
  submitted: '开具中',
  issued: '已开具',
  red_submitted: '红冲中',
  red_issued: '已红冲',
  failed: '开具失败',
}
const session = useSessionStore()
const tab = ref<Tab>('requests')
const dialog = ref<Dialog>()
const dialogVisible = computed({
  get: () => dialog.value !== undefined,
  set: (v) => {
    if (!v) dialog.value = undefined
  },
})
const dialogTitle = computed(() =>
  dialog.value === 'create'
    ? '新建开具申请'
    : dialog.value === 'detail'
      ? '开具详情'
      : dialog.value === 'match'
        ? '匹配采购应付'
        : '维护税收分类编码',
)
const requests = ref<InvoiceRequest[]>([])
const inbound = ref<InboundInvoice[]>([])
const detail = ref<InvoiceDetail>()
const skus = ref<InvoiceSku[]>([])
const selectedSku = ref<InvoiceSku>()
const taxCode = ref('')
const keyword = ref('')
const provider = ref<InvoiceProvider>()
const providerForm = reactive({ taxNo: '', token: '', enabled: false })
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const dialogError = ref('')
const pages = ref<string[]>([])
const canManage = computed(() => session.can('einv:manage') && session.company?.allWarehouses)
const canInbound = computed(() => session.can('einv:inbound') && session.company?.allWarehouses)
const cursor = computed(() => pages.value.at(-1))
const currentRows = computed(() => (tab.value === 'inbound' ? inbound.value : requests.value))
const sequence = ref(0)
const partners = ref<DataRow[]>([])
const candidates = ref<InvoiceCandidate[]>([])
const draft = reactive<{ partnerId: string; invoiceKind: InvoiceKind; receivableIds: string[] }>({
  partnerId: '',
  invoiceKind: 'normal',
  receivableIds: [],
})
const selectedInbound = ref<InboundInvoice>()
const payables = ref<InvoicePayable[]>([])
const payableId = ref('')
const showRedInput = ref(false)
const redReason = ref('')
const syncRange = reactive({ from: '', to: '' })
let commandKey = ''
let commandSignature = ''

function key(signature: string): string {
  if (commandSignature !== signature) {
    commandSignature = signature
    commandKey = newIdempotencyKey()
  }
  return commandKey
}

async function load(): Promise<void> {
  if (!session.companyId) return
  const version = ++sequence.value
  const currentTab = tab.value
  loading.value = true
  error.value = ''
  try {
    if (currentTab === 'requests') {
      const rows = await invoiceRequests(cursor.value)
      if (version === sequence.value) requests.value = rows
    } else if (currentTab === 'inbound' && canInbound.value) {
      const rows = await inboundInvoices(cursor.value)
      if (version === sequence.value) inbound.value = rows
    } else if (currentTab === 'provider' && canManage.value) {
      const result = await invoiceProvider()
      if (version === sequence.value) setProvider(result)
    } else if (currentTab === 'skus' && canManage.value) {
      const rows = await invoiceSkus(keyword.value.trim())
      if (version === sequence.value) skus.value = rows
    }
  } catch (cause: unknown) {
    if (version === sequence.value && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (version === sequence.value) loading.value = false
  }
}

function setProvider(value: InvoiceProvider): void {
  provider.value = value
  Object.assign(providerForm, { taxNo: value.taxNo, token: '', enabled: value.enabled })
}

function followPage(next: boolean): void {
  const last = currentRows.value.at(-1)
  if (next && last) pages.value.push(last.id)
  if (!next) pages.value.pop()
  void load()
}

async function perform(work: () => Promise<void>, inDialog = false): Promise<void> {
  if (busy.value || !session.companyId) return
  const company = session.companyId
  const output = inDialog ? dialogError : error
  output.value = ''
  busy.value = true
  try {
    await work()
  } catch (cause: unknown) {
    if (company === session.companyId && !isCancelled(cause)) output.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

function start(view: Dialog): void {
  dialogError.value = ''
  dialog.value = view
  commandSignature = ''
}

function newDraft(): void {
  start('create')
  Object.assign(draft, { partnerId: '', invoiceKind: 'normal', receivableIds: [] })
  candidates.value = []
  const company = session.companyId
  if (session.can('partner:view'))
    void perform(async () => {
      const rows = await allRecords('partners')
      if (session.companyId === company && dialog.value === 'create')
        partners.value = rows.filter((row) => row.status === 'active')
    }, true)
}

function findCandidates(): void {
  const partner = draft.partnerId.trim()
  candidates.value = []
  draft.receivableIds = []
  if (!/^\d+$/.test(partner)) return
  void perform(async () => {
    candidates.value = await invoiceCandidates(partner)
  }, true)
}

function saveDraft(): void {
  void perform(async () => {
    const payload = {
      ...draft,
      partnerId: draft.partnerId.trim(),
      receivableIds: [...draft.receivableIds].sort(),
    }
    const result = await createInvoice(payload, key(JSON.stringify(payload)))
    ElMessage.success('已创建开具申请')
    detail.value = await invoiceDetail(result.id)
    dialog.value = 'detail'
    pages.value = []
    await load()
  }, true)
}

function openRequest(row: InvoiceRequest): void {
  detail.value = undefined
  showRedInput.value = false
  redReason.value = ''
  start('detail')
  void perform(async () => {
    detail.value = await invoiceDetail(row.id)
  }, true)
}

function execute(action: 'submit' | 'red-issue'): void {
  const document = detail.value
  if (!document) return
  void perform(async () => {
    const reason = action === 'red-issue' ? redReason.value.trim() : undefined
    await invoiceAction(
      document.id,
      action,
      document.version,
      key(JSON.stringify([document.id, action, document.version, reason])),
      reason,
    )
    detail.value = await invoiceDetail(document.id)
    showRedInput.value = false
    redReason.value = ''
    ElMessage.success('申请已提交，等待服务商处理')
    await load()
  }, true)
}

function download(file: InvoiceFile): void {
  const document = detail.value
  if (document) void perform(() => downloadInvoice(document, file), true)
}

function syncInbound(): void {
  void perform(async () => {
    const result = await syncInvoices(syncRange.from, syncRange.to)
    ElMessage.success(`已同步 ${result.count} 张进项发票`)
    pages.value = []
    await load()
  })
}

function openMatch(invoice: InboundInvoice): void {
  selectedInbound.value = invoice
  payableId.value = invoice.payableId ?? ''
  payables.value = []
  start('match')
  void perform(async () => {
    payables.value = await payableCandidates(invoice.id)
  }, true)
}

function saveMatch(unlink = false): void {
  const invoice = selectedInbound.value
  if (!invoice || (!unlink && !payableId.value)) return
  void perform(async () => {
    const target = unlink ? null : payableId.value
    await linkPayable(invoice, target, key(JSON.stringify([invoice.id, invoice.version, target])))
    dialog.value = undefined
    await load()
  }, true)
}

function saveProvider(): void {
  const current = provider.value
  if (!current) return
  void perform(async () => {
    setProvider(
      await saveInvoiceProvider({
        ...providerForm,
        taxNo: providerForm.taxNo.trim(),
        version: current.version,
      }),
    )
    ElMessage.success('服务商配置已保存')
  })
}

function testProvider(): void {
  void perform(async () => {
    await testInvoiceProvider()
    ElMessage.success('已连接服务商网关')
  })
}

function editTax(sku: InvoiceSku): void {
  selectedSku.value = sku
  taxCode.value = sku.taxCategoryCode ?? ''
  start('tax')
}

function saveTax(): void {
  const sku = selectedSku.value
  if (!sku) return
  void perform(async () => {
    await saveSkuTaxCategory(sku, taxCode.value.trim() || null)
    dialog.value = undefined
    await load()
  }, true)
}

watch(
  [tab, () => session.companyId],
  ([, company], [, lastCompany]) => {
    if (company !== lastCompany) {
      sequence.value++
      dialog.value = undefined
      requests.value = []
      inbound.value = []
      partners.value = []
      skus.value = []
      provider.value = undefined
      providerForm.token = ''
      detail.value = undefined
      selectedSku.value = undefined
      selectedInbound.value = undefined
      candidates.value = []
      payables.value = []
      if (tab.value !== 'requests') {
        tab.value = 'requests'
        return
      }
    }
    pages.value = []
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <div class="page-heading">
    <div>
      <h1>数电发票</h1>
      <p class="muted">按已签收应收开票，查询服务商处理结果并核对采购进项。</p>
    </div>
    <div class="invoice-actions">
      <el-button :loading="loading" :disabled="busy" @click="load">刷新</el-button>
      <el-button
        v-if="tab === 'requests' && session.can('einv:create')"
        type="primary"
        :disabled="busy"
        @click="newDraft"
        >新建开具申请</el-button
      >
    </div>
  </div>
  <section class="panel">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-tabs v-model="tab">
      <el-tab-pane name="requests" label="开具记录">
        <el-table v-loading="loading" :data="requests" row-key="id" empty-text="暂无开具申请">
          <el-table-column prop="buyerName" label="购方" min-width="160" />
          <el-table-column label="发票类型" width="100"
            ><template #default="{ row }">{{
              row.invoiceKind === 'special' ? '数电专票' : '数电普票'
            }}</template></el-table-column
          >
          <el-table-column prop="amount" label="价税合计" align="right" width="110" />
          <el-table-column prop="taxAmount" label="税额" align="right" width="100" />
          <el-table-column prop="invoiceNo" label="发票号码" min-width="140" />
          <el-table-column label="状态" width="110"
            ><template #default="{ row }">{{
              labels[row.status as InvoiceStatus]
            }}</template></el-table-column
          >
          <el-table-column label="操作" width="90"
            ><template #default="{ row }"
              ><el-button
                link
                type="primary"
                :disabled="busy"
                @click="openRequest(row as InvoiceRequest)"
                >详情</el-button
              ></template
            ></el-table-column
          >
        </el-table>
      </el-tab-pane>
      <el-tab-pane v-if="canInbound" name="inbound" label="进项台账">
        <el-form inline @submit.prevent="syncInbound">
          <el-form-item label="起始日期"
            ><el-input v-model="syncRange.from" type="date"
          /></el-form-item>
          <el-form-item label="截止日期"
            ><el-input v-model="syncRange.to" type="date"
          /></el-form-item>
          <el-form-item
            ><el-button
              native-type="submit"
              :loading="busy"
              :disabled="!syncRange.from || !syncRange.to"
              >同步进项</el-button
            ></el-form-item
          >
        </el-form>
        <el-table v-loading="loading" :data="inbound" row-key="id" empty-text="暂无进项发票">
          <el-table-column prop="invoiceNo" label="发票号码" min-width="150" />
          <el-table-column prop="sellerName" label="销方" min-width="150" />
          <el-table-column prop="invoiceDate" label="开票日期" width="120" />
          <el-table-column prop="amount" label="价税合计" align="right" width="120" />
          <el-table-column label="状态" width="92"
            ><template #default="{ row }">{{
              row.status === 'normal' ? '正常' : row.status === 'red_issued' ? '已红冲' : '已作废'
            }}</template></el-table-column
          >
          <el-table-column label="勾选" width="80"
            ><template #default="{ row }">{{
              row.checked ? '已勾选' : '未勾选'
            }}</template></el-table-column
          >
          <el-table-column label="应付关联" width="100"
            ><template #default="{ row }">
              <el-button
                v-if="row.status === 'normal'"
                link
                type="primary"
                :disabled="busy"
                @click="openMatch(row as InboundInvoice)"
                >{{ row.payableId ? '查看匹配' : '匹配' }}</el-button
              >
            </template></el-table-column
          >
        </el-table>
      </el-tab-pane>
      <el-tab-pane v-if="canManage" name="provider" label="服务商配置">
        <el-form
          v-if="provider"
          class="provider-form"
          label-position="top"
          @submit.prevent="saveProvider"
        >
          <el-alert
            v-if="!provider.gatewayAvailable"
            title="请由运维配置服务商网关地址"
            type="warning"
            :closable="false"
          />
          <el-form-item label="销方纳税人识别号"
            ><el-input v-model="providerForm.taxNo" maxlength="32"
          /></el-form-item>
          <el-form-item label="网关访问密钥"
            ><el-input
              v-model="providerForm.token"
              type="password"
              maxlength="1000"
              :placeholder="provider.tokenConfigured ? '留空保留现有密钥' : '请输入网关访问密钥'"
          /></el-form-item>
          <el-checkbox v-model="providerForm.enabled" :disabled="!provider.gatewayAvailable"
            >启用服务商</el-checkbox
          >
          <div class="invoice-actions">
            <el-button
              type="primary"
              native-type="submit"
              :loading="busy"
              :disabled="!providerForm.taxNo.trim()"
              >保存配置</el-button
            >
            <el-button
              :disabled="busy || !provider.tokenConfigured || !provider.gatewayAvailable"
              @click="testProvider"
              >测试已保存的连接</el-button
            >
          </div>
        </el-form>
      </el-tab-pane>
      <el-tab-pane v-if="canManage && session.can('sku:view')" name="skus" label="商品税码">
        <el-form inline @submit.prevent="load">
          <el-form-item label="商品"
            ><el-input
              v-model="keyword"
              placeholder="商品编码、名称或条码"
              maxlength="100"
              clearable
          /></el-form-item>
          <el-form-item
            ><el-button native-type="submit" :loading="loading">查询</el-button></el-form-item
          >
        </el-form>
        <el-table v-loading="loading" :data="skus" row-key="id" empty-text="未找到商品">
          <el-table-column prop="code" label="商品编码" min-width="140" />
          <el-table-column prop="name" label="名称" min-width="160" />
          <el-table-column prop="taxCategoryCode" label="税收分类编码" min-width="150" />
          <el-table-column v-if="session.can('sku:update')" label="操作" width="90"
            ><template #default="{ row }"
              ><el-button link type="primary" :disabled="busy" @click="editTax(row as InvoiceSku)"
                >编辑</el-button
              ></template
            ></el-table-column
          >
        </el-table>
      </el-tab-pane>
    </el-tabs>
    <div
      v-if="(tab === 'requests' || tab === 'inbound') && (pages.length || currentRows.length >= 30)"
      class="invoice-actions"
    >
      <el-button :disabled="!pages.length || loading" @click="followPage(false)">上一页</el-button>
      <el-button :disabled="currentRows.length < 30 || loading" @click="followPage(true)"
        >下一页</el-button
      >
    </div>
  </section>

  <el-dialog
    v-model="dialogVisible"
    :title="dialogTitle"
    width="min(900px,calc(100vw - 32px))"
    :close-on-click-modal="false"
    :close-on-press-escape="!busy"
    :show-close="!busy"
  >
    <el-alert v-if="dialogError" :title="dialogError" type="error" :closable="false" />
    <template v-if="dialog === 'create'">
      <el-form inline @submit.prevent="findCandidates">
        <el-form-item label="购方">
          <el-select
            v-if="partners.length"
            v-model="draft.partnerId"
            filterable
            :disabled="busy"
            class="invoice-select"
            @change="findCandidates"
          >
            <el-option
              v-for="partner in partners"
              :key="partner.id"
              :value="partner.id"
              :label="partner.name ?? partner.id"
            />
          </el-select>
          <el-input v-else v-model="draft.partnerId" placeholder="输入客户编号" :disabled="busy" />
        </el-form-item>
        <el-form-item label="发票类型"
          ><el-select v-model="draft.invoiceKind" class="invoice-select"
            ><el-option label="数电普票" value="normal" /><el-option
              label="数电专票"
              value="special" /></el-select
        ></el-form-item>
        <el-form-item
          ><el-button native-type="submit" :disabled="!draft.partnerId || busy"
            >查询应收</el-button
          ></el-form-item
        >
      </el-form>
      <el-table
        v-loading="busy"
        :data="candidates"
        row-key="id"
        max-height="360"
        empty-text="暂无可关联的签收应收"
        @selection-change="
          (rows: InvoiceCandidate[]) => (draft.receivableIds = rows.map((row) => row.id))
        "
      >
        <el-table-column
          type="selection"
          width="48"
          :selectable="(row: InvoiceCandidate) => row.canIssue"
        />
        <el-table-column prop="shipmentNo" label="发货单" min-width="125" />
        <el-table-column prop="goodsName" label="商品" min-width="145" />
        <el-table-column prop="businessDate" label="签收日期" width="110" />
        <el-table-column prop="amount" label="价税合计" align="right" width="105" />
        <el-table-column prop="taxAmount" label="税额" align="right" width="85" />
        <el-table-column label="开票条件" min-width="130"
          ><template #default="{ row }">{{
            row.canIssue ? '可开具' : '请核对商品税码和数量'
          }}</template></el-table-column
        >
      </el-table>
    </template>
    <template v-else-if="dialog === 'detail' && detail">
      <dl class="invoice-summary">
        <dt>购方</dt>
        <dd>{{ detail.buyerName }}</dd>
        <dt>购方纳税人识别号</dt>
        <dd>{{ detail.buyerTaxNo || '—' }}</dd>
        <dt>状态</dt>
        <dd>{{ labels[detail.status] }}</dd>
        <dt>价税合计</dt>
        <dd>{{ detail.amount }}</dd>
        <dt>税额</dt>
        <dd>{{ detail.taxAmount }}</dd>
        <dt>发票号码</dt>
        <dd>{{ detail.invoiceNo ?? '—' }}</dd>
        <dt>红字发票号码</dt>
        <dd>{{ detail.redInvoiceNo ?? '—' }}</dd>
      </dl>
      <el-alert v-if="detail.error" :title="detail.error" type="warning" :closable="false" />
      <el-table :data="detail.lines" row-key="id" max-height="300">
        <el-table-column prop="goodsName" label="商品" min-width="145" />
        <el-table-column prop="taxCategoryCode" label="税收分类编码" width="150" />
        <el-table-column prop="quantity" label="数量" align="right" width="105" />
        <el-table-column prop="unitName" label="单位" width="80" />
        <el-table-column prop="untaxedAmount" label="未税金额" align="right" width="105" />
        <el-table-column prop="taxAmount" label="税额" align="right" width="90" />
      </el-table>
      <div v-if="session.can('einv:download') && detail.files.length" class="invoice-actions">
        <el-button
          v-for="file in detail.files"
          :key="file.variant + file.fileFormat"
          :disabled="busy"
          @click="download(file)"
          >{{ file.variant === 'red' ? '红字' : '原票' }}
          {{ file.fileFormat.toUpperCase() }}</el-button
        >
      </div>
      <el-form v-if="showRedInput" label-position="top" @submit.prevent="execute('red-issue')">
        <el-form-item label="红字原因"
          ><el-input v-model="redReason" type="textarea" maxlength="200" show-word-limit
        /></el-form-item>
      </el-form>
    </template>
    <el-form
      v-else-if="dialog === 'match' && selectedInbound"
      label-position="top"
      @submit.prevent="saveMatch()"
    >
      <el-form-item :label="`发票 ${selectedInbound.invoiceNo} 的采购应付`">
        <el-select
          v-model="payableId"
          class="invoice-select"
          :loading="busy"
          placeholder="选择金额和税额一致的收货应付"
        >
          <el-option
            v-for="payable in payables"
            :key="payable.id"
            :value="payable.id"
            :label="`${payable.receiptNo} / ${payable.businessDate} / ${payable.amount}`"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <el-form
      v-else-if="dialog === 'tax' && selectedSku"
      label-position="top"
      @submit.prevent="saveTax"
    >
      <el-form-item :label="`${selectedSku.name}的税收分类编码`"
        ><el-input v-model="taxCode" maxlength="24" placeholder="6 至 24 位数字"
      /></el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="busy" @click="dialog = undefined">关闭</el-button>
      <el-button
        v-if="dialog === 'create'"
        type="primary"
        :loading="busy"
        :disabled="!draft.receivableIds.length || draft.receivableIds.length > 50"
        @click="saveDraft"
        >提交申请</el-button
      >
      <template v-if="dialog === 'detail' && detail">
        <el-button
          v-if="
            detail.status === 'draft' &&
            detail.requestedBy !== session.user?.id &&
            session.can('einv:submit')
          "
          type="primary"
          :loading="busy"
          @click="execute('submit')"
          >确认开具</el-button
        >
        <el-button
          v-if="detail.status === 'issued' && session.can('einv:red_issue') && !showRedInput"
          type="danger"
          plain
          :disabled="busy"
          @click="showRedInput = true"
          >申请红冲</el-button
        >
        <el-button
          v-if="showRedInput"
          type="danger"
          :loading="busy"
          :disabled="!redReason.trim()"
          @click="execute('red-issue')"
          >确认红冲申请</el-button
        >
      </template>
      <template v-if="dialog === 'match'">
        <el-button v-if="selectedInbound?.payableId" :disabled="busy" @click="saveMatch(true)"
          >解除关联</el-button
        >
        <el-button
          type="primary"
          :loading="busy"
          :disabled="!payableId || payableId === selectedInbound?.payableId"
          @click="saveMatch()"
          >确认关联</el-button
        >
      </template>
      <el-button
        v-if="dialog === 'tax'"
        type="primary"
        :loading="busy"
        :disabled="!!taxCode.trim() && !/^\d{6,24}$/.test(taxCode.trim())"
        @click="saveTax"
        >保存</el-button
      >
    </template>
  </el-dialog>
</template>

<style scoped>
.invoice-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-block: 12px;
}
.invoice-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.invoice-select {
  min-width: min(210px, 65vw);
}
.provider-form {
  max-width: 480px;
}
.invoice-summary {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr) max-content minmax(0, 1fr);
  gap: 10px 14px;
}
.invoice-summary dt {
  color: var(--el-text-color-secondary);
}
.invoice-summary dd {
  margin: 0;
  overflow-wrap: anywhere;
}
@media (max-width: 560px) {
  .invoice-summary {
    grid-template-columns: max-content minmax(0, 1fr);
  }
}
</style>
