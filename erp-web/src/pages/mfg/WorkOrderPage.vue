<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  manufacturingCommand,
  manufacturingDetail,
  manufacturingList,
} from '../../api/manufacturing'
import { isCancelled, messageOf } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import {
  manufacturingActions,
  manufacturingStates,
  shortCloseCostLabels,
} from '../../types/manufacturing'
import type { CostRow, WorkOrder } from '../../types/manufacturing'
import { useWmsCommands } from '../wms/useWmsCommands'
import ManufacturingCostTable from './ManufacturingCostTable.vue'
import WorkOrderEditor from './WorkOrderEditor.vue'
import ShortCloseEditor from './ShortCloseEditor.vue'
import ReferenceSelect from './ReferenceSelect.vue'
import {
  catalogLabel,
  remainingMaterial,
  useManufacturingCatalog,
  useManufacturingList,
} from './useManufacturing'

const route = useRoute(),
  router = useRouter(),
  session = useSessionStore()
const id = typeof route.params.id === 'string' ? route.params.id : undefined
const keyword = ref(''),
  status = ref(''),
  warehouse = ref('')
const detail = ref<WorkOrder>(),
  loadingDetail = ref(false),
  detailError = ref('')
const tab = ref('materials'),
  editor = ref(false)
const shortCloseEditor = ref(false),
  confirmShortClose = ref(false)
const { warehouses, loadCatalog } = useManufacturingCatalog()
const { busy, error: commandError, notice, run } = useWmsCommands()
const { items, cursor, loading, error, load } = useManufacturingList((cursor) =>
  manufacturingList<WorkOrder>('work-orders', {
    cursor,
    keyword: keyword.value.trim() || undefined,
    status: status.value || undefined,
    warehouseId: warehouse.value || undefined,
  }),
)
const costs = useManufacturingList((cursor) =>
  manufacturingList<CostRow>('reports/cost-detail', { workOrderId: id, cursor }),
)
const canCost = computed(() => session.can('cost:view') && session.can('mfg_report:view'))
const states = ['draft', 'released', 'in_progress', 'completed', 'closed', 'cancelled'] as const
let disposed = false
onScopeDispose(() => {
  disposed = true
})

const actions = computed(() => {
  const item = detail.value
  if (!item) return []
  const byState: Record<string, string[]> = {
    draft: ['release', 'cancel'],
    released: ['cancel'],
    completed: ['close'],
  }
  return (item.allowedActions ?? byState[item.status] ?? []).filter(
    (action) =>
      ['release', 'close', 'cancel'].includes(action) && session.can('mfg_work_order:' + action),
  )
})
const ready = computed(() =>
  Boolean(detail.value && ['released', 'in_progress', 'completed'].includes(detail.value.status)),
)
const shortClose = computed(() => detail.value?.shortClose)
const staleShortClose = computed(
  () =>
    shortClose.value?.status === 'pending' &&
    shortClose.value.orderVersion !== detail.value?.version,
)
const canRequestShortClose = computed(
  () =>
    session.can('mfg_work_order:view') &&
    session.can('mfg_work_order:short_close') &&
    detail.value?.allowedActions?.includes('short_close'),
)
const canApproveShortClose = computed(
  () =>
    session.can('mfg_work_order:view') &&
    session.can('mfg_work_order:approve_short_close') &&
    detail.value?.allowedActions?.includes('approve_short_close') &&
    shortClose.value?.status === 'pending' &&
    !staleShortClose.value,
)
const materialLabels = computed(
  () =>
    new Map(
      (detail.value?.materials ?? []).map((item) => [
        item.id,
        item.skuName ?? item.skuCode ?? item.componentSkuId,
      ]),
    ),
)

function warehouseLabel(id: string): string {
  const item = warehouses.value.find((warehouse) => warehouse.id === id)
  return item ? catalogLabel(item) : id
}

async function inspect(): Promise<void> {
  if (!id || loadingDetail.value) return
  loadingDetail.value = true
  detailError.value = ''
  try {
    const data = await manufacturingDetail<WorkOrder>('work-orders', id)
    if (!disposed) {
      if (!detail.value && data.shortClose?.status === 'pending') tab.value = 'short-close'
      if (!data.shortClose && tab.value === 'short-close') tab.value = 'materials'
      detail.value = data
    }
  } catch (cause: unknown) {
    if (!disposed && !isCancelled(cause)) {
      detail.value = undefined
      detailError.value = messageOf(cause)
    }
  } finally {
    loadingDetail.value = false
  }
}

