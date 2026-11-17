<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { allRecords } from '../api/admin'
import { batchTrace, customerTrace, serialTrace, trackedBatches } from '../api/inventory-tracking'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'
import type { DataRow } from '../types/admin'
import type { BatchBalance, StockMovementTrace, TrackedBatch } from '../types/inventory-tracking'

const session = useSessionStore()
const route = useRoute()
const active = ref('')
const tabs = computed(() => [
  { key: 'batch', label: '批次', allowed: session.can('batch:view') },
  { key: 'serial', label: '序列号', allowed: session.can('serial:trace') },
  { key: 'customer', label: '客户反查', allowed: session.can('inventory_trace:customer') },
].filter((tab) => tab.allowed))
const keyword = ref('')
const batchSku = ref('')
const within = ref<number>()
const sku = ref('')
const serialNo = ref('')
const customer = ref('')
const warehouses = ref<DataRow[]>([])
const warehouse = ref('')
const partners = ref<DataRow[]>([])
const skus = ref<DataRow[]>([])
const batches = ref<TrackedBatch[]>([])
const movements = ref<StockMovementTrace[]>([])
const balances = ref<BatchBalance[]>([])
const detail = ref('')
const batchCursor = ref<string | null>(null)
const movementCursor = ref<string | null>(null)
const selectedBatch = ref('')
const busy = ref(false)
const error = ref('')
let requestRevision = 0
let scopeRevision = 0

const movementNames: Record<string, string> = {
  opening_in: '期初库存', purchase_in: '采购收货', purchase_return_out: '采购退货',
  sales_out: '销售发货', sales_return_in: '销售退货', sales_reject_in: '拒收退回',
  transfer_out: '调拨出库', transfer_in: '调拨入库', count_gain: '盘盈', count_loss: '盘亏',
  location_move_out: '库位移出', location_move_in: '库位移入',
  adjust_in: '调整入库', adjust_out: '调整出库', block: '冻结', unblock: '解冻',
  scrap: '报废', stock_misc_in: '其他入库', stock_misc_out: '其他出库', stock_misc_return_in: '借出归还',
  mfg_issue_out: '生产领料', mfg_return_in: '生产退料', mfg_receipt_in: '完工入库',
}
const historyTitle = computed(() => detail.value || (active.value === 'customer' ? '签收记录' : '库存流水'))

function queryId(value: unknown): string {
  return typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value) ? value : ''
}

watch(() => [session.companyId, route.query.warehouseId, route.query.skuId, route.query.batchId], async () => {
  requestRevision++
  const revision = ++scopeRevision
  active.value = tabs.value[0]?.key ?? ''
  keyword.value = ''; within.value = undefined; warehouse.value = queryId(route.query.warehouseId)
  batchSku.value = queryId(route.query.skuId)
  const sourceBatch = queryId(route.query.batchId)
  sku.value = ''; serialNo.value = ''; customer.value = ''
  warehouses.value = []; partners.value = []; skus.value = []
  batches.value = []; batchCursor.value = null
  movements.value = []; balances.value = []; movementCursor.value = null
  detail.value = ''; selectedBatch.value = ''; error.value = ''; busy.value = false
  if (!session.companyId) return
  const [stock, customers, products] = await Promise.allSettled([
    session.can('warehouse:view') ? allRecords('warehouses', true) : Promise.resolve([]),
    session.can('partner:view') ? allRecords('partners') : Promise.resolve([]),
    session.can('sku:view') ? allRecords('skus') : Promise.resolve([]),
  ])
  if (revision !== scopeRevision) return
  if (stock.status === 'fulfilled') warehouses.value = stock.value
  if (customers.status === 'fulfilled') partners.value = customers.value
  if (products.status === 'fulfilled') skus.value = products.value
  if (active.value === 'batch') {
    await loadBatches()
    if (revision === scopeRevision && sourceBatch && session.can('batch:trace')) await loadHistory(sourceBatch)
  }
}, { immediate: true })

watch(active, (tab) => {
  requestRevision++
  busy.value = false; error.value = ''; detail.value = ''; selectedBatch.value = ''
  movements.value = []; balances.value = []; movementCursor.value = null
  if (tab === 'batch' && batches.value.length === 0) void loadBatches()
})

