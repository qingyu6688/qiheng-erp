<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { productionIssue, productionIssues, productionOrder, productionOrders, saveProductionIssue, updateProductionIssue } from '../api/manufacturing'
import { errorText } from '../api/http'
import { resolveCode } from '../api/work'
import { usePage } from '../composables/usePage'
import { feedback } from '../composables/useScanner'
import { useTaskFlow } from '../composables/useTaskFlow'
import { useSession } from '../stores/session'
import type { Id } from '../types/api'
import type { MobileIssueLine, MobileProductionIssue, MobileProductionOrder, ProductionMaterial } from '../types/manufacturing'
import { identifiesMaterial, materialQuantity, scanIssueDetail } from '../utils/manufacturing'
import { quantity } from '../utils/quantity'
import ScanInput from './ScanInput.vue'
import WorkList from './WorkList.vue'

const session = useSession()
const currentOrder = ref<MobileProductionOrder>()
const document = ref<MobileProductionIssue>()
const keyword = ref('')
const warehouse = ref<Id>('')
const businessDate = ref(new Date(Date.now() + 8 * 3_600_000).toISOString().slice(0, 10))
const editor = ref(false)
const opening = ref(false)
const scannerBusy = ref(false)
const error = ref('')
const lines = ref<MobileIssueLine[]>([])
const active = ref<MobileIssueLine>()
const scan = ref('')
const scanInput = ref<InstanceType<typeof ScanInput>>()
const { busy, failure, success, run } = useTaskFlow()
const orders = usePage((cursor) => productionOrders(keyword.value.trim() || undefined, cursor))
const issues = usePage((cursor) => productionIssues(currentOrder.value!.id, cursor))
const status: Record<string, string> = { draft: '草稿', pending: '待审核', approved: '已审核', posted: '已过账', reversed: '已冲销', rejected: '已驳回', cancelled: '已取消' }
const enabled = ['released', 'in_progress', 'completed']
const orderRows = computed(() => orders.items.value.filter((row) => enabled.includes(row.status)).map((row) => ({ id: row.id, title: row.documentNo, subtitle: row.skuName ?? row.skuCode })))
const issueRows = computed(() => issues.items.value.map((row) => ({ id: row.id, title: row.documentNo, status: status[row.status], subtitle: row.businessDate })))
const materials = computed(() => (currentOrder.value?.materials ?? []).filter((row) => (row.issueWarehouseId || currentOrder.value?.issueWarehouseId) === warehouse.value))
const warehouseIds = computed(() => [...new Set([currentOrder.value?.issueWarehouseId, ...(currentOrder.value?.materials ?? []).map((row) => row.issueWarehouseId)].filter((id): id is Id => Boolean(id)))])
const warehouseLabels = computed(() => warehouseIds.value.map((id) => id === currentOrder.value?.issueWarehouseId
  ? currentOrder.value.issueWarehouseName ?? currentOrder.value.issueWarehouseCode ?? id : id))
const warehouseIndex = computed(() => Math.max(warehouseIds.value.indexOf(warehouse.value), 0))
const permit = (name: string): boolean => Boolean(session.profile?.permissions.includes(`mfg_issue:${name}`))
const allows = (action: string): boolean => permit(action) && Boolean(document.value?.allowedActions.includes(action))

onMounted(() => { void orders.load() })

async function openOrder(id: Id): Promise<void> {
  if (opening.value) return
  opening.value = true
  error.value = ''
  try {
    currentOrder.value = await productionOrder(id)
    warehouse.value = currentOrder.value.issueWarehouseId
    document.value = undefined
    await issues.load()
  } catch (cause: unknown) { error.value = errorText(cause) }
  finally { opening.value = false }
}

async function openIssue(id: Id): Promise<void> {
  if (opening.value) return
  opening.value = true
  error.value = ''
  try { document.value = await productionIssue(id) }
  catch (cause: unknown) { error.value = errorText(cause) }
  finally { opening.value = false }
}

function start(edit?: MobileProductionIssue): void {
  if (!currentOrder.value || busy.value) return
  document.value = edit
  warehouse.value = edit?.warehouseId ?? currentOrder.value.issueWarehouseId
  if (edit) businessDate.value = edit.businessDate
  lines.value = (edit?.lines ?? []).map((line) => ({ ...line, stockSelection: { ...line.stockSelection } }))
  active.value = lines.value[0]
  error.value = ''
  editor.value = true
  void nextTick(() => scanInput.value?.focusScanner())
}

