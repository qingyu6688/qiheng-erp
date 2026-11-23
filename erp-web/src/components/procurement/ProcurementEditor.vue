<script setup lang="ts">
import DynamicFields from '../DynamicFields.vue'
import { customFields } from '../../api/platform-rules'
import type { CustomField, FieldValues } from '../../api/platform-rules'
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { allRecords, record } from '../../api/admin'
import { candidates, commandResult, pricePreview } from '../../api/sales'
import { procurementCommand, saveProcurement } from '../../api/procurement'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { procurementSpecs, adjustmentLabels } from '../../types/procurement'
import type { ProcurementDocument, ProcurementKind } from '../../types/procurement'
import type { DataRow } from '../../types/admin'
import type { SalesSkuUnit } from '../../types/sales'
import { today } from '../../types/sales'
import { useSessionStore } from '../../stores/session'
import DocumentEditorFrame from '../DocumentEditorFrame.vue'
import BusinessUserSelect from '../BusinessUserSelect.vue'
import StockSelectionFields from '../stock-operations/StockSelectionFields.vue'
import type { StockSelection } from '../../types/inventory-tracking'
import EditableTable from '../EditableTable.vue'
import FixedFooter from '../FixedFooter.vue'
import OrderLineImport from '../OrderLineImport.vue'
import EmptyOptionHint from '../EmptyOptionHint.vue'
import { ORDER_LINE_LIMIT } from '../../utils/order-line-paste'
import type { PastedOrderLine } from '../../utils/order-line-paste'

import PaymentTermsField from '../PaymentTermsField.vue'
import { readPaymentTerm } from '../../utils/payment-terms'
import type { PaymentTerm } from '../../types/payment-terms'

const props = defineProps<{
  kind: ProcurementKind
  original?: ProcurementDocument
  change?: boolean
  inline?: boolean
}>()
const emit = defineEmits<{ close: []; saved: [ProcurementDocument, boolean?]; dirty: [boolean] }>()
const paymentTerms = ref<PaymentTerm | null>(props.original?.termOverride ? readPaymentTerm({ type: props.original.termType, value: props.original.termValue }) : null)
let submitRequested = false
const session = useSessionStore(),
  spec = computed(() => procurementSpecs.find((s) => s.path === props.kind)!)
const fields = ref<CustomField[]>([]),
  ext = ref<FieldValues>({ ...props.original?.extJson })
const form = reactive({
  buyerUserId: (props.original?.buyerUserId === '0' ? '' : props.original?.buyerUserId) ?? '',
  supplierId:
    props.original?.supplierId ?? props.original?.supplier?.id ?? props.original?.partnerId ?? '',
  warehouseId: props.original?.warehouseId ?? '',
  businessDate: props.original?.businessDate ?? today(),
  deliveryDate: props.original?.deliveryDate ?? '',
  priceTaxInclusive: props.original?.priceTaxInclusive ?? true,
  orderDiscountAmount: props.original?.orderDiscountAmount ?? '0',
  reason: props.change ? '' : (props.original?.reason ?? ''),
  remark: props.original?.remark ?? '',
  paymentType: props.original?.paymentType ?? 'supplier',
  cashAccountId: props.original?.cashAccountId ?? '',
  relatedOrderId: props.original?.relatedOrderId ?? '',
  expenseItemId: props.original?.expenseItemId ?? '',
  amount: props.original?.amount ?? '',
  bankSerialNo: props.original?.bankSerialNo ?? '',
  fromAccountId: props.original?.fromAccountId ?? '',
  toAccountId: props.original?.toAccountId ?? '',
  feeAmount: props.original?.feeAmount ?? '0',
  entryType: props.original?.entryType ?? 'cash',
  dueDate: props.original?.dueDate ?? '',
  direction: props.original?.direction ?? 'D',
  adjustType: props.original?.adjustType ?? 'opening',
})
interface InputLine {
  id?: string
  skuId: string
  warehouseId: string
  unitId: string
  qty: string
  unitPrice: string
  lineDiscountAmount: string
  taxRate: string
  sourceId: string
  inputTotalCost: string
  needDate: string
  stockSelection?: StockSelection | null
}
const lines = ref<InputLine[]>(
  (props.original?.lines ?? []).map((l) => ({
    id: l.id,
    skuId: l.skuId ?? l.sku?.id ?? '',
    warehouseId: l.warehouseId ?? l.warehouse?.id ?? '',
    unitId: l.unitId ?? l.unit?.id ?? '',
    qty: l.qty ?? '1',
    unitPrice: l.unitPrice ?? '',
    lineDiscountAmount: l.lineDiscountAmount ?? String(l.lineDiscount ?? '0'),
    taxRate: l.taxRate ?? '0.13',
    sourceId: l.requisitionLineId ?? l.orderLineId ?? l.receiptLineId ?? '',
    inputTotalCost: l.inputTotalCost ?? '',
    needDate: l.needDate ?? '',
    stockSelection: l.stockSelection,
  })),
)
const fees = ref((props.original?.fees ?? []).map((f) => ({ ...f })))
const partners = ref<DataRow[]>([]),
  warehouses = ref<DataRow[]>([]),
  skus = ref<DataRow[]>([]),
  accounts = ref<DataRow[]>([]),
  expenses = ref<DataRow[]>([]),
  orders = ref<DataRow[]>([])
