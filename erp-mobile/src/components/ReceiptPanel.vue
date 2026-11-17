<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import ScanInput from './ScanInput.vue'
import WorkList from './WorkList.vue'
import { errorText } from '../api/http'
import { browse, command, goodsReceipt, idPart, read, resolveCode, save, warehouseChoices } from '../api/work'
import { usePage } from '../composables/usePage'
import { feedback } from '../composables/useScanner'
import { useTaskFlow } from '../composables/useTaskFlow'
import { useSession } from '../stores/session'
import type { Id, Page } from '../types/api'
import type { Receipt, StockLine, Warehouse, WorkRow } from '../types/work'
import { quantity, serialLines } from '../utils/quantity'
import { lineForScan, listRow, statusTitle } from '../utils/work'

interface PurchaseLine extends StockLine {
  orderNo: string
  supplierId: Id
  warehouseId: Id
  unitId: Id
  unitName: string
}

interface DraftLine {
  qty: string
  locationCode: string
  batchNo: string
  productionDate: string
  expiryDate: string
  serialCodes: string
}

const session = useSession()
const receipt = ref<Receipt>()
const pendingSource = ref<PurchaseLine>()
const candidates = ref<PurchaseLine[]>([])
const creating = ref(false)
const warehouse = ref<Warehouse>()
const warehouses = ref<Warehouse[]>([])
const active = ref<Id>()
const error = ref('')
const scanned = ref('')
const drafts = reactive<Record<Id, DraftLine>>({})
const { items, cursor, busy, failure, load } = usePage((after) => browse<WorkRow>('/goods-receipts', {}, after))
const { busy: sending, failure: commandError, success, run } = useTaskFlow()
const mayCreate = computed(() => ['goods_receipt:create', 'purchase_order:view']
  .every((permission) => session.profile?.permissions.includes(permission)))

onMounted(() => load())

function newLine(): DraftLine {
  return { qty: '', locationCode: '', batchNo: '', productionDate: '', expiryDate: '', serialCodes: '' }
}

async function open(id: Id): Promise<void> {
  error.value = ''
  try {
    const value = await goodsReceipt(id)
    for (const line of value.lines) {
      drafts[line.id] = {
        ...newLine(), qty: line.qty ?? line.quantity ?? '', batchNo: line.stockSelection?.batchNo ?? '',
        productionDate: line.stockSelection?.productionDate ?? '', expiryDate: line.stockSelection?.expiryDate ?? '',
        serialCodes: (line.stockSelection?.serials ?? line.stockSelection?.serialNos ?? []).join('\n'),
      }
    }
    receipt.value = value
    active.value = undefined
  } catch (cause: unknown) {
    error.value = errorText(cause)
  }
}

async function start(): Promise<void> {
  error.value = ''
  try {
    warehouses.value = (await warehouseChoices()).items
    if (!warehouses.value.length) throw new Error('当前公司没有可用仓库')
    warehouse.value = undefined
    candidates.value = []
    pendingSource.value = undefined
    creating.value = true
  } catch (cause: unknown) {
    error.value = errorText(cause)
  }
}

async function warehouseChanged(event: { detail: { value: string | number } }): Promise<void> {
  warehouse.value = warehouses.value[Number(event.detail.value)]
  candidates.value = []
  pendingSource.value = undefined
  error.value = ''
  const chosen = warehouse.value?.id
  if (!chosen) return
  try {
    const page = await read<Page<PurchaseLine>>('/purchase-order-lines', { warehouseId: chosen, limit: 200 })
    if (warehouse.value?.id === chosen) candidates.value = page.items
  } catch (cause: unknown) {
    if (warehouse.value?.id === chosen) error.value = errorText(cause)
  }
}

function useSource(source: PurchaseLine): void {
  pendingSource.value = source
  drafts[source.id] = newLine()
  active.value = source.id
}

async function scan(code: string): Promise<void> {
  error.value = ''
  try {
    const match = await resolveCode(code, receipt.value?.warehouseId ?? warehouse.value?.id)
    if (receipt.value) active.value = lineForScan(receipt.value.lines, match).id
    else useSource(lineForScan(candidates.value, match))
    feedback(true)
  } catch (cause: unknown) {
    error.value = errorText(cause)
    feedback(false)
  } finally {
    scanned.value = ''
  }
}

async function selection(
  draft: DraftLine, warehouseId: Id, skuCode?: string, originalLocation?: Id, originalBatch?: Id, originalBatchNo?: string,
): Promise<Record<string, unknown> | undefined> {
  let locationId = originalLocation
  if (draft.locationCode.trim()) {
    const match = await resolveCode(draft.locationCode, warehouseId)
    if (match.type !== 'location' || (match.warehouseId && match.warehouseId !== warehouseId))
      throw new Error('该编码不是当前仓库的库位')
    locationId = match.id
  }
  let batchNo = draft.batchNo.trim()
  if (batchNo.startsWith('B:')) {
    const [scannedSku, scannedBatch] = batchNo.slice(2).split('|')
    if ((skuCode && scannedSku !== skuCode) || !scannedBatch) throw new Error('批次标签与商品不匹配')
    batchNo = scannedBatch
  }
  const serials = serialLines(draft.serialCodes)
  const batchId = batchNo === originalBatchNo ? originalBatch : undefined
  if (!batchNo && !batchId && !locationId && !draft.productionDate && !draft.expiryDate && !serials.length) return
  return {
    locationId, batchId, batchNo: batchNo || undefined, serials,
    productionDate: draft.productionDate || undefined, expiryDate: draft.expiryDate || undefined,
  }
}