async function transition(action: string): Promise<void> {
  const current = detail.value
  if (!current) return
  const data = { version: current.version }
  const saved = await run(
    action + ':' + current.id,
    data,
    (key) => manufacturingCommand<WorkOrder>('work-orders', current.id, action, data, key),
    '工单已更新。',
  )
  if (saved && !disposed) await inspect()
}

function shortCloseSaved(item: WorkOrder): void {
  if (disposed || item.id !== id) return
  shortCloseEditor.value = false
  detail.value = item
  tab.value = 'short-close'
  commandError.value = ''
  notice.value =
    item.shortClose?.status === 'approved'
      ? '工单已短交关闭。'
      : '短交申请已提交，请由另一位有权用户复核。'
}

async function approveShortClose(): Promise<void> {
  const current = detail.value
  if (!current || !canApproveShortClose.value || loadingDetail.value) return
  const company = session.companyId
  const data = { version: current.version }
  const result = await run(
    'approve-short-close:' + current.id,
    data,
    (key) =>
      manufacturingCommand<WorkOrder>('work-orders', current.id, 'approve-short-close', data, key),
    '工单已短交关闭。',
  )
  if (!result || disposed || session.companyId !== company) return
  confirmShortClose.value = false
  detail.value = result
  tab.value = 'short-close'
}

function saved(item: WorkOrder): void {
  editor.value = false
  if (id) void inspect()
  else void router.push('/mfg/work-orders/' + encodeURIComponent(item.id))
}

function selectTab(value: string | number): void {
  tab.value = String(value)
  if (tab.value === 'costs' && canCost.value && !costs.loading.value) void costs.load()
}

onMounted(() => {
  void loadCatalog(['warehouses'])
  if (id) void inspect()
  else void load()
})
</script>

