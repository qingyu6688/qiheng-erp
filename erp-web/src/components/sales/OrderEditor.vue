<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { allRecords, record } from '../../api/admin'
import { pricePreview, saveDocument, commandResult, salesCommand } from '../../api/sales'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { today } from '../../types/sales'
import type { SalesDocument, SalesSkuUnit } from '../../types/sales'
import type { DataRow } from '../../types/admin'
import { useSessionStore } from '../../stores/session'

const props = defineProps<{ original?: SalesDocument; change?: boolean }>()
const emit = defineEmits<{ close: []; saved: [SalesDocument] }>()
const session = useSessionStore()
const customerId = props.original?.customerId ?? props.original?.customer?.id ?? ''
const form = reactive({
  customerId, businessDate: props.original?.businessDate ?? today(), deliveryDate: props.original?.deliveryDate ?? '',
  priceTaxInclusive: props.original?.priceTaxInclusive ?? true, orderDiscountAmount: props.original?.orderDiscountAmount ?? '0',
  remark: props.original?.remark ?? '', reason: '',
})
interface InputLine { id?: string; skuId: string; warehouseId: string; unitId: string; qty: string; unitPrice: string; lineDiscountAmount: string; taxRate: string }
const lines = ref<InputLine[]>((props.original?.lines ?? []).map(l => ({
  id: l.id, skuId: l.skuId ?? l.sku?.id ?? '', warehouseId: l.warehouseId ?? l.warehouse?.id ?? '',
  unitId: l.unitId ?? l.unit?.id ?? '', qty: l.qty ?? '', unitPrice: l.unitPrice ?? '',
  lineDiscountAmount: l.lineDiscountAmount ?? '0', taxRate: l.taxRate ?? '0.13',
})))
const fees = ref((props.original?.fees ?? []).map(f => ({ ...f })))
const customers = ref<DataRow[]>([]), skus = ref<DataRow[]>([]), warehouses = ref<DataRow[]>([])
const units = reactive<Record<string, SalesSkuUnit[]>>({})
const preview = ref<SalesDocument>()
const error = ref(''), notice = ref(''), busy = ref(false), loading = ref(true), uncertain = ref(false), conflict = ref(false)
const key = newIdempotencyKey()
let timer: ReturnType<typeof setTimeout> | undefined
let sequence = 0
const ready = computed(() => Boolean(form.customerId && lines.value.length && lines.value.every(l => l.skuId && l.unitId && l.qty) && (!props.change || form.reason.trim())))
function body(): Record<string, unknown> {
  return { ...form, deliveryDate: form.deliveryDate || null, version: props.original?.version,
    lines: lines.value.map(l => ({ ...l, unitPrice: l.unitPrice || undefined, warehouseId: l.warehouseId || null, taxRate: session.can('document:change_tax_rate') ? l.taxRate : undefined })),
    fees: fees.value.map(f => ({ ...f, taxRate: session.can('document:change_tax_rate') ? f.taxRate : undefined })) }
}
async function chooseSku(line: InputLine): Promise<void> {
  try {
    const sku = await record('skus/' + line.skuId)
    const values = sku.units as SalesSkuUnit[]
    units[line.skuId] = values ?? []
    line.unitId = values?.find(u => u.isBase)?.unitId ?? ''
    line.unitPrice = ''
    line.taxRate = String(sku.defaultTaxRate ?? '0.13')
  } catch (cause: unknown) { error.value = messageOf(cause) }
}
async function calculate(): Promise<void> {
  const current = ++sequence
  if (!ready.value) { preview.value = undefined; return }
  try { const value = await pricePreview(body()); if (current === sequence) { preview.value = value; error.value = '' } }
  catch (cause: unknown) { if (current === sequence && !isCancelled(cause)) { error.value = messageOf(cause); preview.value = undefined } }
}
watch([form, lines, fees], () => { clearTimeout(timer); timer = setTimeout(() => { void calculate() }, 350) }, { deep: true })
onUnmounted(() => { clearTimeout(timer); sequence++ })
onMounted(async () => {
  try {
    [customers.value, skus.value, warehouses.value] = await Promise.all([
      allRecords('partners', false), allRecords('skus'), allRecords('warehouses', true),
    ])
    customers.value = customers.value.filter(c => (c.roles as string[] | undefined)?.includes('customer') && c.status === 'active')
    for (const l of lines.value) {
      const sku = await record('skus/' + l.skuId)
      units[l.skuId] = sku.units as SalesSkuUnit[]
    }
    if (!lines.value.length) addLine()
    await calculate()
  } catch (cause: unknown) { if (!isCancelled(cause)) error.value = messageOf(cause) }
  finally { loading.value = false }
})
function addLine(): void { lines.value.push({ skuId: '', warehouseId: '', unitId: '', qty: '1', unitPrice: '', lineDiscountAmount: '0', taxRate: '0.13' }) }
async function save(): Promise<void> {
  if (busy.value || !ready.value) return
  busy.value = true; error.value = ''
  try {
    await calculate()
    if (!preview.value) return
    const value = props.change && props.original
      ? await salesCommand('sales-orders', props.original.id, 'changes', body(), key)
      : await saveDocument('sales-orders', body(), props.original?.id, props.original ? undefined : key)
    emit('saved', value)
  } catch (cause: unknown) {
    if (!isCancelled(cause)) {
      error.value = messageOf(cause)
      if (cause instanceof ApiError) {
        const unknown = cause.status === 0 || cause.code === 'REQUEST_IN_PROGRESS'
        uncertain.value = unknown && (!props.original || props.change === true)
        conflict.value = cause.code === 'VERSION_CONFLICT' || (unknown && Boolean(props.original) && !props.change)
      }
    }
  } finally { busy.value = false }
}
async function verify(): Promise<void> {
  try { const result = await commandResult(props.change ? 'sales_order.change' : 'sales_order.create', key); if (result.status === 'succeeded') emit('saved', result.response) }
  catch (cause: unknown) { error.value = messageOf(cause); if (cause instanceof ApiError && cause.status === 404) { uncertain.value = false; notice.value = '尚无已提交结果，可使用原请求重试' } }
}
</script>
<template>
  <el-dialog :model-value="true" :title="change ? '销售订单变更' : original ? '编辑销售订单' : '新建销售订单'" class="sales-editor-dialog" top="5vh" width="min(1100px, 96vw)" :close-on-click-modal="false" :show-close="!busy" @close="emit('close')">
    <fieldset :disabled="busy || loading || uncertain || conflict" class="form-fieldset">
      <el-form label-position="top" class="sales-header-form">
        <el-form-item label="客户" for="order-customer"><el-select id="order-customer" v-model="form.customerId" filterable :disabled="change"><el-option v-for="c in customers" :key="c.id" :value="c.id" :label="String(c.name)" /></el-select></el-form-item>
        <el-form-item label="业务日期" for="order-date"><el-input id="order-date" v-model="form.businessDate" type="date" /></el-form-item>
        <el-form-item label="交货日期" for="order-delivery"><el-input id="order-delivery" v-model="form.deliveryDate" type="date" /></el-form-item>
        <el-form-item label="价格口径"><el-switch v-model="form.priceTaxInclusive" active-text="含税" inactive-text="未税" :disabled="change" /></el-form-item>
        <el-form-item label="整单优惠" for="order-discount"><el-input id="order-discount" v-model="form.orderDiscountAmount" /></el-form-item>
        <el-form-item label="备注" for="order-remark"><el-input id="order-remark" v-model="form.remark" maxlength="500" /></el-form-item>
      </el-form>
      <div class="sales-lines-scroll"><table class="sales-input-table"><thead><tr><th>商品</th><th>仓库</th><th>单位</th><th>数量</th><th>单价</th><th>行优惠</th><th v-if="session.can('document:change_tax_rate')">税率</th><th></th></tr></thead><tbody>
        <tr v-for="(line, index) in lines" :key="line.id ?? index">
          <td><el-select v-model="line.skuId" :aria-label="'商品'+(index+1)" filterable @change="chooseSku(line)"><el-option v-for="s in skus.filter(s => s.status === 'active')" :key="s.id" :value="s.id" :label="String(s.code)+' '+String(s.name)" /></el-select></td>
          <td><el-select v-model="line.warehouseId" :aria-label="'仓库'+(index+1)" clearable><el-option v-for="w in warehouses.filter(w=>w.status==='active')" :key="w.id" :value="w.id" :label="String(w.name)" /></el-select></td>
          <td><el-select v-model="line.unitId" :aria-label="'单位'+(index+1)" @change="line.unitPrice=''"><el-option v-for="u in units[line.skuId] ?? []" :key="u.unitId" :value="u.unitId" :label="u.unitName" /></el-select></td>
          <td><el-input v-model="line.qty" :aria-label="'数量'+(index+1)" /></td><td><el-input v-model="line.unitPrice" :aria-label="'单价'+(index+1)" placeholder="留空使用单位参考价" /></td><td><el-input v-model="line.lineDiscountAmount" :aria-label="'行优惠'+(index+1)" /></td>
          <td v-if="session.can('document:change_tax_rate')"><el-input v-model="line.taxRate" :aria-label="'税率'+(index+1)" /></td><td><el-button text @click="lines.splice(index,1)">移除</el-button></td>
        </tr></tbody></table></div>
      <el-button @click="addLine">添加商品行</el-button>
      <section class="sale-fees"><div v-for="(fee,index) in fees" :key="index" class="relation-row">
        <el-select v-model="fee.feeType" aria-label="费用类型"><el-option value="freight" label="运费" /><el-option value="other" label="其他费用" /></el-select>
        <el-select v-model="fee.bearer" aria-label="费用承担方"><el-option value="buyer" label="买方承担" /><el-option value="seller" label="我方承担" /></el-select>
        <el-input v-model="fee.amount" aria-label="费用金额" /><el-button text @click="fees.splice(index,1)">移除</el-button>
      </div><el-button text @click="fees.push({feeType:'freight',bearer:'buyer',amount:'0',taxRate:'0.13'})">添加费用</el-button></section>
      <el-form-item v-if="change" label="变更原因"><el-input v-model="form.reason" maxlength="200" /></el-form-item>
    </fieldset>
    <div v-if="preview" class="sales-totals"><span>商品 {{ preview.goodsAmount }}</span><span>行优惠 {{ preview.lineDiscountAmount }}</span><span>税额 {{ preview.taxAmount }}</span><strong>合计 ¥{{ preview.totalAmount }}</strong></div>
    <p v-if="notice" role="status">{{ notice }}</p><el-alert v-if="error" type="error" :title="error" :closable="false" />
    <p v-if="conflict">数据已有更新，请关闭并刷新后编辑。</p>
    <template #footer><el-button :disabled="busy" @click="emit('close')">取消</el-button><el-button v-if="uncertain" type="primary" @click="verify">查询结果</el-button><el-button v-else type="primary" :disabled="loading || !ready || conflict" :loading="busy" @click="save">保存订单</el-button></template>
  </el-dialog>
</template>
