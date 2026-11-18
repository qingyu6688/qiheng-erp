<script setup lang="ts">
import DynamicFields from '../DynamicFields.vue'
import { customFields } from '../../api/platform-rules'
import type { CustomField, FieldValues } from '../../api/platform-rules'
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { allRecords, record } from '../../api/admin'
import { pricePreview, saveDocument, commandResult, salesCommand } from '../../api/sales'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { today } from '../../types/sales'
import type { SalesDocument, SalesSkuUnit } from '../../types/sales'
import type { DataRow } from '../../types/admin'
import { useSessionStore } from '../../stores/session'
import DocumentEditorFrame from '../DocumentEditorFrame.vue'
import BusinessUserSelect from '../BusinessUserSelect.vue'
import AreaCascader from '../AreaCascader.vue'
import EditableTable from '../EditableTable.vue'
import FixedFooter from '../FixedFooter.vue'
import OrderLineImport from '../OrderLineImport.vue'
import { ORDER_LINE_LIMIT } from '../../utils/order-line-paste'
import type { PastedOrderLine } from '../../utils/order-line-paste'

const props = defineProps<{ original?: SalesDocument; change?: boolean; inline?: boolean }>()
const emit = defineEmits<{ close: []; saved: [SalesDocument, boolean?]; dirty: [boolean] }>()
let submitRequested = false
const session = useSessionStore()
const customerId = props.original?.customerId ?? props.original?.customer?.id ?? ''
const fields = ref<CustomField[]>([]),
  ext = ref<FieldValues>({ ...props.original?.extJson })
const form = reactive({
  customerId,
  salesUserId: (props.original?.salesUserId === '0' ? '' : props.original?.salesUserId) ?? '',
  deliveryAreaId: props.original?.deliveryAreaId ?? (null as string | null),
  deliveryAddress: props.original?.deliveryAddress ?? '',
  businessDate: props.original?.businessDate ?? today(),
  deliveryDate: props.original?.deliveryDate ?? '',
  priceTaxInclusive: props.original?.priceTaxInclusive ?? true,
  orderDiscountAmount: props.original?.orderDiscountAmount ?? '0',
  remark: props.original?.remark ?? '',
  reason: '',
})
const lineFields = ref<CustomField[]>([])
interface InputLine {
  extJson?: FieldValues
  id?: string
  skuId: string
  warehouseId: string
  unitId: string
  qty: string
  unitPrice: string
  lineDiscountAmount: string
  taxRate: string
}
const lines = ref<InputLine[]>(
  (props.original?.lines ?? []).map((l) => ({
    id: l.id,
    extJson: l.extJson ? { ...l.extJson } : undefined,
    skuId: l.skuId ?? l.sku?.id ?? '',
    warehouseId: l.warehouseId ?? l.warehouse?.id ?? '',
    unitId: l.unitId ?? l.unit?.id ?? '',
    qty: l.qty ?? '',
    unitPrice: l.unitPrice ?? '',
    lineDiscountAmount: l.lineDiscountAmount ?? '0',
    taxRate: l.taxRate ?? '0.13',
  })),
)
const fees = ref((props.original?.fees ?? []).map((f) => ({ ...f })))
const customers = ref<DataRow[]>([]),
  skus = ref<DataRow[]>([]),
  warehouses = ref<DataRow[]>([])
const units = reactive<Record<string, SalesSkuUnit[]>>({})
const preview = ref<SalesDocument>()
const error = ref(''),
  notice = ref(''),
  busy = ref(false),
  loading = ref(true),
  uncertain = ref(false),
  conflict = ref(false)
