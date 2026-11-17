<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import {
  claimWmsTask,
  confirmWmsTaskLine,
  finishWmsTask,
  setWmsTaskMode,
  wmsTask,
  wmsTasks,
  wmsWarehouses,
} from '../api/wms'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import { useSessionStore } from '../stores/session'
import { demandExceedsAvailable } from '../types/inventory'
import { formatQuantity } from '../utils/format'
import type { Id, PageResult } from '../types/api'
import type {
  WmsLineConfirmation,
  WmsTask,
  WmsTaskLine,
  WmsTaskMode,
  WmsTaskStatus,
  WmsTaskSummary,
  WmsWarehouse,
} from '../types/wms'
import PutawayConfirmForm from './wms/PutawayConfirmForm.vue'

const session = useSessionStore()
const statusNames: Record<WmsTaskStatus, string> = {
  created: '待领取',
  assigned: '已分配',
  in_progress: '作业中',
  done: '已完成',
  cancelled: '已取消',
}
const sourceNames: Record<WmsTask['sourceType'], string> = {
  goods_receipt: '采购收货',
  sales_return: '销售退货',
}
const page = ref<PageResult<WmsTaskSummary>>()
const warehouses = ref<WmsWarehouse[]>([])
const filters = reactive({ warehouse: '', status: '' as WmsTaskStatus | '' })
const cursors = ref<Id[]>([])
const selectedId = ref<Id>()
const selected = ref<WmsTask>()
const confirming = ref<WmsTaskLine>()
const showSettings = ref(false)
const modeWarehouseId = ref<Id>('')
const modeDraft = ref<WmsTaskMode>('off')
const selectedWarehouse = computed(() =>
  warehouses.value.find((warehouse) => warehouse.id === modeWarehouseId.value),
)
const listBusy = ref(false)
const detailBusy = ref(false)
const saving = ref(false)
const listError = ref('')
const warehouseError = ref('')
const detailError = ref('')
const actionError = ref('')
const settingsError = ref('')
const notice = ref('')
let scope = new AbortController()
let listRequest = new AbortController()
let detailRequest = new AbortController()
let warehouseRequest = new AbortController()
const commandKeys = new Map<string, string>()

const canExecute = computed(() => session.can('wms_task:execute'))
const canConfigure = computed(
  () => session.can('wms_task:configure') && session.can('warehouse:update'),
)
const ownTask = computed(
  () => !!selected.value?.assigneeId && selected.value.assigneeId === session.user?.id,
)
const canClaim = computed(() => canExecute.value && selected.value?.status === 'created')
const canConfirm = computed(
  () =>
    canExecute.value &&
    ownTask.value &&
    (selected.value?.status === 'assigned' || selected.value?.status === 'in_progress'),
)
const canFinish = computed(
  () =>
    canConfirm.value &&
    !!selected.value?.lines.length &&
    selected.value.lines.every((line) => !linePending(line)),
)

function linePending(line: WmsTaskLine): boolean {
  return demandExceedsAvailable(line.planQty, line.doneQty)
}

function warehouseName(id: Id): string {
  const warehouse = warehouses.value.find((entry) => entry.id === id)
  return warehouse ? warehouse.code + ' · ' + warehouse.name : id
}

async function loadWarehouses(): Promise<void> {
  warehouseRequest.abort()
  warehouseRequest = new AbortController()
  const { signal } = warehouseRequest
  warehouseError.value = ''
  try {
    const result = await wmsWarehouses(signal)
    if (!signal.aborted) warehouses.value = result.items
  } catch (cause: unknown) {
    if (!signal.aborted && !isCancelled(cause)) warehouseError.value = messageOf(cause)
  }
}