<template>
  <el-alert
    v-if="error || detailError || commandError"
    :title="error || detailError || commandError"
    type="error"
    :closable="false"
  />
  <p v-if="notice" role="status">{{ notice }}</p>
  <section v-if="!id" class="panel mfg-panel" aria-label="生产工单列表">
    <div class="mfg-toolbar">
      <el-input
        v-model="keyword"
        placeholder="工单号、商品编码或名称"
        aria-label="搜索生产工单"
        @keydown.enter.prevent="load()"
      />
      <el-select
        v-model="status"
        clearable
        placeholder="全部状态"
        aria-label="工单状态"
        @change="load()"
      >
        <el-option
          v-for="key in states"
          :key="key"
          :value="key"
          :label="manufacturingStates[key]"
        />
      </el-select>
      <ReferenceSelect
        v-if="warehouses.length"
        v-model="warehouse"
        :rows="warehouses"
        label="工单仓库"
        optional
        @update:model-value="load()"
      />
      <el-button @click="load()">查询</el-button>
      <el-button v-if="session.can('mfg_work_order:create')" type="primary" @click="editor = true"
        >新建工单</el-button
      >
    </div>
    <el-table v-loading="loading" :data="items" empty-text="暂无生产工单">
      <el-table-column label="工单号" min-width="145"
        ><template #default="{ row }"
          ><router-link class="text-link" :to="'/mfg/work-orders/' + row.id">{{
            row.documentNo
          }}</router-link></template
        ></el-table-column
      >
      <el-table-column label="产成品" min-width="145"
        ><template #default="{ row }">{{
          row.skuName ?? row.skuCode ?? row.skuId
        }}</template></el-table-column
      >
      <el-table-column label="状态" min-width="85"
        ><template #default="{ row }">{{
          manufacturingStates[row.status] ?? '—'
        }}</template></el-table-column
      >
      <el-table-column prop="planQty" label="计划数量" min-width="95" />
      <el-table-column prop="completedQty" label="已完工" min-width="95" />
      <el-table-column prop="planStartDate" label="计划开工" min-width="110" />
      <el-table-column prop="planFinishDate" label="计划完工" min-width="110" />
    </el-table>
    <el-button v-if="cursor" class="mfg-list-pager" :loading="loading" @click="load(true)"
      >加载更多</el-button
    >
  </section>
  <section v-else class="panel mfg-panel" aria-label="工单详情">
    <div class="mfg-toolbar">
      <router-link class="text-link" to="/mfg/work-orders">返回生产工单</router-link>
      <el-button :loading="loadingDetail" :disabled="busy" @click="inspect">刷新</el-button>
    </div>
    <p v-if="loadingDetail" role="status">正在读取工单。</p>
    <template v-if="detail">
      <h2>{{ detail.documentNo }} · {{ detail.skuName ?? detail.skuCode ?? detail.skuId }}</h2>
      <div class="mfg-toolbar">
        <el-tag>{{ manufacturingStates[detail.status] }}</el-tag>
        <el-tag
          v-if="shortClose?.status === 'pending'"
          :type="staleShortClose ? 'info' : 'warning'"
        >
          {{ staleShortClose ? '短交申请已失效' : '短交待复核' }}
        </el-tag>
        <el-tag v-else-if="shortClose?.status === 'approved'" type="success">已短交关闭</el-tag>
        <el-button
          v-if="detail.status === 'draft' && session.can('mfg_work_order:update')"
          :disabled="busy || loadingDetail"
          @click="editor = true"
          >编辑工单</el-button
        >
        <el-button
          v-for="action in actions"
          :key="action"
          :type="action === 'release' ? 'primary' : undefined"
          :disabled="loadingDetail"
          :loading="busy"
          @click="transition(action)"
          >{{ manufacturingActions[action] }}</el-button
        >
        <el-button
          v-if="canRequestShortClose"
          :disabled="busy || loadingDetail"
          @click="shortCloseEditor = true"
          >{{ shortClose?.status === 'pending' ? '重新申请短交' : '申请短交关闭' }}</el-button
        >
        <el-button
          v-if="canApproveShortClose"
          type="primary"
          :disabled="busy || loadingDetail"
          @click="confirmShortClose = true"
          >复核短交申请</el-button
        >
        <template v-if="ready">
          <router-link
            v-if="session.can('mfg_issue:view') && session.can('mfg_issue:create')"
            class="text-link"
            :to="{ path: '/mfg/issues', query: { workOrderId: detail.id, create: 'issue' } }"
            >生成领料单</router-link
          >
          <router-link
            v-if="session.can('mfg_issue:view') && session.can('mfg_issue:create')"
            class="text-link"
            :to="{ path: '/mfg/issues', query: { workOrderId: detail.id, create: 'return' } }"
            >生成退料单</router-link
          >
          <router-link
            v-if="session.can('mfg_receipt:view') && session.can('mfg_receipt:create')"
            class="text-link"
            :to="{ path: '/mfg/receipts', query: { workOrderId: detail.id, create: 'receipt' } }"
            >生成完工入库单</router-link
          >
        </template>
      </div>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="计划数量">{{ detail.planQty }}</el-descriptions-item>
        <el-descriptions-item label="已完工">{{ detail.completedQty }}</el-descriptions-item>
        <el-descriptions-item label="超产上限"
          >{{ detail.overReceivePct ?? '0' }}%</el-descriptions-item
        >
        <el-descriptions-item label="领料仓">{{
          detail.issueWarehouseName ??
          detail.issueWarehouseCode ??
          warehouseLabel(detail.issueWarehouseId)
        }}</el-descriptions-item>
        <el-descriptions-item label="完工仓">{{
          detail.receiveWarehouseName ??
          detail.receiveWarehouseCode ??
          warehouseLabel(detail.receiveWarehouseId)
        }}</el-descriptions-item>
        <el-descriptions-item label="计划工期"
          >{{ detail.planStartDate }} 至 {{ detail.planFinishDate }}</el-descriptions-item
        >
      </el-descriptions>
      <el-tabs :model-value="tab" @update:model-value="selectTab">
        <el-tab-pane v-if="shortClose" label="短交记录" name="short-close">
          <el-alert
            v-if="staleShortClose"
            title="工单用料、完工数量或状态已变化，请核对后重新申请短交。"
            type="warning"
            :closable="false"
          />
          <el-descriptions :column="2" border>
            <el-descriptions-item label="短交日期">{{
              shortClose.businessDate
            }}</el-descriptions-item>
            <el-descriptions-item label="发起人编号">{{
              shortClose.requestedBy
            }}</el-descriptions-item>
            <el-descriptions-item v-if="shortClose.reviewedBy" label="复核人编号">
              {{ shortClose.reviewedBy }}
            </el-descriptions-item>
            <el-descriptions-item label="剩余在制成本">
              {{ shortCloseCostLabels[shortClose.costDisposition] }}
            </el-descriptions-item>
            <el-descriptions-item label="短交原因">{{ shortClose.reason }}</el-descriptions-item>
          </el-descriptions>
          <el-table :data="shortClose.materials" row-key="materialId" empty-text="无净领料需处理">
            <el-table-column label="物料" min-width="128">
              <template #default="{ row }">{{
                materialLabels.get(row.materialId) ?? row.materialId
              }}</template>
            </el-table-column>
            <el-table-column prop="consumedQty" label="已耗用" min-width="105" align="right" />
            <el-table-column prop="scrappedQty" label="已报废" min-width="105" align="right" />
            <el-table-column prop="scrapReason" label="报废原因" min-width="150" />
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="用料" name="materials">
          <el-table :data="detail.materials ?? []" empty-text="暂无冻结用料">
            <el-table-column label="物料" min-width="145"
              ><template #default="{ row }">{{
                row.skuName ?? row.skuCode ?? row.componentSkuId
              }}</template></el-table-column
            >
            <el-table-column prop="unitName" label="单位" min-width="85" />
            <el-table-column prop="requiredQty" label="应领" min-width="90" />
            <el-table-column prop="issuedQty" label="已领" min-width="90" />
            <el-table-column prop="returnedQty" label="已退" min-width="90" />
            <el-table-column label="待领" min-width="90"
              ><template #default="{ row }">{{
                remainingMaterial(row.requiredQty, row.issuedQty, row.returnedQty)
              }}</template></el-table-column
            >
          </el-table>
        </el-tab-pane>
        <el-tab-pane v-if="session.can('mfg_issue:view')" label="领退料记录" name="issues" lazy>
          <el-table :data="detail.issues ?? []" empty-text="暂无领退料记录">
            <el-table-column label="单据号" min-width="145"
              ><template #default="{ row }"
                ><router-link
                  class="text-link"
                  :to="{
                    path: '/mfg/issues',
                    query: { workOrderId: detail.id, documentId: row.id },
                  }"
                  >{{ row.documentNo }}</router-link
                ></template
              ></el-table-column
            >
            <el-table-column label="方向" min-width="90"
              ><template #default="{ row }">{{
                row.direction === 'return' ? '退料' : '领料'
              }}</template></el-table-column
            >
            <el-table-column label="状态" min-width="85"
              ><template #default="{ row }">{{
                manufacturingStates[row.status] ?? '—'
              }}</template></el-table-column
            >
            <el-table-column prop="businessDate" label="日期" min-width="110" />
          </el-table>
        </el-tab-pane>
        <el-tab-pane v-if="session.can('mfg_receipt:view')" label="完工记录" name="receipts" lazy>
          <el-table :data="detail.receipts ?? []" empty-text="暂无完工记录">
            <el-table-column label="单据号" min-width="145"
              ><template #default="{ row }"
                ><router-link
                  class="text-link"
                  :to="{
                    path: '/mfg/receipts',
                    query: { workOrderId: detail.id, documentId: row.id },
                  }"
                  >{{ row.documentNo }}</router-link
                ></template
              ></el-table-column
            >
            <el-table-column label="状态" min-width="85"
              ><template #default="{ row }">{{
                manufacturingStates[row.status] ?? '—'
              }}</template></el-table-column
            >
            <el-table-column prop="businessDate" label="日期" min-width="110" />
          </el-table>
        </el-tab-pane>
        <el-tab-pane v-if="canCost" label="成本" name="costs" lazy>
          <el-alert
            v-if="costs.error.value"
            :title="costs.error.value"
            type="error"
            :closable="false"
          />
          <ManufacturingCostTable v-loading="costs.loading.value" :rows="costs.items.value" />
          <el-button
            v-if="costs.cursor.value"
            class="mfg-list-pager"
            :loading="costs.loading.value"
            @click="costs.load(true)"
            >加载更多</el-button
          >
        </el-tab-pane>
      </el-tabs>
    </template>
    <el-empty v-else-if="!loadingDetail" description="工单详情未加载" />
  </section>
  <WorkOrderEditor v-if="editor" :source="detail" @close="editor = false" @saved="saved" />
  <ShortCloseEditor
    v-if="shortCloseEditor && detail && canRequestShortClose"
    :order="detail"
    @close="shortCloseEditor = false"
    @saved="shortCloseSaved"
  />
  <el-dialog
    v-if="confirmShortClose && shortClose && canApproveShortClose"
    :model-value="true"
    title="确认短交关闭"
    width="min(520px, 95vw)"
    :close-on-click-modal="false"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    @close="confirmShortClose = false"
  >
    <el-alert v-if="commandError" :title="commandError" type="error" :closable="false" />
    <p>请核对短交日期、原因和材料处理记录。复核通过后，本工单停止领退料及完工入库。</p>
    <el-descriptions :column="1">
      <el-descriptions-item label="短交日期">{{ shortClose.businessDate }}</el-descriptions-item>
      <el-descriptions-item label="短交原因">{{ shortClose.reason }}</el-descriptions-item>
      <el-descriptions-item label="剩余在制成本">{{
        shortCloseCostLabels[shortClose.costDisposition]
      }}</el-descriptions-item>
    </el-descriptions>
    <template #footer>
      <el-button :disabled="busy" @click="confirmShortClose = false">返回复核</el-button>
      <el-button type="primary" :loading="busy" @click="approveShortClose">确认短交关闭</el-button>
    </template>
  </el-dialog>
</template>