const key = newIdempotencyKey()
let timer: ReturnType<typeof setTimeout> | undefined
let sequence = 0
let baseline = ''
let active = true
const calculating = ref(false)
const editable = ref<InstanceType<typeof EditableTable>>()
const importer = ref<InstanceType<typeof OrderLineImport>>()
const populatedLineCount = computed(() => lines.value.filter((line) => line.skuId).length)
const lineErrors = computed(() =>
  lines.value.map((line) => ({
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
  })),
)
const errorCount = computed(() =>
  lineErrors.value.reduce((count, row) => count + Object.values(row).filter(Boolean).length, 0),
)
function locateError(): void {
  const index = lineErrors.value.findIndex((row) => Object.values(row).some(Boolean))
  const row = lineErrors.value[index]
  if (row) editable.value?.focus(index, row.skuId ? 0 : row.unitId ? 2 : row.qty ? 3 : 4)
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
    })),
  ]
  notice.value = '已追加' + items.length + '行，请核对单位、仓库和单价'
}
const title = computed(() =>
  props.change ? '销售订单变更' : props.original ? '编辑销售订单' : '新建销售订单',
)
const ready = computed(() =>
  Boolean(
    form.customerId &&
    lines.value.length &&
    lines.value.every((l) => l.skuId && l.unitId && l.qty) &&
    (!props.change || form.reason.trim()),
  ),
)
function body(): Record<string, unknown> {
  return {
    ...form,
    extJson: fields.value.length ? ext.value : undefined,
    salesUserId: form.salesUserId || null,
    deliveryAreaId: form.deliveryAreaId || null,
    deliveryAddress: form.deliveryAddress || null,
    deliveryDate: form.deliveryDate || null,
    version: props.original?.version,
    lines: lines.value.map((l) => ({
      ...l,
      unitPrice: l.unitPrice || undefined,
      warehouseId: l.warehouseId || null,
      taxRate: session.can('document:change_tax_rate') ? l.taxRate : undefined,
    })),
    fees: fees.value.map((f) => ({
      ...f,
      taxRate: session.can('document:change_tax_rate') ? f.taxRate : undefined,
    })),
  }
}
async function chooseSku(line: InputLine): Promise<void> {
  const selected = line.skuId
  line.unitId = ''
  line.unitPrice = ''
  if (!selected) return
  try {
    const sku = await record('skus/' + selected)
    if (!active || line.skuId !== selected || !lines.value.includes(line)) return
    const values = sku.units as SalesSkuUnit[]
    units[line.skuId] = values ?? []
    line.unitId = values?.find((u) => u.isBase)?.unitId ?? ''
    line.unitPrice = ''
    line.taxRate = String(sku.defaultTaxRate ?? '0.13')
  } catch (cause: unknown) {
    if (line.skuId === selected && !isCancelled(cause)) error.value = messageOf(cause)
  }
}
async function calculate(): Promise<void> {
  clearTimeout(timer)
  if (!active) return
  const current = ++sequence
  if (!ready.value) {
    preview.value = undefined
    calculating.value = false
    return
  }
  calculating.value = true
  try {
    const value = await pricePreview(body())
    if (current === sequence) {
      preview.value = value
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
    preview.value = undefined
    calculating.value = ready.value
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
})
onMounted(async () => {
  try {
    if (session.companyId) {
      const [header, detail] = await Promise.allSettled([
        customFields('sales_order'),
        customFields('sales_order_line'),
      ])
      if (header.status === 'fulfilled') fields.value = header.value
      else if (!isCancelled(header.reason)) error.value = messageOf(header.reason)
      if (detail.status === 'fulfilled') lineFields.value = detail.value
      else if (!isCancelled(detail.reason)) error.value = messageOf(detail.reason)
    }
    ;[customers.value, skus.value, warehouses.value] = await Promise.all([
      allRecords('partners', false),
      allRecords('skus'),
      allRecords('warehouses', true),
    ])
    customers.value = customers.value.filter(
      (c) => (c.roles as string[] | undefined)?.includes('customer') && c.status === 'active',
    )
    for (const l of lines.value) {
      const sku = await record('skus/' + l.skuId)
      units[l.skuId] = sku.units as SalesSkuUnit[]
    }
    if (!lines.value.length) addLine()
    await calculate()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    baseline = JSON.stringify(body())
    loading.value = false
  }
})
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
  })
}
async function save(submitAfterSave = false): Promise<void> {
  if (busy.value || !ready.value) return
  if (errorCount.value) {
    locateError()
    return
  }
  submitRequested = submitAfterSave
  busy.value = true
  error.value = ''
  try {
    await calculate()
    if (!preview.value) return
    const value =
      props.change && props.original
        ? await salesCommand('sales-orders', props.original.id, 'changes', body(), key)
        : await saveDocument(
            'sales-orders',
            body(),
            props.original?.id,
            props.original ? undefined : key,
          )
    emit('saved', value, submitRequested)
  } catch (cause: unknown) {
    if (!isCancelled(cause)) {
      error.value = messageOf(cause)
      if (cause instanceof ApiError) {
        const unknown = cause.status === 0 || cause.code === 'REQUEST_IN_PROGRESS'
        uncertain.value = unknown && (!props.original || props.change === true)
        conflict.value =
          cause.code === 'VERSION_CONFLICT' || (unknown && Boolean(props.original) && !props.change)
      }
    }
  } finally {
    busy.value = false
  }
}
async function verify(): Promise<void> {
  try {
    const result = await commandResult(
      props.change ? 'sales_order.change' : 'sales_order.create',
      key,
    )
    if (result.status === 'succeeded') emit('saved', result.response, submitRequested)
  } catch (cause: unknown) {
    error.value = messageOf(cause)
    if (cause instanceof ApiError && cause.status === 404) {
      uncertain.value = false
      notice.value = '尚无已提交结果，可使用原请求重试'
    }
  }
}
</script>
<template>
  <DocumentEditorFrame :inline="inline" :title="title" :busy="busy" @close="emit('close')">
    <fieldset :disabled="busy || loading || uncertain || conflict" class="form-fieldset">
      <el-form label-position="top" class="sales-header-form">
        <el-form-item label="客户" for="order-customer"
          ><el-select id="order-customer" v-model="form.customerId" filterable :disabled="change"
            ><el-option
              v-for="c in customers"
              :key="c.id"
              :value="c.id"
              :label="String(c.name)" /></el-select
        ></el-form-item>
        <el-form-item label="销售员"
          ><BusinessUserSelect
            v-model="form.salesUserId"
            label="销售员"
            :name="original?.salesUserName"
            :disabled="Boolean(change)"
        /></el-form-item>
        <el-form-item label="送货省市区"
          ><AreaCascader v-model="form.deliveryAreaId" label="送货省市区"
        /></el-form-item>
        <el-form-item label="送货详细地址"
          ><el-input v-model="form.deliveryAddress" maxlength="200"
        /></el-form-item>
        <el-form-item label="业务日期" for="order-date"
          ><el-input id="order-date" v-model="form.businessDate" type="date"
        /></el-form-item>
        <el-form-item label="交货日期" for="order-delivery"
          ><el-input id="order-delivery" v-model="form.deliveryDate" type="date"
        /></el-form-item>
        <el-form-item label="价格口径"
          ><el-switch
            v-model="form.priceTaxInclusive"
            active-text="含税"
            inactive-text="未税"
            :disabled="change"
        /></el-form-item>
        <el-form-item label="整单优惠" for="order-discount"
          ><el-input id="order-discount" v-model="form.orderDiscountAmount"
        /></el-form-item>
        <el-form-item label="备注" for="order-remark"
          ><el-input id="order-remark" v-model="form.remark" maxlength="500"
        /></el-form-item>
        <template v-if="fields.length"
          ><h3>其他信息</h3>
          <DynamicFields v-model="ext" :fields="fields" prefix="sales-extra-"
        /></template>
      </el-form>
      <EditableTable
        ref="editable"
        :count="lines.length"
        :disabled="busy || loading || uncertain || conflict"
        @add="addLine"
        @remove="lines.splice($event, 1)"
        @paste="importer?.openPaste($event)"
      >
        <template #tools>
          <OrderLineImport
            ref="importer"
            kind="sales-orders"
            :count="populatedLineCount"
            :partner-id="form.customerId"
            :price-tax-inclusive="form.priceTaxInclusive"
            :disabled="busy || loading || uncertain || conflict"
            :skus="skus"
            @import="importLines"
          />
          <el-button :disabled="lines.length >= ORDER_LINE_LIMIT" @click="addLine"
            >添加商品行</el-button
          >
        </template>
        <template #head>
          <tr>
            <th scope="col">#</th>
            <th>商品</th>
            <th>仓库</th>
            <th>单位</th>
            <th>数量</th>
            <th>单价</th>
            <th>行优惠</th>
            <th v-if="session.can('document:change_tax_rate')">税率</th>
            <th v-if="lineFields.length">明细其他信息</th>
            <th scope="col">金额</th>
            <th scope="col">操作</th>
          </tr>
        </template>
        <tr v-for="(line, index) in lines" :key="line.id ?? index" data-edit-row>
          <td>{{ index + 1 }}</td>
          <td :data-error="lineErrors[index]?.skuId || undefined" :title="lineErrors[index]?.skuId">
            <el-select
              v-model="line.skuId"
              :aria-label="'商品' + (index + 1)"
              filterable
              @change="chooseSku(line)"
              ><el-option
                v-for="s in skus.filter((s) => s.status === 'active')"
                :key="s.id"
                :value="s.id"
                :label="String(s.code) + ' ' + String(s.name)"
            /></el-select>
          </td>
          <td>
            <el-select v-model="line.warehouseId" :aria-label="'仓库' + (index + 1)" clearable
              ><el-option
                v-for="w in warehouses.filter((w) => w.status === 'active')"
                :key="w.id"
                :value="w.id"
                :label="String(w.name)"
            /></el-select>
          </td>
          <td
            :data-error="lineErrors[index]?.unitId || undefined"
            :title="lineErrors[index]?.unitId"
          >
            <el-select
              v-model="line.unitId"
              :aria-label="'单位' + (index + 1)"
              @change="line.unitPrice = ''"
              ><el-option
                v-for="u in units[line.skuId] ?? []"
                :key="u.unitId"
                :value="u.unitId"
                :label="u.unitName"
            /></el-select>
          </td>
          <td :data-error="lineErrors[index]?.qty || undefined" :title="lineErrors[index]?.qty">
            <el-input v-model="line.qty" :aria-label="'数量' + (index + 1)" />
          </td>
          <td
            :data-error="lineErrors[index]?.unitPrice || undefined"
            :title="lineErrors[index]?.unitPrice"
          >
            <el-input
              v-model="line.unitPrice"
              :aria-label="'单价' + (index + 1)"
              placeholder="留空使用单位参考价"
            />
          </td>
          <td>
            <el-input v-model="line.lineDiscountAmount" :aria-label="'行优惠' + (index + 1)" />
          </td>
          <td v-if="session.can('document:change_tax_rate')">
            <el-input v-model="line.taxRate" :aria-label="'税率' + (index + 1)" />
          </td>
          <td v-if="lineFields.length">
            <details>
              <summary>第 {{ index + 1 }} 行</summary>
              <el-form label-position="top"
                ><DynamicFields
                  :model-value="line.extJson ?? {}"
                  :fields="lineFields"
                  :prefix="'sales-line-' + index + '-'"
                  @update:model-value="line.extJson = $event"
              /></el-form>
            </details>
          </td>
          <td class="line-amount">
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
        <template #summary>
          <tr>
            <td
              :colspan="
                7 + (session.can('document:change_tax_rate') ? 1 : 0) + (lineFields.length ? 1 : 0)
              "
            >
              合计
            </td>
            <td class="line-amount">{{ calculating ? '…' : (preview?.goodsAmount ?? '—') }}</td>
            <td />
          </tr>
        </template>
      </EditableTable>
      <section class="sale-fees">
        <div v-for="(fee, index) in fees" :key="index" class="relation-row">
          <el-select v-model="fee.feeType" aria-label="费用类型"
            ><el-option value="freight" label="运费" /><el-option value="other" label="其他费用"
          /></el-select>
          <el-select v-model="fee.bearer" aria-label="费用承担方"
            ><el-option value="buyer" label="买方承担" /><el-option value="seller" label="我方承担"
          /></el-select>
          <el-input v-model="fee.amount" aria-label="费用金额" /><el-button
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
      <el-form-item v-if="change" label="变更原因"
        ><el-input v-model="form.reason" maxlength="200"
      /></el-form-item>
    </fieldset>
    <p v-if="notice" role="status">{{ notice }}</p>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <p v-if="conflict">数据已有更新，请关闭并刷新后编辑。</p>
    <template #footer>
      <FixedFooter :calculating="calculating" :errors="errorCount" @locate-error="locateError">
        <template v-if="preview" #summary>
          <span>商品 {{ preview.goodsAmount }}</span
          ><span>行优惠 {{ preview.lineDiscountAmount }}</span>
          <span>税额 {{ preview.taxAmount }}</span
          ><strong>合计 ¥{{ preview.totalAmount }}</strong>
        </template>
        <el-button :disabled="busy" @click="emit('close')">取消</el-button
        ><el-button v-if="uncertain" type="primary" @click="verify">查询结果</el-button
        ><template v-else>
          <el-button :disabled="busy || loading || !ready || conflict" @click="save()">保存订单</el-button>
          <el-button
            v-if="inline && !change && session.can('sales_order:submit')"
            type="primary"
            :disabled="busy || loading || !ready || conflict"
            @click="save(true)"
          >保存并提交</el-button>
        </template></FixedFooter
      >
    </template>
  </DocumentEditorFrame>
</template>