async function loadTasks(): Promise<void> {
  listRequest.abort()
  listRequest = new AbortController()
  const { signal } = listRequest
  listBusy.value = true
  listError.value = ''
  try {
    const result = await wmsTasks(
      {
        warehouseId: filters.warehouse || undefined,
        status: filters.status || undefined,
        cursor: cursors.value.at(-1),
        limit: 20,
      },
      signal,
    )
    if (!signal.aborted) page.value = result
  } catch (cause: unknown) {
    if (!signal.aborted && !isCancelled(cause)) listError.value = messageOf(cause)
  } finally {
    if (!signal.aborted) listBusy.value = false
  }
}

async function loadSelected(id: Id): Promise<void> {
  detailRequest.abort()
  detailRequest = new AbortController()
  const { signal } = detailRequest
  selectedId.value = id
  selected.value = undefined
  detailError.value = ''
  detailBusy.value = true
  try {
    const result = await wmsTask(id, signal)
    if (!signal.aborted) selected.value = result
  } catch (cause: unknown) {
    if (!signal.aborted && !isCancelled(cause)) detailError.value = messageOf(cause)
  } finally {
    if (!signal.aborted) detailBusy.value = false
  }
}

function openTask(id: Id): void {
  confirming.value = undefined
  actionError.value = ''
  notice.value = ''
  void loadSelected(id)
}

function updateSearch(): void {
  cursors.value = []
  page.value = undefined
  void loadTasks()
}

function changePage(forward: boolean): void {
  const next = page.value?.nextCursor
  if (forward && next) cursors.value.push(next)
  else if (!forward && cursors.value.length) cursors.value.pop()
  else return
  void loadTasks()
}

function refresh(): void {
  void loadWarehouses()
  void loadTasks()
  if (selectedId.value && !confirming.value) openTask(selectedId.value)
}

function resetScope(): void {
  scope.abort()
  scope = new AbortController()
  listRequest.abort()
  detailRequest.abort()
  warehouseRequest.abort()
  commandKeys.clear()
  page.value = undefined
  warehouses.value = []
  selected.value = undefined
  selectedId.value = undefined
  confirming.value = undefined
  showSettings.value = false
  saving.value = false
  detailBusy.value = false
  listBusy.value = false
  filters.warehouse = ''
  filters.status = ''
  cursors.value = []
  modeWarehouseId.value = ''
  listError.value =
    warehouseError.value =
    detailError.value =
    actionError.value =
    settingsError.value =
      ''
  notice.value = ''
  if (session.companyId) refresh()
}

async function runTaskCommand(
  task: WmsTask,
  signature: string,
  send: (key: string) => Promise<unknown>,
  success: string,
): Promise<void> {
  if (saving.value) return
  const { signal } = scope
  const commandKey = commandKeys.get(signature) ?? newIdempotencyKey()
  commandKeys.set(signature, commandKey)
  saving.value = true
  actionError.value = ''
  notice.value = ''
  try {
    await send(commandKey)
    if (signal.aborted) return
    commandKeys.delete(signature)
    confirming.value = undefined
    notice.value = success
    await Promise.all([loadSelected(task.id), loadTasks()])
  } catch (cause: unknown) {
    if (signal.aborted || isCancelled(cause)) return
    actionError.value = messageOf(cause)
    if (cause instanceof ApiError && cause.status === 0) {
      actionError.value += ' 保留当前输入后可重试同一操作。'
    }
  } finally {
    if (!signal.aborted) saving.value = false
  }
}

function claim(): void {
  const task = selected.value
  if (!task || !canClaim.value) return
  void runTaskCommand(
    task,
    `claim:${task.id}:${task.version}`,
    (key) => claimWmsTask(task.id, task.version, key),
    '已领取上架任务。',
  )
}

function startConfirmation(line: WmsTaskLine): void {
  confirming.value = line
  actionError.value = ''
}

function closeConfirmation(): void {
  confirming.value = undefined
  actionError.value = ''
}

