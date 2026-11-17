<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { allRecords, record } from '../../api/admin'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { dictionaries } from '../../api/platform'
import type { DictionaryItem } from '../../api/platform'
import { saveStockMisc, stockLoanLines, stockLoans } from '../../api/stock-misc'
import type { LoanOutstanding, StockMiscDirection, StockMiscDocument } from '../../api/stock-misc'
import type { DataRow } from '../../types/admin'
import type { StockSelection } from '../../types/inventory-tracking'
import type { SalesSkuUnit } from '../../types/sales'
import { today } from '../../types/sales'
import { useSessionStore } from '../../stores/session'
import { formatQuantity } from '../../utils/format'
import StockSelectionFields from './StockSelectionFields.vue'

const props = defineProps<{ direction: StockMiscDirection; original?: StockMiscDocument }>()
const emit = defineEmits<{ close: []; saved: [StockMiscDocument] }>()
const session = useSessionStore()
const dialogTitle = computed(() => (props.original ? '编辑' : '新建') + (props.direction === 'in' ? '其他入库单' : '其他出库单'))
const form = reactive({
  purpose: props.original?.purpose ?? '',
  warehouseId: props.original?.warehouseId ?? '',
  partnerId: props.original?.partnerId ?? '',
  incomeExpenseItemId: props.original?.incomeExpenseItemId ?? '',
  businessDate: props.original?.businessDate ?? today(),
  expectedReturnDate: props.original?.expectedReturnDate ?? '',
  originalLoanId: props.original?.originalLoanId ?? '',
  remark: props.original?.remark ?? '',
})
interface InputLine {
  row: number
  skuId: string
  unitId: string
  qty: string
  inputUnitCost: string
  zeroCostReason: string
  originalLoanLineId: string
  stockSelection: StockSelection | null
}
let rowNumber = 0
const lines = ref<InputLine[]>((props.original?.lines ?? []).map(line => ({
  row: ++rowNumber, skuId: line.skuId, unitId: line.unitId, qty: line.qty, inputUnitCost: line.inputUnitCost ?? '',
  zeroCostReason: line.zeroCostReason ?? '', originalLoanLineId: line.originalLoanLineId ?? '', stockSelection: line.stockSelection ?? null,
})))
const warehouses = ref<DataRow[]>([]), partners = ref<DataRow[]>([]), accounts = ref<DataRow[]>([]), skus = ref<DataRow[]>([])
const purposes = ref<DictionaryItem[]>([])
const units = reactive<Record<string, SalesSkuUnit[]>>({})
const loans = ref<StockMiscDocument[]>([]), outstanding = ref<LoanOutstanding[]>([])
const loanKeyword = ref(''), loadBusy = ref(false), busy = ref(false), uncertain = ref(false), error = ref('')
const key = newIdempotencyKey()
let retryPayload: object | undefined

function addLine(): void {
  lines.value.push({ row: ++rowNumber, skuId: '', unitId: '', qty: '', inputUnitCost: '', zeroCostReason: '', originalLoanLineId: '', stockSelection: null })
}

async function chooseSku(line: InputLine, reset: boolean): Promise<void> {
  const id = line.skuId
  if (!id) return
  if (reset) line.stockSelection = null
  if (units[id] === undefined) {
    const result = await record('skus/' + id)
    units[id] = (result.units as SalesSkuUnit[] | undefined) ?? []
  }
  if (line.skuId === id && reset) line.unitId = units[id]?.find(unit => unit.isBase)?.unitId ?? ''
}

async function chooseLoanLine(line: InputLine): Promise<void> {
  const source = outstanding.value.find(item => item.id === line.originalLoanLineId)
  if (!source) return
  line.skuId = source.skuId
  line.qty = formatQuantity(source.remainingQty).replaceAll(',', '')
  await chooseSku(line, true)
}

async function fetchLoans(): Promise<void> {
  const result = await stockLoans({ keyword: loanKeyword.value })
  loans.value = result.items
}

