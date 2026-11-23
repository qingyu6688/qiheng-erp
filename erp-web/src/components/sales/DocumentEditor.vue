<script setup lang="ts">
import { computed, onMounted, onScopeDispose, reactive, ref, watch } from 'vue'
import { allRecords } from '../../api/admin'
import { candidates, commandResult, saveDocument } from '../../api/sales'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { salesSpecs, today } from '../../types/sales'
import type { SalesDocument, SalesKind } from '../../types/sales'
import type { DataRow } from '../../types/admin'
import { useSessionStore } from '../../stores/session'
import BusinessUserSelect from '../BusinessUserSelect.vue'
import StockSelectionFields from '../stock-operations/StockSelectionFields.vue'
import type { StockSelection } from '../../types/inventory-tracking'
import FinancialAmount from '../FinancialAmount.vue'

const props = defineProps<{ kind: Exclude<SalesKind, 'sales-orders'>; original?: SalesDocument }>()
const emit = defineEmits<{ close: []; saved: [SalesDocument] }>()
const session = useSessionStore()
const spec = computed(() => salesSpecs.find((s) => s.path === props.kind)!)
const form = reactive({
  salesUserId: (props.original?.salesUserId === '0' ? '' : props.original?.salesUserId) ?? '',
  customerId:
    props.original?.customerId ?? props.original?.customer?.id ?? props.original?.partnerId ?? '',
  warehouseId: props.original?.warehouseId ?? '',
  businessDate: props.original?.businessDate ?? props.original?.invoiceDate ?? today(),
  deliveryMethod: props.original?.deliveryMethod ?? 'delivery',
  receiptType: props.original?.receiptType ?? 'customer',
  cashAccountId: props.original?.cashAccountId ?? '',
  amount: props.original?.amount ?? '',
  relatedOrderId: props.original?.relatedOrderId ?? '',
  incomeItemId: props.original?.incomeItemId ?? '',
  bankSerialNo: props.original?.bankSerialNo ?? '',
  originalReceiptId: props.original?.originalReceiptId ?? '',
  reason: props.original?.reason ?? '',
  remark: props.original?.remark ?? '',
  invoiceNo: props.original?.invoiceNo ?? '',
  taxAmount: props.original?.taxAmount ?? '0',
})
interface InputLine {
  sourceId: string
  skuId: string
  unitId: string
  qty: string
  qualityResult: string
  amount: string
  stockSelection?: StockSelection | null
}
const lines = ref<InputLine[]>(
  (props.original?.lines ?? []).map((l) => ({
    sourceId: String(l.orderLineId ?? l.shipmentLineId ?? l.receivableId ?? ''),
    skuId: l.skuId ?? '',
    unitId: l.unitId ?? '',
    qty: l.qty ?? '',
    qualityResult: l.qualityResult ?? 'pending',
    amount: String(l.amount ?? ''),
    stockSelection: l.stockSelection,
  })),
)
const partners = ref<DataRow[]>([]),
  warehouses = ref<DataRow[]>([]),
  accounts = ref<DataRow[]>([]),
  orders = ref<DataRow[]>([]),
  receipts = ref<DataRow[]>([]),
  income = ref<DataRow[]>([])
const sources = ref<Record<string, unknown>[]>([])
const sourceCursor = ref<string | null>(null)
const busy = ref(false),
  loading = ref(true),
  loadingSources = ref(false),
  error = ref(''),
  uncertain = ref(false),
  conflict = ref(false)