const sources = ref<Record<string, unknown>[]>([]),
  units = reactive<Record<string, SalesSkuUnit[]>>({})
const loading = ref(true),
  busy = ref(false),
  uncertain = ref(false),
  conflict = ref(false),
  error = ref(''),
  preview = ref<ProcurementDocument>()
const key = newIdempotencyKey()
const order = computed(() => props.kind === 'purchase-orders'),
  requisition = computed(() => props.kind === 'purchase-requisitions'),
  stock = computed(() => props.kind === 'stock-adjustments')
const products = computed(() => order.value || requisition.value || stock.value),
  sourceLines = computed(() => ['goods-receipts', 'purchase-returns'].includes(props.kind))
const inputCost = computed(() => stock.value && ['opening', 'gain'].includes(form.adjustType))
const trackingMode = computed(() => {
  if (props.kind === 'goods-receipts' || inputCost.value) return 'in' as const
  if (props.kind === 'purchase-returns') return 'return' as const
  if (form.adjustType === 'scrap') return 'scrap' as const
  return ['block', 'unblock'].includes(form.adjustType) ? ('freeze' as const) : ('out' as const)
})
const hasLines = computed(() => products.value || sourceLines.value),
  warehouseHead = computed(() => stock.value || sourceLines.value)
const hasPartner = computed(
  () =>
    order.value ||
    sourceLines.value ||
    (props.kind === 'payments' && form.paymentType === 'supplier') ||
    (props.kind === 'opening-entries' && form.entryType !== 'cash'),
)
let timer: ReturnType<typeof setTimeout> | undefined,
  sequence = 0,
  sourceSequence = 0,
  baseline = ''