async function chooseLoan(reset: boolean): Promise<void> {
  const id = form.originalLoanId
  if (reset) lines.value.forEach(line => { line.originalLoanLineId = ''; line.stockSelection = null })
  if (!id) {
    outstanding.value = []
    return
  }
  if (reset) form.partnerId = loans.value.find(item => item.id === id)?.partnerId ?? ''
  const rows = await stockLoanLines(id)
  if (id === form.originalLoanId) outstanding.value = rows.filter(item => Number(item.remainingQty) > 0)
}

async function choosePurpose(): Promise<void> {
  form.expectedReturnDate = ''
  form.originalLoanId = ''
  lines.value.forEach(line => { line.originalLoanLineId = ''; line.stockSelection = null })
  if (form.purpose === 'loan_return') await fetchLoans()
}

function reportError(cause: unknown): void {
  if (!isCancelled(cause)) error.value = messageOf(cause)
}

function payload(): object {
  const loan = form.purpose === 'loan', back = form.purpose === 'loan_return'
  const cost = props.direction === 'in' && !back
  return {
    direction: props.direction, purpose: form.purpose, warehouseId: form.warehouseId,
    partnerId: form.partnerId || undefined, incomeExpenseItemId: form.incomeExpenseItemId,
    businessDate: form.businessDate, expectedReturnDate: loan ? form.expectedReturnDate : undefined,
    originalLoanId: back ? form.originalLoanId : undefined, remark: form.remark, version: props.original?.version,
    lines: lines.value.map(line => ({
      skuId: line.skuId, unitId: line.unitId, qty: line.qty,
      inputUnitCost: cost ? line.inputUnitCost : undefined,
      zeroCostReason: cost && Number(line.inputUnitCost) === 0 ? line.zeroCostReason : undefined,
      originalLoanLineId: back ? line.originalLoanLineId : undefined, stockSelection: line.stockSelection,
    })),
  }
}

async function save(): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  retryPayload ??= payload()
  try {
    emit('saved', await saveStockMisc(retryPayload, props.original?.id, key))
  } catch (cause: unknown) {
    reportError(cause)
    uncertain.value = cause instanceof ApiError && (cause.status === 0 || cause.code === 'REQUEST_IN_PROGRESS')
    if (!uncertain.value) retryPayload = undefined
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  loadBusy.value = true
  try {
    const [warehouseRows, partnerRows, accountRows, skuRows, dictionary] = await Promise.all([
      allRecords('warehouses', true),
      session.can('partner:view') ? allRecords('partners') : [],
      allRecords('income-expense-items'),
      allRecords('skus'),
      dictionaries('stock_misc_purpose'),
    ])
    warehouses.value = warehouseRows.filter(item => item.status === 'active')
    partners.value = partnerRows.filter(item => item.status === 'active')
    accounts.value = accountRows.filter(item => item.status === 'active' &&
      item.itemType === (props.direction === 'in' ? 'income' : 'expense'))
    skus.value = skuRows.filter(item => item.status === 'active')
    purposes.value = (dictionary.items?.stock_misc_purpose ?? []).filter(option => option.enabled &&
      !(props.direction === 'in' ? ['loan', 'sample', 'internal'] : ['gift', 'loan_return']).includes(option.value))
    if (!lines.value.length) addLine()
    await Promise.all(lines.value.map(line => chooseSku(line, false)))
    if (form.originalLoanId) {
      await fetchLoans()
      await chooseLoan(false)
    }
  } catch (cause: unknown) {
    reportError(cause)
  } finally {
    loadBusy.value = false
  }
})
</script>