function confirm(input: Omit<WmsLineConfirmation, 'version'>): void {
  const task = selected.value
  const line = confirming.value
  if (!task || !line || !canConfirm.value) return
  const body = { ...input, version: task.version }
  void runTaskCommand(
    task,
    `confirm:${task.id}:${line.id}:${JSON.stringify(body)}`,
    (key) => confirmWmsTaskLine(task.id, line.id, body, key),
    '已确认本次上架。',
  )
}

function finish(): void {
  const task = selected.value
  if (!task || !canFinish.value) return
  void runTaskCommand(
    task,
    `finish:${task.id}:${task.version}`,
    (key) => finishWmsTask(task.id, task.version, key),
    '上架任务已完成。',
  )
}

async function saveMode(): Promise<void> {
  const warehouse = selectedWarehouse.value
  if (!warehouse || !canConfigure.value || saving.value) return
  const { signal } = scope
  saving.value = true
  settingsError.value = ''
  notice.value = ''
  try {
    await setWmsTaskMode(warehouse.id, modeDraft.value)
    if (signal.aborted) return
    notice.value = '仓库任务模式已保存。'
    showSettings.value = false
    await loadWarehouses()
  } catch (cause: unknown) {
    if (!signal.aborted && !isCancelled(cause)) settingsError.value = messageOf(cause)
  } finally {
    if (!signal.aborted) saving.value = false
  }
}

watch(modeWarehouseId, () => {
  modeDraft.value = selectedWarehouse.value?.taskMode ?? 'off'
  settingsError.value = ''
})
watch(() => session.companyId, resetScope, { immediate: true })
onScopeDispose(() => {
  scope.abort()
  listRequest.abort()
  detailRequest.abort()
  warehouseRequest.abort()
})
</script>