function chooseWarehouse(index: string | number): void {
  const value = warehouseIds.value[Number(index)]
  if (!value || value === warehouse.value) return
  warehouse.value = value
  lines.value = []
  active.value = undefined
  void nextTick(() => scanInput.value?.focusScanner())
}

function selectMaterial(material: ProductionMaterial, sku = material.componentSkuId, separate = false): MobileIssueLine {
  let row = separate ? undefined : lines.value.find((line) => line.materialId === material.id && line.skuId === sku && !line.stockSelection?.serials?.length)
  if (!row) {
    const alternative = material.substitutes?.find(({ substituteSkuId }) => substituteSkuId === sku)
    row = { materialId: material.id, skuId: sku, skuName: alternative?.skuName ?? alternative?.skuCode ?? material.skuName ?? material.skuCode,
      qty: alternative || separate ? '' : materialQuantity(material) }
    lines.value = [...lines.value, row]
    row = lines.value.at(-1)!
  }
  active.value = row
  return row
}

function splitLine(line: MobileIssueLine): void {
  const material = materials.value.find((item) => item.id === line.materialId)
  if (material) selectMaterial(material, line.skuId, true)
}

async function scanCode(value: string): Promise<void> {
  if (scannerBusy.value || busy.value || !warehouse.value) return
  scannerBusy.value = true
  error.value = ''
  try {
    const match = await resolveCode(value, warehouse.value)
    if (match.type === 'sku') {
      const sku = match.skuId ?? match.id
      const candidates = materials.value.filter((row) => identifiesMaterial(row, sku))
      const selected = candidates.length === 1 ? candidates[0] : candidates.find(({ id }) => id === active.value?.materialId)
      if (!selected) throw new Error(candidates.length ? '请先点选要处理的工单物料' : '该商品不在当前仓库的工单物料中')
      selectMaterial(selected, sku)
    } else {
      if (!active.value) throw new Error('请先扫描商品或选择工单物料')
      active.value.stockSelection = scanIssueDetail(active.value.stockSelection ?? {}, match, active.value.skuId)
      if (active.value.stockSelection.serials?.length) active.value.qty = String(active.value.stockSelection.serials.length)
    }
    feedback(true)
  } catch (cause: unknown) { error.value = errorText(cause); feedback(false) }
  finally { scannerBusy.value = false; scan.value = ''; void nextTick(() => scanInput.value?.focusScanner()) }
}

function remove(index: number): void {
  if (active.value === lines.value[index]) active.value = undefined
  lines.value.splice(index, 1)
}

async function save(): Promise<void> {
  if (!currentOrder.value || !lines.value.length || !businessDate.value) return
  error.value = ''
  try {
    const body = { workOrderId: currentOrder.value.id, businessDate: businessDate.value, warehouseId: warehouse.value, direction: 'issue', version: document.value?.version,
      lines: lines.value.map(({ materialId, skuId, qty, stockSelection }) => ({ materialId, skuId, qty: quantity(qty), stockSelection })) }
    const id = document.value?.id
    const result = await run({ id, body }, (key) => saveProductionIssue(body, key, id), '领料草稿已保存')
    if (result) { document.value = result; editor.value = false }
  } catch (cause: unknown) { error.value = errorText(cause) }
}

async function transition(action: 'submit' | 'post'): Promise<void> {
  const item = document.value
  if (!item || !allows(action)) return
  const result = await run([item.id, item.version, action], (key) => updateProductionIssue(item.id, action, item.version, key), action === 'submit' ? '领料单已提交' : '领料已过账')
  if (result) document.value = result
}

function back(): void {
  error.value = ''
  if (editor.value) { editor.value = false; return }
  if (document.value) { document.value = undefined; void issues.load(); return }
  currentOrder.value = undefined
  void orders.load()
}
</script>