<template>
  <el-dialog :model-value="true" :title="dialogTitle" width="min(1040px, 94vw)" :close-on-click-modal="false" :show-close="!busy" @close="emit('close')">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="uncertain" title="保存结果待确认，请使用原内容重试。" type="warning" :closable="false" />
    <el-form label-position="top" :disabled="busy || loadBusy || uncertain">
      <div class="sales-form-grid">
        <el-form-item label="仓库"><el-select v-model="form.warehouseId" filterable @change="lines.forEach(line => line.stockSelection = null)"><el-option v-for="item in warehouses" :key="item.id" :label="item.name ?? item.id" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="业务用途"><el-select v-model="form.purpose" @change="choosePurpose().catch(reportError)"><el-option v-for="item in purposes" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="业务日期"><el-input v-model="form.businessDate" type="date" /></el-form-item>
        <el-form-item label="往来单位"><el-select v-model="form.partnerId" :disabled="form.purpose === 'loan_return'" clearable filterable><el-option v-for="item in partners" :key="item.id" :label="item.name ?? item.id" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="收支项目"><el-select v-model="form.incomeExpenseItemId" filterable><el-option v-for="item in accounts" :key="item.id" :label="item.name ?? item.id" :value="item.id" /></el-select></el-form-item>
        <el-form-item v-if="form.purpose === 'loan'" label="预计归还日期"><el-input v-model="form.expectedReturnDate" type="date" /></el-form-item>
        <template v-if="form.purpose === 'loan_return'">
          <el-form-item label="按单号找借出单"><el-input v-model="loanKeyword" clearable @keyup.enter.prevent="fetchLoans().catch(reportError)" /><el-button @click="fetchLoans().catch(reportError)">查询</el-button></el-form-item>
          <el-form-item label="原借出单"><el-select v-model="form.originalLoanId" filterable @change="chooseLoan(true).catch(reportError)"><el-option v-for="item in loans" :key="item.id" :label="item.documentNo + ' · ' + (item.partnerName ?? '')" :value="item.id" /></el-select></el-form-item>
        </template>
      </div>
      <div v-for="(line, index) in lines" :key="line.row" class="stock-misc-line">
        <div class="stock-misc-fields">
          <el-form-item v-if="form.purpose === 'loan_return'" :label="'原借出商品 ' + (index + 1)">
            <el-select v-model="line.originalLoanLineId" filterable @change="chooseLoanLine(line).catch(reportError)">
              <el-option v-for="item in outstanding" :key="item.id" :value="item.id" :label="(item.skuName ?? item.skuId) + ' · 未还 ' + formatQuantity(item.remainingQty)" />
            </el-select>
          </el-form-item>
          <el-form-item v-else :label="'商品 ' + (index + 1)"><el-select v-model="line.skuId" filterable @change="chooseSku(line, true).catch(reportError)"><el-option v-for="item in skus" :key="item.id" :value="item.id" :label="(item.code ?? '') + ' ' + (item.name ?? item.id)" /></el-select></el-form-item>
          <el-form-item label="单位"><el-select v-model="line.unitId"><el-option v-for="item in units[line.skuId] ?? []" :key="item.unitId" :value="item.unitId" :label="item.unitName" /></el-select></el-form-item>
          <el-form-item label="数量"><el-input v-model="line.qty" /></el-form-item>
          <template v-if="direction === 'in' && form.purpose !== 'loan_return' && session.can('cost:view')">
            <el-form-item label="基础单位成本"><el-input v-model="line.inputUnitCost" /></el-form-item>
            <el-form-item v-if="line.inputUnitCost && Number(line.inputUnitCost) === 0" label="零成本原因"><el-input v-model="line.zeroCostReason" maxlength="200" /></el-form-item>
          </template>
          <el-button link type="danger" :disabled="lines.length === 1" @click="lines.splice(index, 1)">移除</el-button>
        </div>
        <StockSelectionFields v-if="line.skuId && form.warehouseId" v-model="line.stockSelection" :warehouse-id="form.warehouseId" :sku-id="line.skuId" :mode="form.purpose === 'loan_return' ? 'return' : direction" />
      </div>
      <el-button :disabled="lines.length >= 200" @click="addLine">添加商品</el-button>
      <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" maxlength="500" /></el-form-item>
    </el-form>
    <template #footer><el-button :disabled="busy" @click="emit('close')">关闭</el-button><el-button type="primary" :loading="busy || loadBusy" @click="save">{{ uncertain ? '重试原请求' : '保存草稿' }}</el-button></template>
  </el-dialog>
</template>

<style scoped>
.stock-misc-line { border-block-start: 1px solid var(--el-border-color-lighter); padding-block: 10px; }
.stock-misc-fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: 8px; align-items: center; }
</style>