<template>
  <div class="page-heading">
    <h1>上架任务</h1>
    <div class="sales-actions">
      <el-button v-if="canConfigure" :disabled="saving" @click="showSettings = !showSettings">
        仓库任务模式
      </el-button>
      <el-button :disabled="saving" @click="refresh">刷新</el-button>
    </div>
  </div>
  <el-alert v-if="notice" type="success" :title="notice" :closable="false" />
  <el-alert
    v-if="warehouseError"
    type="warning"
    :title="'仓库选项加载失败。' + warehouseError"
    :closable="false"
  />
  <section v-if="showSettings && canConfigure" class="panel table-panel" aria-label="仓库任务模式">
    <div class="filters">
      <el-select
        v-model="modeWarehouseId"
        :disabled="saving"
        aria-label="配置仓库"
        placeholder="选择仓库"
      >
        <el-option
          v-for="warehouse in warehouses"
          :key="warehouse.id"
          :value="warehouse.id"
          :label="warehouseName(warehouse.id)"
        />
      </el-select>
      <el-select v-model="modeDraft" :disabled="saving || !selectedWarehouse" aria-label="任务模式">
        <el-option value="off" label="关闭" />
        <el-option
          value="optional"
          label="生成上架任务"
          :disabled="!selectedWarehouse?.locationManaged"
        />
        <el-option value="required" label="拣货复核后发货" :disabled="!selectedWarehouse?.locationManaged" />
      </el-select>
      <el-button
        type="primary"
        :loading="saving"
        :disabled="!selectedWarehouse || modeDraft === selectedWarehouse.taskMode"
        @click="saveMode"
        >保存模式</el-button
      >
    </div>
    <p v-if="selectedWarehouse && !selectedWarehouse.locationManaged">
      仓库启用库位管理后可开启上架任务。
    </p>
    <p v-else-if="selectedWarehouse">开启后单据过账时生成新任务，关闭时保留已有任务。</p>
    <el-alert v-if="settingsError" type="error" :title="settingsError" :closable="false" />
  </section>
  <section class="panel table-panel" aria-label="上架任务列表">
    <div class="filters">
      <el-select
        v-model="filters.warehouse"
        aria-label="筛选仓库"
        :disabled="saving"
        @change="updateSearch"
      >
        <el-option value="" label="全部仓库" />
        <el-option
          v-for="warehouse in warehouses"
          :key="warehouse.id"
          :value="warehouse.id"
          :label="warehouseName(warehouse.id)"
        />
      </el-select>
      <el-select
        v-model="filters.status"
        aria-label="筛选任务状态"
        :disabled="saving"
        @change="updateSearch"
      >
        <el-option value="" label="全部状态" />
        <el-option
          v-for="(label, state) in statusNames"
          :key="state"
          :value="state"
          :label="label"
        />
      </el-select>
    </div>
    <el-alert v-if="listError" type="error" :title="listError" :closable="false" />
    <el-table
      v-loading="listBusy"
      :data="page?.items ?? []"
      row-key="id"
      empty-text="暂无匹配的上架任务"
    >
      <el-table-column prop="no" label="任务单号" min-width="160" />
      <el-table-column label="仓库" min-width="150">
        <template #default="{ row }">{{ warehouseName(row.warehouseId) }}</template>
      </el-table-column>
      <el-table-column label="来源" min-width="120">
        <template #default="{ row }">{{
          sourceNames[(row as WmsTaskSummary).sourceType]
        }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">{{ statusNames[(row as WmsTaskSummary).status] }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="saving" @click="openTask(row.id)"
            >查看任务</el-button
          >
        </template>
      </el-table-column>
    </el-table>
    <footer class="table-footer">
      <el-button :disabled="!cursors.length || listBusy || saving" @click="changePage(false)">
        上一页
      </el-button>
      <el-button :disabled="!page?.nextCursor || listBusy || saving" @click="changePage(true)">
        下一页
      </el-button>
    </footer>
  </section>
  <section v-if="selectedId" v-loading="detailBusy" class="panel table-panel" aria-label="任务明细">
    <el-alert v-if="detailError" type="error" :title="detailError" :closable="false" />
    <template v-if="selected">
      <div class="filters">
        <h2>{{ selected.no }}</h2>
        <el-tag>{{ statusNames[selected.status] }}</el-tag>
        <span>{{ warehouseName(selected.warehouseId) }}</span>
        <span>{{ sourceNames[selected.sourceType] }} {{ selected.sourceId }}</span>
        <el-button v-if="canClaim" type="primary" :loading="saving" @click="claim"
          >领取任务</el-button
        >
        <el-button v-if="canFinish" type="primary" :loading="saving" @click="finish"
          >完成任务</el-button
        >
      </div>
      <el-alert v-if="actionError" type="error" :title="actionError" :closable="false" />
      <el-table :data="selected.lines" row-key="id" empty-text="暂无明细">
        <el-table-column label="商品" min-width="155">
          <template #default="{ row }">{{ row.skuCode }} · {{ row.skuName }}</template>
        </el-table-column>
        <el-table-column prop="batchNo" label="批次" min-width="100" />
        <el-table-column prop="fromLocationCode" label="来源库位" min-width="100" />
        <el-table-column label="推荐库位" min-width="100">
          <template #default="{ row }">{{ row.recommendedLocationCode || '人工指定' }}</template>
        </el-table-column>
        <el-table-column label="已上架 / 计划数量" min-width="145" align="right">
          <template #default="{ row }">
            {{ formatQuantity(row.doneQty) }} / {{ formatQuantity(row.planQty) }}
          </template>
        </el-table-column>
        <el-table-column v-if="canConfirm" label="操作" width="100">
          <template #default="{ row }">
            <el-button
              v-if="linePending(row)"
              link
              type="primary"
              :disabled="saving"
              @click="startConfirmation(row)"
              >扫码确认</el-button
            >
          </template>
        </el-table-column>
      </el-table>
      <PutawayConfirmForm
        v-if="confirming && canConfirm"
        :key="confirming.id"
        :line="confirming"
        :busy="saving"
        @confirm="confirm"
        @cancel="closeConfirmation"
      />
    </template>
  </section>
</template>