function today(): string {
  return new Date(Date.now() + 8 * 3_600_000).toISOString().slice(0, 10)
}

async function persist(): Promise<void> {
  error.value = ''
  const current = receipt.value
  const source = pendingSource.value
  if ((!current && !source) || sending.value) return
  try {
    if (current) {
      const lines = await Promise.all(current.lines.map(async (line) => ({
        orderLineId: line.orderLineId, unitId: line.unitId, qty: quantity(drafts[line.id]!.qty),
        stockSelection: await selection(drafts[line.id]!, current.warehouseId, line.skuCode,
          line.stockSelection?.locationId, line.stockSelection?.batchId, line.stockSelection?.batchNo),
      })))
      const body = {
        supplierId: current.supplierId, warehouseId: current.warehouseId, businessDate: current.businessDate,
        remark: current.remark, buyerUserId: current.buyerUserId, version: current.version, lines,
      }
      const saved = await run(body, () => save<Receipt>('/goods-receipts/' + idPart(current.id), body), '已保存收货明细')
      if (saved) await open(saved.id)
      feedback(Boolean(saved))
    } else if (source) {
      const draft = drafts[source.id]!
      const body = {
        supplierId: source.supplierId, warehouseId: source.warehouseId, businessDate: today(),
        lines: [{
          orderLineId: source.id, unitId: source.unitId, qty: quantity(draft.qty),
          stockSelection: await selection(draft, source.warehouseId, source.skuCode),
        }],
      }
      const saved = await run(body, (key) => command<Receipt>('/goods-receipts', body, key), '已新建采购收货单')
      if (saved) {
        pendingSource.value = undefined
        creating.value = false
        await open(saved.id)
      }
      feedback(Boolean(saved))
    }
  } catch (cause: unknown) {
    error.value = errorText(cause)
    feedback(false)
  }
}

async function transition(action: 'submit' | 'post'): Promise<void> {
  const current = receipt.value
  if (!current) return
  const path = `/goods-receipts/${idPart(current.id)}/${action}`
  const body = { version: current.version }
  const recorded = await run([path, body], async (key) => {
    await command(path, body, key)
    await open(current.id)
    return true
  }, action === 'post' ? '采购收货已过账' : '采购收货已提交')
  feedback(Boolean(recorded))
}
</script>

<template>
  <view class="panel">
    <view class="section-title">
      <text class="heading">采购收货</text>
      <button v-if="receipt || creating" class="compact secondary" :disabled="sending"
        @click="receipt = undefined; creating = false; pendingSource = undefined; active = undefined; load()">返回</button>
      <button v-else-if="mayCreate" class="compact secondary" @click="start">扫码新建</button>
    </view>
    <text v-if="failure || commandError || error" class="error" role="alert">{{ error || commandError || failure }}</text>
    <text v-if="success" class="success">{{ success }}</text>
    <template v-if="creating">
      <picker :range="warehouses" range-key="name" :disabled="sending" @change="warehouseChanged">
        <view class="field">{{ warehouse?.name ?? '请选择收货仓库' }}</view>
      </picker>
      <ScanInput v-if="warehouse" v-model="scanned" label="扫描待收货商品" :disabled="sending" @scan="scan" />
      <text v-if="pendingSource">{{ pendingSource.orderNo }} · {{ pendingSource.skuName }} · {{ pendingSource.unitName }}</text>
      <template v-else>
        <button v-for="entry in candidates" :key="entry.id" class="secondary work-list-row"
          @click="useSource(entry)">{{ entry.orderNo }} · {{ entry.skuName }}</button>
      </template>
    </template>
    <template v-else-if="receipt">
      <text class="subheading">{{ receipt.documentNo }} · {{ statusTitle(receipt.status) }}</text>
      <ScanInput v-if="receipt.allowedActions?.includes('update')" v-model="scanned"
        label="扫描商品选行" :disabled="sending" @scan="scan" />
      <button v-for="line in receipt.lines" :key="line.id" class="secondary work-list-row"
        :disabled="sending || !receipt.allowedActions?.includes('update')" @click="active = line.id">
        {{ line.skuName }} · {{ drafts[line.id]?.qty }}
      </button>
      <button v-if="receipt.allowedActions?.includes('submit') && !active" :disabled="sending" @click="transition('submit')">提交收货</button>
      <wd-button v-if="receipt.allowedActions?.includes('post')" block :loading="sending" @click="transition('post')">过账收货</wd-button>
    </template>
    <WorkList v-else :rows="items.map(listRow)" :more="!!cursor" :busy="busy" @select="open" @more="load(true)" />
    <template v-if="active && drafts[active]">
      <input v-model="drafts[active]!.qty" class="field" type="digit" placeholder="实收数量" aria-label="实收数量" :disabled="sending">
      <ScanInput v-model="drafts[active]!.locationCode" label="收货库位（选填）" :disabled="sending" />
      <ScanInput v-model="drafts[active]!.batchNo" label="批次（按商品要求）" :disabled="sending" />
      <input v-model="drafts[active]!.productionDate" class="field" placeholder="生产日期 YYYY-MM-DD"
        aria-label="生产日期" :disabled="sending">
      <input v-model="drafts[active]!.expiryDate" class="field" placeholder="失效日期 YYYY-MM-DD"
        aria-label="失效日期" :disabled="sending">
      <textarea v-model="drafts[active]!.serialCodes" class="field field-text" placeholder="序列号，每行一个"
        aria-label="序列号" :disabled="sending" auto-height />
      <wd-button block :loading="sending" @click="persist">保存收货明细</wd-button>
    </template>
  </view>
</template>