async function search(run: (revision: number) => Promise<void>): Promise<void> {
  const revision = ++requestRevision
  busy.value = true
  error.value = ''
  try {
    await run(revision)
  } catch (cause: unknown) {
    if (revision === requestRevision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (revision === requestRevision) busy.value = false
  }
}

async function loadBatches(cursor?: string): Promise<void> {
  await search(async (revision) => {
    const result = await trackedBatches({
      keyword: keyword.value, warehouseId: warehouse.value || undefined, skuId: batchSku.value || undefined, expiringWithinDays: within.value, cursor,
    })
    if (revision !== requestRevision) return
    batches.value = cursor ? [...batches.value, ...result.items] : result.items
    batchCursor.value = result.nextCursor
  })
}

async function loadHistory(id?: string, cursor?: string): Promise<void> {
  const batchId = id ?? selectedBatch.value
  await search(async (revision) => {
    if (active.value === 'batch') {
      const result = await batchTrace(batchId, cursor)
      if (revision !== requestRevision) return
      selectedBatch.value = batchId
      detail.value = result.batch.batchNo + ' · ' + result.batch.skuName
      balances.value = result.balances
      movements.value = cursor ? [...movements.value, ...result.items] : result.items
      movementCursor.value = result.nextCursor
      return
    }
    if (active.value === 'serial') {
      const result = await serialTrace(sku.value, serialNo.value.trim(), cursor)
      if (revision !== requestRevision) return
      detail.value = '序列号 ' + result.serial.serialNo
      movements.value = cursor ? [...movements.value, ...result.items] : result.items
      movementCursor.value = result.nextCursor
      return
    }
    const result = await customerTrace(customer.value, sku.value || undefined, cursor)
    if (revision !== requestRevision) return
    movements.value = cursor ? [...movements.value, ...result.items] : result.items
    movementCursor.value = result.nextCursor
  })
}

function batchStatus(batch: TrackedBatch): string {
  if (batch.expiryState === 'expired' || batch.status === 'expired') return '已过期'
  if (batch.status === 'frozen') return '冻结'
  return batch.expiryState === 'near_expiry' ? '临期' : '有效'
}
</script>

<template>
  <section class="workspace-page inventory-trace">
    <header class="page-head">
      <h1>批次与序列号</h1>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-tabs v-model="active">
      <el-tab-pane v-for="tab in tabs" :key="tab.key" :name="tab.key" :label="tab.label" />
    </el-tabs>
    <template v-if="active === 'batch'">
      <el-form inline class="inventory-trace-filters" @submit.prevent="loadBatches()">
        <el-form-item label="批次或商品"><el-input v-model="keyword" aria-label="批次或商品" clearable maxlength="100" /></el-form-item>
        <el-form-item label="仓库">
          <el-select v-model="warehouse" aria-label="仓库" clearable filterable>
            <el-option v-for="item in warehouses" :key="item.id" :label="String(item.name)" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="到期天数"><el-input-number v-model="within" aria-label="到期天数" :min="0" :max="3650" /></el-form-item>
        <el-button native-type="submit" type="primary" :loading="busy">查询批次</el-button>
      </el-form>
      <el-table :data="batches" row-key="id" empty-text="暂无批次">
        <el-table-column label="批次" prop="batchNo" min-width="145" />
        <el-table-column label="商品" prop="skuName" min-width="145" />
        <el-table-column label="生产日期" prop="productionDate" width="120" />
        <el-table-column label="到期日" prop="expiryDate" width="120" />
        <el-table-column label="效期" width="90">
          <template #default="{ row }">
            <el-tag :type="row.expiryState === 'expired' ? 'danger' : row.expiryState === 'near_expiry' ? 'warning' : 'info'">{{ batchStatus(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="在库位置" min-width="190">
          <template #default="{ row }">
            <div v-for="balance in (row.balances ?? []).slice(0, 3)" :key="balance.warehouseId + ':' + balance.locationKey">
              {{ balance.warehouseName }} {{ balance.locationCode }} · 可用 {{ balance.availableQty }}
            </div>
            <small v-if="(row.balances ?? []).length > 3">其余位置见追溯详情</small>
          </template>
        </el-table-column>
        <el-table-column v-if="session.can('batch:trace')" label="操作" width="106">
          <template #default="{ row }"><el-button text type="primary" @click="loadHistory(row.id)">查看追溯</el-button></template>
        </el-table-column>
      </el-table>
      <el-button v-if="batchCursor" :loading="busy" @click="loadBatches(batchCursor)">更多批次</el-button>
    </template>
    <template v-else>
      <el-form v-if="active" inline class="inventory-trace-filters" @submit.prevent="loadHistory()">
        <el-form-item v-if="active === 'customer'" label="客户">
          <el-select v-if="partners.length" v-model="customer" aria-label="客户" clearable filterable>
            <el-option v-for="item in partners" :key="item.id" :value="item.id" :label="String(item.name)" />
          </el-select>
          <el-input v-else v-model="customer" aria-label="客户编号" placeholder="客户编号" />
        </el-form-item>
        <el-form-item label="商品">
          <el-select v-if="skus.length" v-model="sku" aria-label="商品" clearable filterable>
            <el-option v-for="item in skus" :key="item.id" :value="item.id" :label="String(item.code) + ' ' + String(item.name)" />
          </el-select>
          <el-input v-else v-model="sku" aria-label="商品编号" placeholder="商品编号" />
        </el-form-item>
        <el-form-item v-if="active === 'serial'" label="序列号">
          <el-input v-model="serialNo" aria-label="序列号" maxlength="100" />
        </el-form-item>
        <el-button native-type="submit" type="primary" :loading="busy" :disabled="active === 'serial' ? !sku || !serialNo.trim() : !customer">查询</el-button>
      </el-form>
    </template>
    <section v-if="detail || active !== 'batch'" class="inventory-trace-history">
      <h2>{{ historyTitle }}</h2>
      <el-table v-if="active === 'batch'" :data="balances" empty-text="该批次已无在库数量">
        <el-table-column label="仓库" prop="warehouseName" />
        <el-table-column label="库位" prop="locationCode" />
        <el-table-column label="在库" prop="onHandQty" />
        <el-table-column label="冻结" prop="blockedQty" />
        <el-table-column label="已拣货" prop="reservedQty" />
        <el-table-column label="可用" prop="availableQty" />
      </el-table>
      <el-table :data="movements" row-key="id" empty-text="暂无追溯记录">
        <el-table-column label="日期" prop="businessDate" width="118" />
        <el-table-column :label="active === 'customer' ? '签收数量' : '库存变动'" min-width="145">
          <template #default="{ row }">{{ active === 'customer' ? row.confirmedQty : (row.postingAction === 'reverse' ? '冲销 ' : '') + (movementNames[row.movementType] ?? row.movementType) + ' ' + row.onHandDelta }}</template>
        </el-table-column>
        <el-table-column label="批次及位置" min-width="165">
          <template #default="{ row }">{{ row.batchNo || row.skuName }} · {{ row.warehouseName }} {{ row.locationCode }}</template>
        </el-table-column>
        <el-table-column label="单据与往来单位" min-width="180">
          <template #default="{ row }">
            <div>{{ row.receiptNo || row.shipmentNo || row.purchaseReturnNo || row.sourceId || '—' }}</div>
            {{ row.supplierName || row.customerName || '' }}
          </template>
        </el-table-column>
        <el-table-column label="检验" min-width="140">
          <template #default="{ row }">
            <div v-for="inspection in row.inspections ?? []" :key="inspection.id">
              {{ inspection.inspectionNo }} · {{ ({ passed: '合格', failed: '不合格', partial: '部分合格', pending: '待检' } as Record<string, string>)[inspection.result] ?? inspection.result }}
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="session.can('serial:trace')" label="序列号" min-width="140">
          <template #default="{ row }"><div class="trace-serials">{{ row.serials.join('、') }}</div></template>
        </el-table-column>
      </el-table>
      <el-button v-if="movementCursor" :loading="busy" @click="loadHistory(undefined, movementCursor)">更多记录</el-button>
    </section>
  </section>
</template>

<style scoped>
.inventory-trace { display: grid; gap: 14px; }
.inventory-trace h1, .inventory-trace h2 { margin: 0; }
.inventory-trace-filters :deep(.el-select) { width: 180px; }
.inventory-trace-history { display: grid; gap: 12px; }
.trace-serials { max-height: 85px; overflow: auto; overflow-wrap: anywhere; }
</style>