let active = true
const calculating = ref(false)
const editable = ref<InstanceType<typeof EditableTable>>()
const importer = ref<InstanceType<typeof OrderLineImport>>()
const populatedLineCount = computed(
  () => lines.value.filter((line) => line.skuId || line.id).length,
)
// 新单默认带一行空明细：选好往来单位或点过保存之前不标红，避免一打开就报错；
// 保存按钮在资料不全时是禁用的，所以选了往来单位就开始提示还缺什么
const saveAttempted = ref(false)
const linesTouched = computed(() => saveAttempted.value || Boolean(form.supplierId))
const NO_LINE_ERRORS = { skuId: '', unitId: '', qty: '', unitPrice: '' }
function untouchedLine(line: { skuId?: string; unitId?: string; qty: string; unitPrice: string }): boolean {
  return !line.skuId && !line.unitId && (line.qty === '1' || line.qty === '') && !line.unitPrice
}
const lineErrors = computed(() =>
  order.value
    ? lines.value.map((line) => untouchedLine(line) && !linesTouched.value ? NO_LINE_ERRORS : {
        skuId: !line.skuId ? '请选择商品' : '',
        unitId: !line.unitId ? '请选择单位' : '',
        qty:
          !/^\d{1,18}(?:\.\d{1,6})?$/.test(line.qty) || !/[1-9]/.test(line.qty)
            ? '数量须大于0且最多6位小数'
            : '',
        unitPrice:
          line.unitPrice && !/^\d{1,16}(?:\.\d{1,8})?$/.test(line.unitPrice)
            ? '单价须为非负数且最多16位整数、8位小数'
            : '',
      })
    : [],
)
const errorCount = computed(() =>
  lineErrors.value.reduce((count, row) => count + Object.values(row).filter(Boolean).length, 0),
)
function locateError(): void {
  const index = lineErrors.value.findIndex((row) => Object.values(row).some(Boolean))
  const row = lineErrors.value[index]
  const sourceColumn = session.can('purchase_requisition:view') ? 1 : 0
  if (row)
    editable.value?.focus(index, sourceColumn + (row.skuId ? 0 : row.unitId ? 2 : row.qty ? 3 : 4))
}
function importLines(items: PastedOrderLine[]): void {
  const current = lines.value.filter((line) => line.skuId || line.id)
  if (current.length + items.length > ORDER_LINE_LIMIT) {
    error.value = '商品明细最多500行'
    return
  }
  for (const item of items) units[item.skuId] = item.units
  lines.value = [
    ...current,
    ...items.map((item) => ({
      skuId: item.skuId,
      unitId: item.unitId,
      qty: item.qty,
      unitPrice: item.unitPrice,
      taxRate: item.taxRate,
      warehouseId: '',
      lineDiscountAmount: '0',
      sourceId: '',
      inputTotalCost: '',
      needDate: '',
    })),
  ]
}
function addLine(): void {
  if (lines.value.length >= ORDER_LINE_LIMIT) {
    error.value = '商品明细最多500行'
    return
  }
  lines.value.push({
    skuId: '',
    warehouseId: '',
    unitId: '',
    qty: '1',
    unitPrice: '',
    lineDiscountAmount: '0',
    taxRate: '0.13',
    sourceId: '',
    inputTotalCost: '',
    needDate: '',
  })
}
async function chooseSku(line: InputLine, preferredUnit?: string): Promise<void> {
  const selected = line.skuId
  if (!preferredUnit) {
    line.sourceId = ''
    line.stockSelection = null
  }
  line.unitId = ''
  line.unitPrice = ''
  if (!selected) return
  try {
    const sku = await record('skus/' + selected)
    if (!active || line.skuId !== selected || !lines.value.includes(line)) return
    units[line.skuId] = (sku.units as SalesSkuUnit[]) ?? []
    line.unitId =
      preferredUnit && units[line.skuId].some((u) => u.unitId === preferredUnit)
        ? preferredUnit
        : (units[line.skuId].find((u) => u.isBase)?.unitId ?? '')
    line.unitPrice = ''
    line.taxRate = String(sku.defaultTaxRate ?? '0.13')
  } catch (cause: unknown) {
    if (line.skuId === selected && !isCancelled(cause)) error.value = messageOf(cause)
  }
}
function sourceLabel(row: Record<string, unknown>): string {
  return (
    String(row.orderNo ?? row.receiptNo ?? row.requisitionNo ?? row.documentNo ?? row.id) +
    ' · ' +
    String(row.skuName ?? '商品') +
    ' · 可用 ' +
    String(row.receivableQty ?? row.returnableQty ?? row.orderableQty ?? '') +
    '（基础单位） · 按' +
    String(row.unitName ?? '原单位') +
    '录入'
  )
}
function chooseSource(line: InputLine): void {
  const source = sources.value.find((s) => String(s.id) === line.sourceId)
  line.stockSelection = null
  if (!source) {
    if (sourceLines.value) line.skuId = ''
    return
  }
  line.unitId = String(source?.unitId ?? '')
  if (sourceLines.value) line.skuId = String(source.skuId ?? '')
  if (products.value) {
    line.skuId = String(source?.skuId ?? '')
    void chooseSku(line, line.unitId)
  }
}
async function loadSources(): Promise<void> {
  const current = ++sourceSequence
  const path = order.value
    ? 'purchase-requisition-lines'
    : props.kind === 'goods-receipts'
      ? 'purchase-order-lines'
      : 'goods-receipt-lines'
  if (!order.value && !form.supplierId) {
    sources.value = []
    return
  }
  try {
    const result = await candidates(path, {
      supplierId: form.supplierId || undefined,
      warehouseId: form.warehouseId || undefined,
      receivable: props.kind === 'goods-receipts' || undefined,
      returnable: props.kind === 'purchase-returns' || undefined,
      orderable: order.value || undefined,
    })
    if (current === sourceSequence) sources.value = result.items
  } catch (cause: unknown) {
    if (current === sourceSequence && !isCancelled(cause)) error.value = messageOf(cause)
  }
}
watch(
  () => [form.supplierId, form.warehouseId],
  () => {
    form.relatedOrderId = ''
    if (sourceLines.value) {
      lines.value = []
      addLine()
      sources.value = []
      void loadSources()
    }
  },
)
watch(
  () => form.entryType,
  () => {
    form.direction = form.entryType === 'payable' ? 'C' : 'D'
    form.supplierId = ''
    form.cashAccountId = ''
  },
)
function body(): Record<string, unknown> {
  const common = {
    ...(['purchase-orders', 'goods-receipts', 'purchase-returns', 'payments'].includes(props.kind)
      ? { buyerUserId: form.buyerUserId || null }
      : {}),
    businessDate: form.businessDate,
    remark: form.remark,
    version: props.original?.version,
  }
  if (order.value)
    return {
      ...common,
      extJson: fields.value.length ? ext.value : undefined,
      supplierId: form.supplierId,
      paymentTerms: props.change ? undefined : paymentTerms.value,
      inheritPaymentTerms: props.change ? undefined : paymentTerms.value === null,
      deliveryDate: form.deliveryDate || null,
      priceTaxInclusive: form.priceTaxInclusive,
      orderDiscountAmount: form.orderDiscountAmount,
      reason: form.reason,
      lines: lines.value.map((l) => ({
        id: l.id,
        requisitionLineId: l.sourceId || null,
        skuId: l.skuId,
        warehouseId: l.warehouseId || null,
        unitId: l.unitId,
        qty: l.qty,
        unitPrice: l.unitPrice || undefined,
        lineDiscountAmount: l.lineDiscountAmount,
        taxRate: session.can('document:change_tax_rate') ? l.taxRate : undefined,
      })),
      fees: fees.value.map((f) => ({
        id: f.id,
        feeType: f.feeType,
        bearer: f.bearer,
        amount: f.amount,
        taxRate: session.can('document:change_tax_rate') ? f.taxRate : undefined,
      })),
    }
  if (requisition.value)
    return {
      ...common,
      lines: lines.value.map((l) => ({
        id: l.id,
        skuId: l.skuId,
        unitId: l.unitId,
        qty: l.qty,
        needDate: l.needDate || null,
      })),
    }
  if (stock.value)
    return {
      ...common,
      adjustType: form.adjustType,
      warehouseId: form.warehouseId,
      reason: form.reason,
      lines: lines.value.map((l) => ({
        skuId: l.skuId,
        unitId: l.unitId,
        qty: l.qty,
        inputTotalCost: inputCost.value ? l.inputTotalCost : undefined,
        stockSelection: l.stockSelection ?? undefined,
      })),
    }
  if (sourceLines.value)
    return {
      ...common,
      supplierId: form.supplierId,
      warehouseId: form.warehouseId,
      reason: form.reason,
      lines: lines.value.map((l) => ({
        [props.kind === 'goods-receipts' ? 'orderLineId' : 'receiptLineId']: l.sourceId,
        unitId: l.unitId,
        qty: l.qty,
        stockSelection: l.stockSelection ?? undefined,
      })),
    }
  if (props.kind === 'payments')
    return {
      ...common,
      paymentType: form.paymentType,
      partnerId: form.paymentType === 'supplier' ? form.supplierId : null,
      expenseItemId: form.paymentType === 'other_expense' ? form.expenseItemId : null,
      cashAccountId: form.cashAccountId,
      amount: form.amount,
      relatedOrderId: form.paymentType === 'supplier' ? form.relatedOrderId || null : null,
      bankSerialNo: form.bankSerialNo || null,
    }
  if (props.kind === 'account-transfers')
    return {
      ...common,
      fromAccountId: form.fromAccountId,
      toAccountId: form.toAccountId,
      amount: form.amount,
      feeAmount: form.feeAmount,
    }
  return {
    ...common,
    entryType: form.entryType,
    dueDate: form.entryType === 'cash' ? null : form.dueDate || null,
    direction: form.direction,
    partnerId: form.entryType === 'cash' ? null : form.supplierId,
    cashAccountId: form.entryType === 'cash' ? form.cashAccountId : null,
    amount: form.amount,
  }
}
async function calculate(): Promise<void> {
  clearTimeout(timer)
  if (!active) return
  const current = ++sequence
  if (
    !order.value ||
    !form.supplierId ||
    !lines.value.length ||
    lines.value.some((l) => !l.skuId || !l.unitId || !l.qty)
  ) {
    preview.value = undefined
    calculating.value = false
    return
  }
  calculating.value = true
  try {
    const result = await pricePreview({ ...body(), documentType: 'purchase_order' })
    if (current === sequence) {
      preview.value = result
      error.value = ''
    }
  } catch (cause: unknown) {
    if (current === sequence && !isCancelled(cause)) {
      error.value = messageOf(cause)
      preview.value = undefined
    }
  } finally {
    if (current === sequence) calculating.value = false
  }
}
watch(
  [form, lines, fees, ext],
  () => {
    if (!loading.value) emit('dirty', JSON.stringify(body()) !== baseline)
    clearTimeout(timer)
    sequence++
    if (order.value) {
      preview.value = undefined
      calculating.value = Boolean(
        form.supplierId &&
        lines.value.length &&
        lines.value.every((line) => line.skuId && line.unitId && line.qty),
      )
    }
    if (order.value)
      timer = setTimeout(() => {
        void calculate()
      }, 300)
  },
  { deep: true },
)
onUnmounted(() => {
  active = false
  clearTimeout(timer)
  sequence++
  sourceSequence++
})
onMounted(async () => {
  try {
    if (order.value && session.companyId) {
      try {
        fields.value = await customFields('purchase_order')
      } catch (cause: unknown) {
        if (!isCancelled(cause)) error.value = messageOf(cause)
      }
    }
    if (hasPartner.value || order.value || props.kind === 'opening-entries')
      partners.value = await allRecords('partners')
    if (products.value) {
      skus.value = await allRecords('skus')
      if (order.value || stock.value) warehouses.value = await allRecords('warehouses', true)
    }
    if (sourceLines.value) warehouses.value = await allRecords('warehouses', true)
    if (['payments', 'account-transfers', 'opening-entries'].includes(props.kind))
      accounts.value = await allRecords('cash-accounts', true)
    if (props.kind === 'payments') {
      if (!props.original && !form.cashAccountId)
        form.cashAccountId =
          accounts.value.find(
            (account) => account.status === 'active' && account.defaultForPayment === true,
          )?.id ?? ''
      if (session.can('purchase_order:view'))
        orders.value = await allRecords('purchase-orders', true)
      if (session.can('income_expense_item:view'))
        expenses.value = await allRecords('income-expense-items')
    }
    for (const line of lines.value)
      if (line.skuId) {
        const sku = await record('skus/' + line.skuId)
        units[line.skuId] = (sku.units as SalesSkuUnit[]) ?? []
      }
    if (hasLines.value && !lines.value.length) addLine()
    if (sourceLines.value || (order.value && session.can('purchase_requisition:view')))
      await loadSources()
    await calculate()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    baseline = JSON.stringify(body())
    loading.value = false
  }
})
// 在新标签页补完往来单位或商品后，回到这里刷新下拉，不影响已填内容
async function reloadChoices(): Promise<void> {
  try {
    if (hasPartner.value || order.value || props.kind === 'opening-entries')
      partners.value = await allRecords('partners')
    if (products.value) skus.value = await allRecords('skus')
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  }
}
async function save(submitAfterSave = false): Promise<void> {
  if (busy.value) return
  saveAttempted.value = true
  if (errorCount.value) {
    locateError()
    return
  }
  submitRequested = submitAfterSave
  busy.value = true
  error.value = ''
  try {
    if (order.value) {
      await calculate()
      if (!preview.value) return
    }
    const result =
      props.change && props.original
        ? await procurementCommand(props.kind, props.original.id, 'changes', body(), key)
        : await saveProcurement(
            props.kind,
            body(),
            props.original?.id,
            props.original ? undefined : key,
          )
    emit('saved', result, submitRequested)
  } catch (cause: unknown) {
    if (!isCancelled(cause)) {
      error.value = messageOf(cause)
      if (cause instanceof ApiError) {
        const pending = cause.status === 0 || cause.code === 'REQUEST_IN_PROGRESS'
        uncertain.value = pending && (!props.original || props.change === true)
        conflict.value =
          cause.code === 'VERSION_CONFLICT' || (pending && Boolean(props.original) && !props.change)
      }
    }
  } finally {
    busy.value = false
  }
}
async function verify(): Promise<void> {
  try {
    const result = await commandResult(
      spec.value.permission + '.' + (props.change ? 'change' : 'create'),
      key,
    )
    if (result.status === 'succeeded') emit('saved', result.response, submitRequested)
  } catch (cause: unknown) {
    error.value = messageOf(cause)
    if (cause instanceof ApiError && cause.status === 404) uncertain.value = false
  }
}
</script>
<template>
  <DocumentEditorFrame
    :inline="inline"
    :title="(change ? '变更' : original ? '编辑' : '新建') + spec.singular"
    :busy="busy"
    @close="emit('close')"
  >
    <fieldset class="form-fieldset" :disabled="busy || loading || uncertain || conflict">
      <el-form label-position="top" class="sales-header-form">
        <el-form-item v-if="stock" label="调整类型"
          ><el-select v-model="form.adjustType" aria-label="调整类型" :disabled="Boolean(original)"
            ><el-option
              v-for="(label, type) in adjustmentLabels"
              :key="type"
              :value="type"
              :label="label" /></el-select
        ></el-form-item>
        <el-form-item v-if="kind === 'payments'" label="付款类型"
          ><el-select v-model="form.paymentType" aria-label="付款类型"
            ><el-option value="supplier" label="供应商付款" /><el-option
              value="other_expense"
              label="其他支出" /></el-select
        ></el-form-item>
        <el-form-item v-if="kind === 'opening-entries'" label="期初类型"
          ><el-select v-model="form.entryType" aria-label="期初类型"
            ><el-option value="cash" label="资金期初" /><el-option
              value="receivable"
              label="客户往来期初" /><el-option value="payable" label="供应商往来期初" /></el-select
        ></el-form-item>
        <el-form-item
          v-if="hasPartner"
          :label="kind === 'opening-entries' ? '往来单位' : '供应商'"
          for="purchase-partner"
          ><el-select id="purchase-partner" v-model="form.supplierId" filterable :disabled="change"
            ><el-option
              v-for="p in partners.filter((p) => p.status === 'active')"
              :key="p.id"
              :value="p.id"
              :label="String(p.name)" /><template #empty
              ><EmptyOptionHint
                :text="kind === 'opening-entries' ? '找不到往来单位？' : '找不到供应商？往来单位需要勾选“供应商”角色。'"
                to="/partners"
                label="去添加往来单位（新标签页）"
                @reload="reloadChoices" /></template></el-select
        ></el-form-item>
        <el-form-item v-if="warehouseHead" label="仓库" for="purchase-warehouse"
          ><el-select id="purchase-warehouse" v-model="form.warehouseId"
            ><el-option
              v-for="w in warehouses.filter((w) => w.status === 'active')"
              :key="w.id"
              :value="w.id"
              :label="String(w.name)" /></el-select
        ></el-form-item>
        <el-form-item
          v-if="
            ['purchase-orders', 'goods-receipts', 'purchase-returns', 'payments'].includes(kind)
          "
          label="采购员"
          ><BusinessUserSelect
            v-model="form.buyerUserId"
            label="采购员"
            :name="original?.buyerUserName"
            :disabled="Boolean(change)"
        /></el-form-item>
        <el-form-item label="业务日期" for="purchase-date"
          ><el-input id="purchase-date" v-model="form.businessDate" type="date"
        /></el-form-item>
        <el-form-item v-if="order" label="交货日期" for="purchase-delivery"
          ><el-input id="purchase-delivery" v-model="form.deliveryDate" type="date"
        /></el-form-item>
        <el-form-item v-if="order" label="价格口径"
          ><el-switch
            v-model="form.priceTaxInclusive"
            active-text="含税"
            inactive-text="未税"
            :disabled="change"
        /></el-form-item>
        <el-form-item v-if="order" label="整单优惠" for="purchase-discount"
          ><el-input id="purchase-discount" v-model="form.orderDiscountAmount"
        /></el-form-item>
        <el-form-item
          v-if="kind === 'payments' || (kind === 'opening-entries' && form.entryType === 'cash')"
          label="结算账户"
          for="purchase-account"
          ><el-select id="purchase-account" v-model="form.cashAccountId"
            ><el-option
              v-for="a in accounts.filter((a) => a.status === 'active')"
              :key="a.id"
              :value="a.id"
              :label="String(a.name)" /></el-select
        ></el-form-item>
        <el-form-item
          v-if="
            kind === 'payments' &&
            form.paymentType === 'supplier' &&
            session.can('purchase_order:view')
          "
          label="关联采购订单"
          for="purchase-related"
          ><el-select id="purchase-related" v-model="form.relatedOrderId" clearable
            ><el-option
              v-for="o in orders.filter(
                (o) =>
                  o.supplierId === form.supplierId ||
                  (o.supplier as { id?: string } | undefined)?.id === form.supplierId,
              )"
              :key="o.id"
              :value="o.id"
              :label="String(o.documentNo)" /></el-select
        ></el-form-item>
        <el-form-item
          v-if="kind === 'payments' && form.paymentType === 'other_expense'"
          label="支出项目"
          ><el-select v-model="form.expenseItemId" aria-label="支出项目"
            ><el-option
              v-for="e in expenses.filter((e) => e.itemType === 'expense' && e.status === 'active')"
              :key="e.id"
              :value="e.id"
              :label="String(e.name)" /></el-select
        ></el-form-item>
        <template v-if="kind === 'account-transfers'"
          ><el-form-item label="转出账户" for="transfer-from"
            ><el-select id="transfer-from" v-model="form.fromAccountId"
              ><el-option
                v-for="a in accounts.filter((a) => a.status === 'active')"
                :key="a.id"
                :value="a.id"
                :label="String(a.name)" /></el-select></el-form-item
          ><el-form-item label="转入账户" for="transfer-to"
            ><el-select id="transfer-to" v-model="form.toAccountId"
              ><el-option
                v-for="a in accounts.filter(
                  (a) => a.status === 'active' && a.id !== form.fromAccountId,
                )"
                :key="a.id"
                :value="a.id"
                :label="String(a.name)" /></el-select></el-form-item
          ><el-form-item label="手续费" for="transfer-fee"
            ><el-input id="transfer-fee" v-model="form.feeAmount" /></el-form-item
        ></template>
        <el-form-item v-if="!hasLines" label="金额" for="purchase-amount"
          ><el-input id="purchase-amount" v-model="form.amount"
        /></el-form-item>
        <el-form-item
          v-if="kind === 'opening-entries' && form.entryType !== 'cash'"
          label="借贷方向"
          ><el-select v-model="form.direction" aria-label="借贷方向"
            ><el-option value="D" label="借方 D" /><el-option value="C" label="贷方 C" /></el-select
        ></el-form-item>
        <el-form-item v-if="kind === 'payments'" label="银行流水号" for="purchase-bank"
          ><el-input id="purchase-bank" v-model="form.bankSerialNo" maxlength="64"
        /></el-form-item>
        <el-form-item
          v-if="stock || kind === 'purchase-returns' || change"
          label="原因"
          for="purchase-reason"
          ><el-input id="purchase-reason" v-model="form.reason" maxlength="200"
        /></el-form-item>
        <PaymentTermsField v-if="order && !change" v-model="paymentTerms" label="订单账期" allow-inherit />
        <el-form-item v-if="kind === 'opening-entries' && form.entryType !== 'cash'" label="到期日" for="opening-due-date">
          <el-input id="opening-due-date" v-model="form.dueDate" type="date" />
          <span>不填时按往来单位或公司账期计算。</span>
        </el-form-item>
        <el-form-item label="备注" for="purchase-remark"
          ><el-input id="purchase-remark" v-model="form.remark" maxlength="200"
        /></el-form-item>
        <template v-if="fields.length"
          ><h3>其他信息</h3>
          <DynamicFields v-model="ext" :fields="fields" prefix="purchase-extra-"
        /></template>
      </el-form>
      <EditableTable
        v-if="hasLines"
        ref="editable"
        :count="lines.length"
        :disabled="busy || loading || uncertain || conflict"
        @add="addLine"
        @remove="lines.splice($event, 1)"
        @paste="order && importer?.openPaste($event)"
      >
        <template #tools>
          <OrderLineImport
            v-if="order"
            ref="importer"
            kind="purchase-orders"
            :count="populatedLineCount"
            :partner-id="form.supplierId"
            :price-tax-inclusive="form.priceTaxInclusive"
            :disabled="busy || loading || uncertain || conflict"
            :skus="skus"
            @import="importLines"
          />
          <el-button :disabled="lines.length >= ORDER_LINE_LIMIT" @click="addLine"
            >添加明细</el-button
          >
        </template>
        <template #head>
          <tr>
            <th scope="col">#</th>
            <th v-if="sourceLines || (order && session.can('purchase_requisition:view'))">
              来源{{ order ? '（可选）' : '' }}
            </th>
            <th v-if="products">商品</th>
            <th v-if="order">收货仓库</th>
            <th v-if="products">单位</th>
            <th>数量</th>
            <template v-if="order"
              ><th>单价</th>
              <th>行优惠</th>
              <th v-if="session.can('document:change_tax_rate')">税率</th></template
            >
            <th v-if="inputCost">总成本</th>
            <th v-if="requisition">需求日期</th>
            <th v-if="order" scope="col">金额</th>
            <th scope="col">操作</th>
          </tr>
        </template>
        <template v-for="(line, index) in lines" :key="index">
          <tr data-edit-row>
            <td>{{ index + 1 }}</td>
            <td v-if="sourceLines || (order && session.can('purchase_requisition:view'))">
              <el-select
                v-model="line.sourceId"
                :aria-label="'采购来源' + (index + 1)"
                clearable
                filterable
                @change="chooseSource(line)"
                ><el-option
                  v-for="s in sources"
                  :key="String(s.id)"
                  :value="String(s.id)"
                  :label="sourceLabel(s)"
              /></el-select>
            </td>
            <td
              v-if="products"
              :data-error="lineErrors[index]?.skuId || undefined"
              :title="lineErrors[index]?.skuId"
            >
              <el-select
                v-model="line.skuId"
                :aria-label="'采购商品' + (index + 1)"
                filterable
                @change="chooseSku(line)"
                ><el-option
                  v-for="s in skus.filter((s) => s.status === 'active')"
                  :key="s.id"
                  :value="s.id"
                  :label="String(s.code) + ' ' + String(s.name)"
                /><template #empty
                  ><EmptyOptionHint
                    text="找不到商品？"
                    to="/skus"
                    label="去添加商品（新标签页）"
                    @reload="reloadChoices" /></template
              ></el-select>
            </td>
            <td v-if="order">
              <el-select v-model="line.warehouseId" :aria-label="'采购仓库' + (index + 1)" clearable
                ><el-option
                  v-for="w in warehouses.filter((w) => w.status === 'active')"
                  :key="w.id"
                  :value="w.id"
                  :label="String(w.name)"
              /></el-select>
            </td>
            <td
              v-if="products"
              :data-error="lineErrors[index]?.unitId || undefined"
              :title="lineErrors[index]?.unitId"
            >
              <el-select
                v-model="line.unitId"
                :aria-label="'采购单位' + (index + 1)"
                @change="line.unitPrice = ''"
                ><el-option
                  v-for="u in units[line.skuId] ?? []"
                  :key="u.unitId"
                  :value="u.unitId"
                  :label="u.unitName"
              /></el-select>
            </td>
            <td :data-error="lineErrors[index]?.qty || undefined" :title="lineErrors[index]?.qty">
              <el-input v-model="line.qty" :aria-label="'采购数量' + (index + 1)" />
            </td>
            <template v-if="order"
              ><td
                :data-error="lineErrors[index]?.unitPrice || undefined"
                :title="lineErrors[index]?.unitPrice"
              >
                <el-input
                  v-model="line.unitPrice"
                  :aria-label="'采购单价' + (index + 1)"
                  placeholder="参考价"
                />
              </td>
              <td>
                <el-input
                  v-model="line.lineDiscountAmount"
                  :aria-label="'采购行优惠' + (index + 1)"
                />
              </td>
              <td v-if="session.can('document:change_tax_rate')">
                <el-input v-model="line.taxRate" :aria-label="'采购税率' + (index + 1)" /></td
            ></template>
            <td v-if="inputCost">
              <el-input
                v-model="line.inputTotalCost"
                :aria-label="
                  (form.adjustType === 'opening' ? '期初总成本' : '调整总成本') + (index + 1)
                "
              />
            </td>
            <td v-if="requisition">
              <el-input
                v-model="line.needDate"
                type="date"
                :aria-label="'需求日期' + (index + 1)"
              />
            </td>
            <td v-if="order" class="line-amount">
              <span v-if="calculating" role="status">…</span
              ><span v-else>{{ preview?.lines?.[index]?.receivableAmount ?? '—' }}</span>
            </td>
            <td>
              <el-button
                text
                :aria-label="'移除第' + (index + 1) + '行'"
                @click="lines.splice(index, 1)"
                >移除</el-button
              >
            </td>
          </tr>
          <tr v-if="(stock || sourceLines) && form.warehouseId && line.skuId">
            <td :colspan="stock ? (inputCost ? 6 : 5) : 4">
              <StockSelectionFields
                v-model="line.stockSelection"
                :warehouse-id="form.warehouseId"
                :sku-id="line.skuId"
                :mode="trackingMode"
              />
            </td>
          </tr>
        </template>
        <template v-if="order" #summary>
          <tr>
            <td
              :colspan="
                7 +
                (session.can('purchase_requisition:view') ? 1 : 0) +
                (session.can('document:change_tax_rate') ? 1 : 0)
              "
            >
              合计
            </td>
            <td class="line-amount">{{ calculating ? '…' : (preview?.goodsAmount ?? '—') }}</td>
            <td />
          </tr>
        </template>
      </EditableTable>
      <section v-if="order" class="sale-fees">
        <div v-for="(fee, index) in fees" :key="index" class="relation-row">
          <el-select v-model="fee.feeType" aria-label="采购费用类型"
            ><el-option value="freight" label="运费" /><el-option value="loading" label="装卸费" /><el-option
              value="other"
              label="其他费用" /></el-select
          ><el-select v-model="fee.bearer" aria-label="采购费用承担方"
            ><el-option value="buyer" label="我方承担" /><el-option
              value="seller"
              label="供应商承担" /></el-select
          ><el-input v-model="fee.amount" aria-label="采购费用金额" /><el-button
            text
            @click="fees.splice(index, 1)"
            >移除</el-button
          >
        </div>
        <el-button
          text
          @click="fees.push({ feeType: 'freight', bearer: 'buyer', amount: '0', taxRate: '0.13' })"
          >添加费用</el-button
        >
      </section>
    </fieldset>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <p v-if="conflict">保存版本或结果需要核对，请关闭并刷新后重试。</p>
    <template #footer>
      <FixedFooter :calculating="calculating" :errors="errorCount" @locate-error="locateError">
        <template v-if="preview" #summary>
          <span>商品 {{ preview.goodsAmount }}</span
          ><span>税额 {{ preview.taxAmount }}</span
          ><strong>合计 ¥{{ preview.totalAmount }}</strong>
        </template>
        <el-button :disabled="busy" @click="emit('close')">取消</el-button
        ><el-button v-if="uncertain" type="primary" @click="verify">查询结果</el-button
        ><template v-else>
          <el-button :disabled="busy || loading || conflict" @click="save()">保存{{ spec.singular }}</el-button>
          <el-button
            v-if="inline && kind === 'purchase-orders' && !change && session.can('purchase_order:submit')"
            type="primary"
            :disabled="busy || loading || conflict"
            @click="save(true)"
          >保存并提交</el-button>
        </template></FixedFooter
      >
    </template>
  </DocumentEditorFrame>
</template>
