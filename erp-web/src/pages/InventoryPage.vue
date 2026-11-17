<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { inventoryBalances } from '../api/inventory'
import { allRecords } from '../api/admin'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'
import { demandExceedsAvailable } from '../types/inventory'
import type { InventoryBalance, InventorySnapshot } from '../types/inventory'
import type { DataRow } from '../types/admin'
import { formatDateTime, formatMoney, formatPrice, formatQuantity } from '../utils/format'

const session = useSessionStore(),
  route = useRoute(),
  router = useRouter()
const snapshot = ref<InventorySnapshot>(),
  warehouses = ref<DataRow[]>([])
const error = ref(''),
  optionsError = ref(''),
  busy = ref(false),
  cursors = ref([''])
const filter = reactive({
  keyword: '',
  warehouseId: '',
  skuId: '',
  hideZero: false,
  shortageOnly: false,
})
const canCost = computed(() => session.can('cost:view'))
let revision = 0,
  optionsRevision = 0,
  internalNavigation = false
let controller = new AbortController()
function query(): Record<string, unknown> {
  return {
    keyword: filter.keyword.trim() || undefined,
    warehouseId: filter.warehouseId || undefined,
    skuId: filter.skuId || undefined,
    hideZero: filter.hideZero || undefined,
    shortageOnly: filter.shortageOnly || undefined,
    cursor: cursors.value.at(-1) || undefined,
    limit: 20,
  }
}
async function load(): Promise<void> {
  const current = ++revision
  controller.abort()
  controller = new AbortController()
  busy.value = true
  error.value = ''
  try {
    const result = await inventoryBalances(query(), controller.signal)
    if (current === revision) snapshot.value = result
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) busy.value = false
  }
}
async function loadWarehouses(): Promise<void> {
  const current = ++optionsRevision
  optionsError.value = ''
  if (!session.can('warehouse:view')) {
    warehouses.value = []
    return
  }
  try {
    const result = await allRecords('warehouses', true)
    if (current === optionsRevision) warehouses.value = result
  } catch (cause: unknown) {
    if (current === optionsRevision && !isCancelled(cause))
      optionsError.value = '仓库选项加载失败：' + messageOf(cause)
  }
}
async function saveQuery(): Promise<void> {
  const values = Object.fromEntries(
    Object.entries(query())
      .filter(([key, value]) => key !== 'limit' && value !== undefined)
      .map(([key, value]) => [key, String(value)]),
  )
  internalNavigation = true
  try {
    await router.replace({ path: '/inventory-balances', query: values })
  } finally {
    internalNavigation = false
  }
  await load()
}
function search(): void {
  cursors.value = ['']
  snapshot.value = undefined
  void saveQuery()
}
function paginate(forward: boolean): void {
  if (busy.value) return
  if (forward && snapshot.value?.nextCursor) cursors.value.push(snapshot.value.nextCursor)
  else if (!forward && cursors.value.length > 1) cursors.value.pop()
  else return
  void saveQuery()
}
function ledger(row: InventoryBalance): void {
  void router.push({
    path: '/reports/inventory-flow',
    query: { warehouseId: row.warehouseId, skuId: row.skuId, detail: 'true' },
  })
}
function rowKey(row: InventoryBalance): string {
  return row.warehouseId + ':' + row.skuId
}
function initialize(): void {
  filter.keyword = typeof route.query.keyword === 'string' ? route.query.keyword : ''
  filter.skuId =
    typeof route.query.skuId === 'string' && /^[1-9][0-9]*$/.test(route.query.skuId)
      ? route.query.skuId
      : ''
  filter.warehouseId = typeof route.query.warehouseId === 'string' ? route.query.warehouseId : ''
  filter.hideZero = route.query.hideZero === 'true'
  filter.shortageOnly = route.query.shortageOnly === 'true'
  cursors.value = ['']
  snapshot.value = undefined
  void load()
}
watch(
  () => session.companyId,
  () => {
    warehouses.value = []
    void loadWarehouses()
    initialize()
  },
  { immediate: true },
)
watch(
  () => route.query,
  () => {
    if (!internalNavigation) initialize()
  },
)
onBeforeUnmount(() => {
  ++revision
  ++optionsRevision
  controller.abort()
})
function refresh(): void {
  void load()
  void loadWarehouses()
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>实时库存</h1>
      <p v-if="snapshot">实时数据 · {{ formatDateTime(snapshot.asOf) }} 刷新</p>
    </div>
    <div class="sales-actions">
      <router-link
        v-if="session.can('wms_location:view') && session.can('warehouse:view')"
        class="text-link"
        to="/warehouse-locations?view=balances"
        >按库位</router-link
      >
      <router-link v-if="session.can('batch:view')" class="text-link" to="/inventory-trace"
        >按批次</router-link
      >
      <el-button :loading="busy" @click="refresh">刷新</el-button
      ><router-link
        v-if="session.can('report:inventory_summary')"
        class="text-link"
        to="/reports/inventory-summary"
        >库存汇总</router-link
      >
    </div>
  </div>
  <div v-if="snapshot" class="inventory-statistics" aria-label="库存汇总">
    <div v-if="canCost" class="panel inventory-statistic">
      <span>在库金额（成本）</span><strong>{{ formatMoney(snapshot.summary.costAmount) }}</strong>
    </div>
    <router-link
      v-if="session.can('report:unsigned_shipments')"
      class="panel inventory-statistic"
      to="/reports/unsigned-shipments"
      ><span>发出未签收{{ canCost ? '（成本）' : '' }}</span
      ><strong
        ><template v-if="canCost"
          >{{ formatMoney(snapshot.summary.unreceivedCostAmount) }}
          <small>· {{ snapshot.summary.unreceivedCount ?? 0 }} 张</small></template
        ><template v-else
          >{{ snapshot.summary.unreceivedCount ?? 0 }} <small>张</small></template
        ></strong
      ></router-link
    >
    <div class="panel inventory-statistic">
      <span>冻结待检</span
      ><strong>{{ formatQuantity(snapshot.summary.blockedQty) }} <small>基础单位</small></strong>
    </div>
    <div class="panel inventory-statistic">
      <span>待审需求超过可用</span
      ><strong class="inventory-shortage"
        >{{ snapshot.summary.shortageCount }} <small>个 SKU</small></strong
      >
    </div>
  </div>
  <section class="panel table-panel" aria-label="实时库存列表" :aria-busy="busy">
    <form class="filters" @submit.prevent="search">
      <div v-if="session.can('warehouse:view')" class="filter-field">
        <label for="inventory-warehouse">仓库</label
        ><el-select
          id="inventory-warehouse"
          v-model="filter.warehouseId"
          clearable
          filterable
          placeholder="全部仓库"
          ><el-option
            v-for="warehouse in warehouses"
            :key="warehouse.id"
            :value="warehouse.id"
            :label="[warehouse.code, warehouse.name].filter(Boolean).join(' · ')"
        /></el-select>
      </div>
      <div class="filter-field">
        <label for="inventory-search">商品名称或编码</label
        ><el-input
          id="inventory-search"
          v-model="filter.keyword"
          @input="filter.skuId = ''"
          placeholder="编码、名称或条码"
          maxlength="100"
          clearable
        />
      </div>
      <el-checkbox v-model="filter.shortageOnly">只看待审需求超过可用</el-checkbox
      ><el-checkbox v-model="filter.hideZero">隐藏零库存</el-checkbox
      ><el-button native-type="submit" type="primary" :loading="busy">查询</el-button>
    </form>
    <el-alert
      v-if="error || optionsError"
      type="error"
      :title="error || optionsError"
      :closable="false"
    />
    <el-table :data="snapshot?.items ?? []" :row-key="rowKey">
      <el-table-column prop="warehouseName" label="仓库" min-width="130" /><el-table-column
        prop="skuCode"
        label="商品编码"
        min-width="130"
      /><el-table-column prop="skuName" label="商品名称" min-width="180" />
      <el-table-column label="在库" min-width="100" align="right"
        ><template #default="{ row }">{{
          formatQuantity(row.onHandQty)
        }}</template></el-table-column
      >
      <el-table-column label="预留" min-width="100" align="right"
        ><template #default="{ row }">{{
          formatQuantity(row.reservedQty)
        }}</template></el-table-column
      >
      <el-table-column label="冻结" min-width="100" align="right"
        ><template #default="{ row }">{{
          formatQuantity(row.blockedQty)
        }}</template></el-table-column
      >
      <el-table-column label="可用" min-width="100" align="right"
        ><template #default="{ row }"
          ><strong>{{ formatQuantity(row.availableQty) }}</strong></template
        ></el-table-column
      >
      <el-table-column label="待审核需求" min-width="200" align="right"
        ><template #default="{ row }"
          ><span
            :class="{
              'inventory-shortage': demandExceedsAvailable(row.pendingDemandQty, row.availableQty),
            }"
            >{{ formatQuantity(row.pendingDemandQty) }}</span
          >
          <el-tag
            v-if="demandExceedsAvailable(row.pendingDemandQty, row.availableQty)"
            type="warning"
            size="small"
            >超出可用</el-tag
          ></template
        ></el-table-column
      >
      <el-table-column v-if="canCost" label="单位成本" min-width="120" align="right"
        ><template #default="{ row }">{{ formatPrice(row.unitCost) }}</template></el-table-column
      >
      <el-table-column v-if="canCost" label="库存金额" min-width="140" align="right"
        ><template #default="{ row }">{{ formatMoney(row.costAmount) }}</template></el-table-column
      >
      <el-table-column v-if="session.can('report:inventory_flow')" label="操作" width="80"
        ><template #default="{ row }"
          ><el-button text @click="ledger(row)">台账</el-button></template
        ></el-table-column
      >
      <template #empty
        ><div class="empty-state">
          <strong>{{
            busy ? '正在加载…' : error ? '加载失败，请重试' : '没有符合条件的库存记录'
          }}</strong><el-button v-if="error" text @click="load">重新加载</el-button>
        </div></template
      >
    </el-table>
    <footer class="table-footer">
      <span>可用 = 在库 − 预留 − 冻结。待审核需求只用于提醒，不占用库存。</span>
      <div>
        <span>第 {{ cursors.length }} 页</span
        ><el-button :disabled="cursors.length === 1 || busy" @click="paginate(false)"
          >上一页</el-button
        ><el-button :disabled="!snapshot?.nextCursor || busy" @click="paginate(true)"
          >下一页</el-button
        >
      </div>
    </footer>
  </section>
</template>
<style scoped>
.inventory-statistics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}
.inventory-statistic {
  padding: 18px 20px;
  text-decoration: none;
  color: inherit;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.inventory-statistic > span {
  font-size: 12px;
  color: #6b7380;
}
.inventory-statistic strong {
  font-size: 24px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.inventory-statistic small {
  font-size: 12px;
  font-weight: 400;
  color: #6b7380;
}
.inventory-shortage {
  color: #b77112;
  font-weight: 600;
}
.table-footer > div {
  white-space: nowrap;
}
.table-footer > div > span {
  margin-right: 12px;
  font-size: 12px;
  color: #6b7380;
}
.filters .filter-field {
  width: 210px;
}
@media (max-width: 480px) {
  .inventory-statistics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
  .inventory-statistic {
    padding: 12px;
  }
  .inventory-statistic strong {
    font-size: 20px;
    overflow-wrap: anywhere;
  }
}
</style>