const key = newIdempotencyKey()
const hasLines = computed(() =>
  ['shipments', 'sales-returns', 'invoice-registrations'].includes(props.kind),
)
const hasWarehouse = computed(() => props.kind === 'shipments' || props.kind === 'sales-returns')
function sourceName(row: Record<string, unknown>): string {
  const source =
    String(row.orderNo ?? row.documentNo ?? row.sourceNo ?? row.id) +
    ' · ' +
    String(row.skuName ?? '应收')
  if (props.kind === 'invoice-registrations')
    return (
      source +
      ' · 原金额 ' +
      String(row.amount ?? '') +
      ' · 可开票 ' +
      String(row.invoiceAvailableAmount ?? '待刷新') +
      ' · 可开税额 ' +
      String(row.invoiceAvailableTaxAmount ?? '待刷新')
    )
  return (
    source +
    ' · 可用 ' +
    String(row.shippableQty ?? row.returnableQty ?? '') +
    '（基础单位） · 按' +
    String(row.unitName ?? '原单位') +
    '录入'
  )
}
function chooseSource(line: InputLine): void {
  const row = sources.value.find((r) => r.id === line.sourceId)
  line.unitId = String(row?.unitId ?? '')
  line.skuId = String(row?.skuId ?? '')
  line.stockSelection = null
  if (!line.qty) line.qty = '1'
}
let revision = 0
async function loadSources(more = false): Promise<void> {
  const current = ++revision
  if (!more) {
    sources.value = []
    sourceCursor.value = null
  }
  if (!form.customerId) {
    loadingSources.value = false
    return
  }
  loadingSources.value = true
  error.value = ''
  try {
    const path =
      props.kind === 'shipments'
        ? 'sales-order-lines'
        : props.kind === 'sales-returns'
          ? 'shipment-lines'
          : 'receivables'
    const result = await candidates(path, {
      customerId: form.customerId,
      warehouseId: form.warehouseId || undefined,
      shippable: props.kind === 'shipments',
      returnable: props.kind === 'sales-returns',
      ...(props.kind === 'invoice-registrations' ? { invoiceable: true } : {}),
      ...(more && sourceCursor.value ? { cursor: sourceCursor.value } : {}),
    })
    if (current === revision) {
      const items =
        props.kind === 'invoice-registrations'
          ? result.items.filter(
              (row) =>
                row.kind === 'charge' &&
                (row.invoiceAvailableAmount === undefined ||
                  /^(?:0*[1-9][0-9]*(?:\.[0-9]+)?|0+\.0*[1-9][0-9]*)$/.test(
                    String(row.invoiceAvailableAmount),
                  )),
            )
          : result.items
      sources.value = [
        ...new Map([...sources.value, ...items].map((row) => [String(row.id), row])).values(),
      ]
      sourceCursor.value = result.nextCursor
    }
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) {
      if (cause instanceof ApiError && [403, 404].includes(cause.status)) {
        sources.value = []
        sourceCursor.value = null
        lines.value = []
      }
      error.value = messageOf(cause)
    }
  } finally {
    if (current === revision) loadingSources.value = false
  }
}
onScopeDispose(() => {
  revision++
})
watch(
  () => [form.customerId, form.warehouseId],
  () => {
    form.relatedOrderId = ''
    form.originalReceiptId = ''
    if (hasLines.value) {
      lines.value = []
      addLine()
      void loadSources()
    }
  },
)
watch(
  () => form.receiptType,
  () => {
    form.relatedOrderId = ''
    form.incomeItemId = ''
  },
)
onMounted(async () => {
  try {
    partners.value = await allRecords('partners')
    if (hasWarehouse.value) warehouses.value = await allRecords('warehouses', true)
    if (['receipts', 'refund-requests'].includes(props.kind))
      accounts.value = await allRecords('cash-accounts', true)
    if (props.kind === 'receipts' && !props.original && !form.cashAccountId) {
      form.cashAccountId =
        accounts.value.find(
          (account) => account.status === 'active' && account.defaultForReceipt === true,
        )?.id ?? ''
    }
    if (props.kind === 'receipts') {
      if (session.can('sales_order:view')) orders.value = await allRecords('sales-orders', true)
      if (session.can('income_expense_item:view'))
        income.value = await allRecords('income-expense-items')
    }
    if (props.kind === 'refund-requests') receipts.value = await allRecords('receipts', true)
    if (hasLines.value) {
      await loadSources()
      if (!lines.value.length) addLine()
    }
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    loading.value = false
  }
})
function addLine(): void {
  lines.value.push({
    sourceId: '',
    skuId: '',
    unitId: '',
    qty: '1',
    qualityResult: 'pending',
    amount: '',
  })
}
function body(): Record<string, unknown> {
  const version = props.original?.version
  if (props.kind === 'shipments')
    return {
      version,
      salesUserId: form.salesUserId || null,
      customerId: form.customerId,
      warehouseId: form.warehouseId,
      businessDate: form.businessDate,
      deliveryMethod: form.deliveryMethod,
      remark: form.remark,
      lines: lines.value.map((l) => ({
        orderLineId: l.sourceId,
        unitId: l.unitId,
        qty: l.qty,
        stockSelection: l.stockSelection ?? undefined,
      })),
    }
  if (props.kind === 'sales-returns')
    return {
      version,
      salesUserId: form.salesUserId || null,
      customerId: form.customerId,
      warehouseId: form.warehouseId,
      businessDate: form.businessDate,
      reason: form.reason,
      lines: lines.value.map((l) => ({
        shipmentLineId: l.sourceId,
        unitId: l.unitId,
        qty: l.qty,
        qualityResult: l.qualityResult,
        stockSelection: l.stockSelection ?? undefined,
      })),
    }
  if (props.kind === 'receipts')
    return {
      version,
      salesUserId: form.salesUserId || null,
      receiptType: form.receiptType,
      partnerId: form.receiptType === 'other_income' ? null : form.customerId,
      incomeItemId: form.receiptType === 'other_income' ? form.incomeItemId : null,
      cashAccountId: form.cashAccountId,
      amount: form.amount,
      businessDate: form.businessDate,
      relatedOrderId: form.receiptType === 'customer' ? form.relatedOrderId || null : null,
      bankSerialNo: form.bankSerialNo || null,
      remark: form.remark,
    }
  if (props.kind === 'refund-requests')
    return {
      version,
      customerId: form.customerId,
      originalReceiptId: form.originalReceiptId,
      cashAccountId: form.cashAccountId,
      amount: form.amount,
      businessDate: form.businessDate,
      reason: form.reason,
    }
  return {
    invoiceType: 'sales',
    partnerId: form.customerId,
    invoiceNo: form.invoiceNo,
    invoiceDate: form.businessDate,
    amount: form.amount,
    taxAmount: form.taxAmount,
    remark: form.remark,
    lines: lines.value.map((l) => ({ receivableId: l.sourceId, amount: l.amount })),
  }
}
async function save(): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    emit(
      'saved',
      await saveDocument(props.kind, body(), props.original?.id, props.original ? undefined : key),
    )
  } catch (cause: unknown) {
    if (!isCancelled(cause)) {
      error.value = messageOf(cause)
      if (cause instanceof ApiError) {
        const pending = cause.status === 0 || cause.code === 'REQUEST_IN_PROGRESS'
        uncertain.value = pending && !props.original
        conflict.value = cause.code === 'VERSION_CONFLICT' || (pending && Boolean(props.original))
        if (pending && props.original) error.value = '保存结果尚未确认，请关闭并刷新单据后核对'
      }
    }
  } finally {
    busy.value = false
  }
}
async function verify(): Promise<void> {
  try {
    const result = await commandResult(spec.value.permission + '.create', key)
    if (result.status === 'succeeded') emit('saved', result.response)
  } catch (cause: unknown) {
    error.value = messageOf(cause)
    if (cause instanceof ApiError && cause.status === 404) uncertain.value = false
  }
}
</script>
<template>
  <el-dialog
    :model-value="true"
    :title="(original ? '编辑' : '新建') + spec.singular"
    class="sales-editor-dialog"
    top="5vh"
    width="min(900px,96vw)"
    :close-on-click-modal="false"
    :show-close="!busy"
    @close="emit('close')"
  >
    <fieldset class="form-fieldset" :disabled="busy || loading || uncertain || conflict">
      <el-form label-position="top" class="sales-header-form">
        <el-form-item v-if="kind === 'receipts'" label="收款类型"
          ><el-select v-model="form.receiptType" aria-label="收款类型"
            ><el-option label="客户收款" value="customer" /><el-option
              label="供应商退款"
              value="supplier_refund" /><el-option
              label="其他收入"
              value="other_income" /></el-select
        ></el-form-item>
        <el-form-item
          v-if="form.receiptType !== 'other_income' || kind !== 'receipts'"
          label="客户 / 往来单位"
          for="doc-customer"
          ><el-select id="doc-customer" v-model="form.customerId" filterable
            ><el-option
              v-for="p in partners.filter((p) => p.status === 'active')"
              :key="p.id"
              :value="p.id"
              :label="String(p.name)" /></el-select
        ></el-form-item>
        <el-form-item v-if="hasWarehouse" label="仓库" for="doc-warehouse"
          ><el-select id="doc-warehouse" v-model="form.warehouseId"
            ><el-option
              v-for="w in warehouses.filter((w) => w.status === 'active')"
              :key="w.id"
              :value="w.id"
              :label="String(w.name)" /></el-select
        ></el-form-item>
        <el-form-item
          v-if="['shipments', 'sales-returns', 'receipts'].includes(kind)"
          label="销售员"
          ><BusinessUserSelect
            v-model="form.salesUserId"
            label="销售员"
            :name="original?.salesUserName"
        /></el-form-item>
        <el-form-item label="业务日期" for="doc-date"
          ><el-input id="doc-date" v-model="form.businessDate" type="date"
        /></el-form-item>
        <el-form-item v-if="kind === 'shipments'" label="交付方式"
          ><el-select v-model="form.deliveryMethod" aria-label="交付方式"
            ><el-option label="送货" value="delivery" /><el-option
              label="客户自提"
              value="pickup" /></el-select
        ></el-form-item>
        <el-form-item
          v-if="kind === 'receipts' || kind === 'refund-requests'"
          label="结算账户"
          for="doc-account"
          ><el-select id="doc-account" v-model="form.cashAccountId"
            ><el-option
              v-for="a in accounts.filter((a) => a.status === 'active')"
              :key="a.id"
              :value="a.id"
              :label="String(a.name)" /></el-select
        ></el-form-item>
        <el-form-item
          v-if="
            kind === 'receipts' &&
            form.receiptType === 'customer' &&
            session.can('sales_order:view')
          "
          label="关联销售订单"
          for="doc-order"
          ><el-select id="doc-order" v-model="form.relatedOrderId" clearable filterable
            ><el-option
              v-for="o in orders.filter(
                (o) =>
                  o.customerId === form.customerId ||
                  (o.customer as { id?: string } | undefined)?.id === form.customerId,
              )"
              :key="o.id"
              :value="o.id"
              :label="String(o.documentNo)" /></el-select
        ></el-form-item>
        <el-form-item
          v-if="kind === 'receipts' && form.receiptType === 'other_income'"
          label="收入项目"
          ><el-select v-model="form.incomeItemId"
            ><el-option
              v-for="i in income.filter((i) => i.itemType === 'income' && i.status === 'active')"
              :key="i.id"
              :value="i.id"
              :label="String(i.name)" /></el-select
        ></el-form-item>
        <el-form-item v-if="kind === 'refund-requests'" label="原收款单" for="doc-receipt"
          ><el-select id="doc-receipt" v-model="form.originalReceiptId" filterable
            ><el-option
              v-for="r in receipts.filter(
                (r) =>
                  r.status === 'confirmed' &&
                  r.receiptType === 'customer' &&
                  r.partnerId === form.customerId,
              )"
              :key="r.id"
              :value="r.id"
              :label="String(r.documentNo)" /></el-select
        ></el-form-item>
        <el-form-item v-if="!hasWarehouse" label="金额" for="doc-amount"
          ><el-input id="doc-amount" v-model="form.amount"
        /></el-form-item>
        <el-form-item v-if="kind === 'receipts'" label="银行流水号" for="doc-bank"
          ><el-input id="doc-bank" v-model="form.bankSerialNo" maxlength="64"
        /></el-form-item>
        <el-form-item v-if="kind === 'invoice-registrations'" label="发票号" for="doc-invoice"
          ><el-input id="doc-invoice" v-model="form.invoiceNo" maxlength="64"
        /></el-form-item>
        <el-form-item v-if="kind === 'invoice-registrations'" label="发票税额" for="doc-tax"
          ><el-input id="doc-tax" v-model="form.taxAmount"
        /></el-form-item>
        <el-form-item
          v-if="kind === 'sales-returns' || kind === 'refund-requests'"
          label="原因"
          for="doc-reason"
          ><el-input id="doc-reason" v-model="form.reason" maxlength="200"
        /></el-form-item>
        <el-form-item label="备注" for="doc-remark"
          ><el-input id="doc-remark" v-model="form.remark" maxlength="200"
        /></el-form-item>
      </el-form>
      <section v-if="hasLines" class="sales-lines-scroll">
        <div v-for="(line, index) in lines" :key="index" class="relation-row">
          <el-select
            v-model="line.sourceId"
            :aria-label="'来源行' + (index + 1)"
            filterable
            @change="chooseSource(line)"
            ><el-option
              v-for="s in sources"
              :key="String(s.id)"
              :value="String(s.id)"
              :label="sourceName(s)"
              :style="
                kind === 'invoice-registrations' ? { height: 'auto', minHeight: '54px' } : undefined
              "
            >
              <div v-if="kind === 'invoice-registrations'" class="invoice-candidate">
                <span>{{ s.sourceNo ?? s.id }} · {{ s.skuName ?? '应收' }}</span>
                <span
                  >可开票
                    <FinancialAmount :value="String(s.invoiceAvailableAmount ?? '')" /> 元
                  <small> · 税额 {{ s.invoiceAvailableTaxAmount ?? '待刷新' }} 元</small></span
                >
              </div>
            </el-option>
          </el-select>
          <el-input
            v-if="kind === 'invoice-registrations'"
            v-model="line.amount"
            :aria-label="'开票金额' + (index + 1)"
            placeholder="开票金额"
          />
          <el-input
            v-else
            v-model="line.qty"
            :aria-label="'数量' + (index + 1)"
            placeholder="数量"
          />
          <el-select
            v-if="kind === 'sales-returns'"
            v-model="line.qualityResult"
            :aria-label="'质检' + (index + 1)"
            ><el-option value="pending" label="待检" /><el-option
              value="passed"
              label="合格" /><el-option value="failed" label="不合格"
          /></el-select>
          <el-button text @click="lines.splice(index, 1)">移除</el-button>
          <StockSelectionFields
            v-if="hasWarehouse && line.skuId"
            v-model="line.stockSelection"
            :warehouse-id="form.warehouseId"
            :sku-id="line.skuId"
            :mode="kind === 'shipments' ? 'out' : 'return'"
          />
        </div>
        <el-button @click="addLine">添加来源行</el-button>
        <el-button v-if="sourceCursor" :loading="loadingSources" @click="loadSources(true)"
          >更多来源</el-button
        >
        <p v-if="kind === 'invoice-registrations'" class="invoice-candidate-help">
          可开票金额已扣除退货、折让和已有开票；同一发货行共用净额度，保存时会再次核对。
        </p>
      </section>
    </fieldset>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <p v-if="conflict">单据已被更新，请关闭并刷新。</p>
    <template #footer
      ><el-button :disabled="busy" @click="emit('close')">取消</el-button
      ><el-button v-if="uncertain" type="primary" @click="verify">查询结果</el-button
      ><el-button
        v-else
        type="primary"
        :disabled="loading || loadingSources || conflict"
        :loading="busy"
        @click="save"
        >保存单据</el-button
      ></template
    >
  </el-dialog>
</template>

<style scoped>
.invoice-candidate {
  display: grid;
  gap: 2px;
  padding-block: 5px;
  line-height: 20px;
}
.invoice-candidate > span:first-child,
.invoice-candidate small,
.invoice-candidate-help {
  color: var(--erp-text-2);
  font-size: 12px;
  font-weight: 400;
}
.invoice-candidate-help {
  line-height: 1.6;
}
</style>