<template>
  <view class="panel">
    <view class="section-title"><text class="heading">生产领料</text><button v-if="currentOrder" class="secondary" :disabled="busy || opening" @click="back">返回</button></view>
    <text v-if="error || failure" class="error" role="alert">{{ error || failure }}</text>
    <text v-if="success" class="success" role="status">{{ success }}</text>
    <template v-if="!currentOrder">
      <text class="field-label">已下达的生产工单</text>
      <input v-model="keyword" class="field" aria-label="按工单号或产成品搜索" placeholder="输入工单号后回车" @confirm="orders.load()">
      <text v-if="orders.failure.value" class="error">{{ orders.failure.value }}</text>
      <WorkList :rows="orderRows" :more="Boolean(orders.cursor.value)" :busy="orders.busy.value || opening" @select="openOrder" @more="orders.load(true)" />
    </template>
    <template v-else-if="editor">
      <text class="subheading">{{ currentOrder.documentNo }}</text>
      <text class="field-label">领料仓库</text>
      <picker :range="warehouseLabels" :value="warehouseIndex" :disabled="busy || Boolean(document)" @change="chooseWarehouse($event.detail.value)"><view class="field">{{ warehouseLabels[warehouseIndex] }}</view></picker>
      <text class="field-label">业务日期</text>
      <picker mode="date" :value="businessDate" :disabled="busy" @change="businessDate = String($event.detail.value)"><view class="field">{{ businessDate }}</view></picker>
      <ScanInput ref="scanInput" v-model="scan" label="扫描商品、批次、库位或序列号" :disabled="busy || scannerBusy" pda-focus @scan="scanCode" />
      <text class="field-label">选择工单物料</text>
      <button v-for="item in materials" :key="item.id" class="secondary work-list-row" :disabled="busy || scannerBusy" @click="selectMaterial(item)">{{ item.skuName ?? item.skuCode ?? item.componentSkuId }} · 待领 {{ materialQuantity(item) }}</button>
      <view v-for="(line, index) in lines" :key="index" class="line-edit" :class="{ selected: line === active }">
        <button class="secondary" :disabled="busy" @click="active = line">{{ line.skuName ?? line.skuId }}{{ line === active ? ' · 当前扫码行' : '' }}</button>
        <text class="field-label">实际数量（基本单位）</text>
        <input v-model="line.qty" class="field" inputmode="decimal" aria-label="领料数量" :disabled="busy">
        <text v-if="line.stockSelection?.batchId" class="muted">批次 {{ line.stockSelection.batchId }}</text>
        <text v-if="line.stockSelection?.locationId" class="muted">库位 {{ line.stockSelection.locationId }}</text>
        <text v-if="line.stockSelection?.serials?.length" class="muted">序列号 {{ line.stockSelection.serials.join('、') }}</text>
        <button class="secondary" :disabled="busy" @click="remove(index)">移除明细</button>
        <button v-if="line === active" class="secondary" :disabled="busy || scannerBusy" @click="splitLine(line)">为此物料另加一行</button>
      </view>
      <button :disabled="!lines.length || busy || scannerBusy" @click="save">保存领料草稿</button>
    </template>
    <template v-else-if="document">
      <text class="subheading">{{ document.documentNo }} · {{ status[document.status] }}</text>
      <view v-for="(row, index) in document.lines ?? []" :key="index" class="line-edit"><text>{{ row.skuName ?? row.skuId }} · {{ row.qty }}</text></view>
      <button v-if="document.status === 'draft' && permit('update')" class="secondary" :disabled="busy" @click="start(document)">编辑并扫码</button>
      <button v-if="allows('submit')" :disabled="busy" @click="transition('submit')">提交领料</button>
      <button v-if="allows('post')" :disabled="busy" @click="transition('post')">领料过账</button>
    </template>
    <template v-else>
      <text class="subheading">{{ currentOrder.documentNo }} · {{ currentOrder.skuName ?? currentOrder.skuCode }}</text>
      <button v-if="permit('create') && enabled.includes(currentOrder.status)" :disabled="busy" @click="start()">新建扫码领料</button>
      <text v-if="issues.failure.value" class="error">{{ issues.failure.value }}</text>
      <WorkList :rows="issueRows" :more="Boolean(issues.cursor.value)" :busy="issues.busy.value || opening" @select="openIssue" @more="issues.load(true)" />
    </template>
  </view>
</template>
