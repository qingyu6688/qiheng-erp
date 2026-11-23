<script setup lang="ts">
import WorkflowStarterSelector from '../workflow/WorkflowStarterSelector.vue'
import type { WorkflowSelections } from '../../types/workflow'
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { allRecords, record } from '../../api/admin'
import { reversalSources, saveStock, stockResult } from '../../api/stock-operations'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { reversalTypes, stockSpecs } from '../../types/stock-operations'
import type { ReversalSource, StockDocument, StockKind } from '../../types/stock-operations'
import type { DataRow } from '../../types/admin'
import type { SalesSkuUnit } from '../../types/sales'
import { today } from '../../types/sales'
import { useSessionStore } from '../../stores/session'
import DependencyList from './DependencyList.vue'
import StockSelectionFields from './StockSelectionFields.vue'
import type { StockSelection } from '../../types/inventory-tracking'

const props = defineProps<{ kind: StockKind; original?: StockDocument; presetSource?: ReversalSource & { sourceType: string } }>()
const emit = defineEmits<{ close: []; saved: [StockDocument] }>()
const session = useSessionStore(), spec = computed(() => stockSpecs.find(item => item.path === props.kind)!)
const transfer = computed(() => props.kind === 'stock-transfers'), reversal = computed(() => props.kind === 'document-reversals')
const sourceTypes = computed(() => reversalTypes.filter(item => session.can(item.permission) && (!['price_adjustment', 'purchase_fee_doc'].includes(item.value) || session.can('cost:view') && session.can('goods_receipt:view'))))
const form = reactive({ fromWarehouseId: props.original?.fromWarehouseId ?? '', toWarehouseId: props.original?.toWarehouseId ?? '', warehouseId: props.original?.warehouseId ?? '', businessDate: props.original?.businessDate ?? today(), remark: props.original?.remark ?? '', reason: '', sourceType: props.presetSource?.sourceType ?? sourceTypes.value[0]?.value ?? '', sourceId: props.presetSource?.id ?? '' })
interface InputLine { skuId: string; unitId: string; qty: string; stockSelection?: StockSelection | null }
const lines = ref<InputLine[]>((props.original?.lines ?? []).map(line => ({ skuId: line.skuId, unitId: line.unitId ?? '', qty: line.qty ?? '1', stockSelection: line.stockSelection })))
const warehouses = ref<DataRow[]>([]), skus = ref<DataRow[]>([]), units = reactive<Record<string, SalesSkuUnit[]>>({})
const sources = ref<ReversalSource[]>([]), chosenSource = ref<ReversalSource | undefined>(props.presetSource), keyword = ref(''), sourceCursors = ref<(string | null)[]>([null]), sourceNext = ref<string | null>(null), sourceBusy = ref(false)
const sourceOptions = computed(() => chosenSource.value && !sources.value.some(item => item.id === chosenSource.value?.id) ? [chosenSource.value, ...sources.value] : sources.value)
const loading = ref(true), busy = ref(false), uncertain = ref(false), refreshRequired = ref(false), retryOriginal = ref(false), error = ref('')
const dependencies = ref<unknown>()
const selections = ref<WorkflowSelections>({}), workflowReady = ref(false)
const key = newIdempotencyKey()
let sourceRevision = 0
let submitted: Record<string, unknown> | undefined
function addLine(): void { lines.value.push({ skuId: '', unitId: '', qty: '1' }) }
async function chooseSku(line: InputLine, preserveUnit = false): Promise<void> {
  const skuId = line.skuId
  if (!preserveUnit) line.stockSelection = null
  if (!skuId) { line.unitId = ''; return }
  try {
    const details = await record('skus/' + skuId)
    if (line.skuId !== skuId) return
    units[skuId] = (details.units as SalesSkuUnit[] | undefined) ?? []
    if (!preserveUnit) line.unitId = units[skuId].find(unit => unit.isBase)?.unitId ?? ''
  } catch (cause: unknown) { if (!isCancelled(cause)) error.value = messageOf(cause) }
}
async function loadSources(): Promise<void> {
  if (!form.sourceType) { sources.value = []; return }
  const current = ++sourceRevision
  sourceBusy.value = true
  try {
    const page = await reversalSources({ sourceType: form.sourceType, keyword: keyword.value, cursor: sourceCursors.value.at(-1) ?? undefined, limit: 20 })
    if (current === sourceRevision) { sources.value = page.items; sourceNext.value = page.nextCursor }
  } catch (cause: unknown) { if (current === sourceRevision && !isCancelled(cause)) error.value = messageOf(cause) }
  finally { if (current === sourceRevision) sourceBusy.value = false }
}
function searchSources(): void { sourceCursors.value = [null]; void loadSources() }
function pageSources(forward: boolean): void { if (forward && sourceNext.value) sourceCursors.value.push(sourceNext.value); else if (!forward) sourceCursors.value.pop(); void loadSources() }
function selectSource(): void { chosenSource.value = sources.value.find(item => item.id === form.sourceId) ?? chosenSource.value }
watch(() => form.sourceType, () => { form.sourceId = ''; chosenSource.value = undefined; sources.value = []; sourceNext.value = null; keyword.value = ''; searchSources() })
watch(() => transfer.value ? form.fromWarehouseId : form.warehouseId, () => {
  lines.value.forEach(line => { line.stockSelection = null })
})
function payload(): Record<string, unknown> {
  const common = { businessDate: form.businessDate, version: props.original?.version }
  if (reversal.value) return { ...common, sourceType: form.sourceType, sourceId: form.sourceId, reason: form.reason, ...(Object.keys(selections.value).length ? { workflowAssignees: selections.value } : {}) }
  if (transfer.value) return { ...common, fromWarehouseId: form.fromWarehouseId, toWarehouseId: form.toWarehouseId, remark: form.remark, lines: lines.value.map(line => ({ skuId: line.skuId, unitId: line.unitId, qty: line.qty, stockSelection: line.stockSelection ?? undefined })) }
  return { ...common, warehouseId: form.warehouseId, countMode: 'watermark', remark: form.remark, lines: lines.value.map(line => ({ skuId: line.skuId, stockSelection: line.stockSelection ?? undefined })) }
}
async function save(): Promise<void> {
  if (busy.value || uncertain.value || refreshRequired.value || reversal.value && (!form.sourceId || !workflowReady.value)) return
  busy.value = true; error.value = ''; dependencies.value = undefined; submitted ??= payload()
  try { emit('saved', await saveStock(props.kind, submitted, props.original?.id, props.original ? undefined : key)) }
  catch (cause: unknown) {
    if (!isCancelled(cause)) {
      error.value = messageOf(cause)
      if (cause instanceof ApiError) {
        dependencies.value = cause.code === 'DEPENDENCY_EXISTS' ? cause.details : undefined
        const unknown = cause.status === 0 || cause.code === 'REQUEST_IN_PROGRESS'
        uncertain.value = !props.original && unknown
        refreshRequired.value = cause.code === 'VERSION_CONFLICT' || Boolean(props.original && unknown)
        if (!unknown) { submitted = undefined; retryOriginal.value = false }
      }
    }
  } finally { busy.value = false }
}
async function verify(): Promise<void> {
  if (busy.value) return
  busy.value = true
  dependencies.value = undefined
  try { const result = await stockResult(spec.value.permission + '.create', key); if (result.status === 'succeeded') emit('saved', result.response) }
  catch (cause: unknown) { if (!isCancelled(cause)) { error.value = messageOf(cause); if (cause instanceof ApiError && cause.status === 404) { uncertain.value = false; retryOriginal.value = true } } }
  finally { busy.value = false }
}
onMounted(async () => {
  try {
    if (reversal.value) await loadSources()
    else {
      const refs = await Promise.all([session.can('warehouse:view') ? allRecords('warehouses', true) : Promise.resolve([]), session.can('sku:view') ? allRecords('skus') : Promise.resolve([])])
      warehouses.value = refs[0].filter(item => item.status === 'active'); skus.value = refs[1].filter(item => item.status === 'active')
      if (!lines.value.length) addLine()
      if (transfer.value) await Promise.all(lines.value.filter(line => line.skuId).map(line => chooseSku(line, true)))
    }
  } catch (cause: unknown) { if (!isCancelled(cause)) error.value = messageOf(cause) }
  finally { loading.value = false }
})
onUnmounted(() => { sourceRevision++ })
</script>
<template>
  <el-dialog :model-value="true" :title="(original ? '编辑' : '新建') + spec.singular" width="min(1000px, 94vw)" :close-on-click-modal="false" :show-close="!busy" @close="emit('close')">
    <p v-if="loading">正在加载资料…</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <DependencyList :details="dependencies" />
    <p v-if="uncertain">结果确认中，请先查询原请求结果。</p>
    <p v-if="refreshRequired">修改结果或版本已变化，请关闭表单并刷新单据后核对。</p>
    <p v-if="retryOriginal">尚未查到已提交结果，可使用原内容重试。</p>
    <fieldset class="form-fieldset" :disabled="busy || loading || uncertain || refreshRequired || retryOriginal">
      <el-form label-position="top"><div class="sales-form-grid">
        <el-form-item v-if="transfer" label="调出仓库" for="stock-from-warehouse"><el-select id="stock-from-warehouse" v-model="form.fromWarehouseId" filterable><el-option v-for="item in warehouses" :key="item.id" :value="item.id" :label="item.name" /></el-select></el-form-item>
        <el-form-item v-if="transfer" label="调入仓库" for="stock-to-warehouse"><el-select id="stock-to-warehouse" v-model="form.toWarehouseId" filterable><el-option v-for="item in warehouses" :key="item.id" :value="item.id" :label="item.name" /></el-select></el-form-item>
        <el-form-item v-if="!transfer && !reversal" label="盘点仓库" for="stock-warehouse"><el-select id="stock-warehouse" v-model="form.warehouseId" filterable><el-option v-for="item in warehouses" :key="item.id" :value="item.id" :label="item.name" /></el-select></el-form-item>
        <el-form-item label="业务日期" for="stock-date"><el-input id="stock-date" v-model="form.businessDate" type="date" /></el-form-item>
        <el-form-item v-if="reversal" label="原单类型" for="stock-source-type"><el-select id="stock-source-type" v-model="form.sourceType"><el-option v-for="item in sourceTypes" :key="item.value" :value="item.value" :label="item.label" /></el-select></el-form-item>
      </div>
      <template v-if="reversal">
        <WorkflowStarterSelector v-if="form.sourceId && form.businessDate" v-model="selections" business-type="document_reversal" :preview="{ sourceType: form.sourceType, sourceId: form.sourceId, businessDate: form.businessDate }" :disabled="busy || uncertain || retryOriginal" @ready="workflowReady = $event" />
        <p v-if="!sourceTypes.length">没有可查看的原单类型，请联系管理员分配原单查看权限。</p>
        <div class="filters"><el-input v-model="keyword" aria-label="冲销来源单号" placeholder="按原单号查找" /><el-button :loading="sourceBusy" @click="searchSources">查找原单</el-button></div>
        <el-form-item label="原单据" for="stock-source"><el-select id="stock-source" v-model="form.sourceId" :loading="sourceBusy" filterable placeholder="请选择已过账原单" @change="selectSource"><el-option v-for="item in sourceOptions" :key="item.id" :value="item.id" :label="item.documentNo + ' · ' + (item.businessDate ?? '')" /></el-select></el-form-item>
        <p v-if="!sourceBusy && !sources.length">没有符合条件的原单，已有未完成冲销的单据不会重复列出。</p>
        <div class="table-footer"><span>来源第 {{ sourceCursors.length }} 页</span><div><el-button :disabled="sourceBusy || sourceCursors.length === 1" @click="pageSources(false)">上一页来源</el-button><el-button :disabled="sourceBusy || !sourceNext" @click="pageSources(true)">下一页来源</el-button></div></div>
        <el-form-item label="冲销原因" for="stock-reason"><el-input id="stock-reason" v-model="form.reason" type="textarea" maxlength="200" /></el-form-item>
      </template>
      <template v-else>
        <p v-if="!transfer">实盘数量对应开始盘点时的时点；期间继续收发货，过账只调整盘点差额。</p>
        <div v-for="(line, index) in lines" :key="index" class="relation-row">
          <el-select v-model="line.skuId" :aria-label="'库存商品' + (index + 1)" filterable @change="chooseSku(line)"><el-option v-for="item in skus" :key="item.id" :value="item.id" :label="item.code + ' ' + item.name" /></el-select>
          <el-select v-if="transfer" v-model="line.unitId" :aria-label="'调拨单位' + (index + 1)"><el-option v-for="unit in units[line.skuId] ?? []" :key="unit.unitId" :value="unit.unitId" :label="unit.unitName" /></el-select>
          <el-input v-if="transfer" v-model="line.qty" :aria-label="'调拨数量' + (index + 1)" placeholder="数量" />
          <el-button :disabled="lines.length === 1" @click="lines.splice(index, 1)">移除</el-button>
          <StockSelectionFields
            v-if="line.skuId"
            v-model="line.stockSelection"
            :warehouse-id="transfer ? form.fromWarehouseId : form.warehouseId"
            :sku-id="line.skuId"
            :mode="transfer ? 'out' : 'freeze'"
          />
        </div>
        <el-button :disabled="lines.length >= 200" @click="addLine">添加商品</el-button>
        <el-form-item label="备注" for="stock-remark"><el-input id="stock-remark" v-model="form.remark" type="textarea" maxlength="500" /></el-form-item>
      </template></el-form>
    </fieldset>
    <template #footer><el-button :disabled="busy" @click="emit('close')">关闭</el-button><el-button v-if="uncertain" :loading="busy" type="primary" @click="verify">查询结果</el-button><el-button v-else :loading="busy" :disabled="loading || refreshRequired || reversal && (!form.sourceId || !workflowReady)" type="primary" @click="save">保存{{ spec.singular }}</el-button></template>
  </el-dialog>
</template>
