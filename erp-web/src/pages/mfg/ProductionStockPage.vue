<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  manufacturingCommand,
  manufacturingDetail,
  manufacturingList,
} from '../../api/manufacturing'
import { isCancelled, messageOf } from '../../api/http'
import WorkflowStarterSelector from '../../components/workflow/WorkflowStarterSelector.vue'
import WorkflowProgressPanel from '../../components/workflow/WorkflowProgressPanel.vue'
import { useSessionStore } from '../../stores/session'
import { formatMoney } from '../../utils/format'
import { manufacturingActions, manufacturingStates } from '../../types/manufacturing'
import type { ProductionDocument, StockArea } from '../../types/manufacturing'
import type { WorkflowSelections } from '../../types/workflow'
import { useWmsCommands } from '../wms/useWmsCommands'
import ProductionStockEditor from './ProductionStockEditor.vue'
import ReferenceSelect from './ReferenceSelect.vue'
import { useManufacturingCatalog, useManufacturingList } from './useManufacturing'

const props = defineProps<{ area: StockArea }>()
const route = useRoute(),
  session = useSessionStore()
const issue = props.area === 'issues'
const permission = issue ? 'mfg_issue' : 'mfg_receipt'
const routeString = (value: unknown): string =>
  typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value) ? value : ''
const workOrderId = ref(routeString(route.query.workOrderId))
const focusing = routeString(route.params.id) || routeString(route.query.documentId)
const warehouseId = ref(''),
  status = ref(''),
  direction = ref('')
const detail = ref<ProductionDocument>(),
  editor = ref<'new' | 'edit'>()
const newDirection = ref<'issue' | 'return'>('issue')
const detailError = ref(''),
  opening = ref(false)
const addingWorkflow = ref(false),
  workflowReady = ref(false),
  selections = ref<WorkflowSelections>({})
const rejection = ref(false),
  reason = ref('')
let revision = 0
onScopeDispose(() => {
  revision++
})

const { warehouses, loadCatalog } = useManufacturingCatalog()
const {
  items,
  cursor,
  loading,
  error: listError,
  load,
} = useManufacturingList((cursor) =>
  manufacturingList<ProductionDocument>(props.area, {
    cursor,
    workOrderId: workOrderId.value || undefined,
    warehouseId: warehouseId.value || undefined,
    status: status.value || undefined,
    direction: issue ? direction.value || undefined : undefined,
  }),
)
const { busy, error: commandError, notice, run } = useWmsCommands()
const states = [
  'draft',
  'pending',
  'approved',
  'posted',
  'rejected',
  'cancelled',
  'reversed',
] as const
const actions = computed(() =>
  (detail.value?.allowedActions ?? []).filter(
    (action) =>
      ['submit', 'approve', 'reject', 'post', 'cancel'].includes(action) &&
      session.can(permission + ':' + (action === 'reject' ? 'approve' : action)),
  ),
)

async function open(id: string): Promise<void> {
  const current = ++revision
  detail.value = undefined
  detailError.value = ''
  opening.value = true
  try {
    const data = await manufacturingDetail<ProductionDocument>(props.area, id)
    if (current === revision) detail.value = data
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) detailError.value = messageOf(cause)
  } finally {
    if (current === revision) opening.value = false
  }
}

async function saved(document: ProductionDocument): Promise<void> {
  editor.value = undefined
  await Promise.all([load(), open(document.id)])
}

async function transition(action: string): Promise<void> {
  const current = detail.value
  if (!current || busy.value) return
  if (issue && action === 'submit' && !addingWorkflow.value) {
    addingWorkflow.value = true
    workflowReady.value = false
    selections.value = {}
    return
  }
  if (action === 'reject' && !rejection.value) {
    rejection.value = true
    reason.value = ''
    return
  }
  const input = {
    version: current.version,
    workflowAssignees:
      action === 'submit' && Object.keys(selections.value).length ? selections.value : undefined,
    reason: action === 'reject' ? reason.value.trim() : undefined,
  }
  const result = await run(
    action + ':' + current.id,
    input,
    (key) => manufacturingCommand<ProductionDocument>(props.area, current.id, action, input, key),
    '生产单据已更新。',
  )
  if (result) {
    addingWorkflow.value = false
    rejection.value = false
    await saved(result)
  }
}

function start(kind: 'issue' | 'return'): void {
  newDirection.value = kind
  editor.value = 'new'
}

onMounted(() => {
  void load()
  void loadCatalog(['warehouses'])
  if (focusing) void open(focusing)
  if (workOrderId.value && route.query.create && session.can(permission + ':create'))
    start(route.query.create === 'return' ? 'return' : 'issue')
})
</script>

<template>
  <el-alert
    v-if="listError || detailError || commandError"
    :title="listError || detailError || commandError"
    type="error"
    :closable="false"
  />
  <p v-if="notice" role="status">{{ notice }}</p>
  <section class="panel mfg-panel" :aria-label="issue ? '领退料列表' : '完工入库列表'">
    <div class="mfg-toolbar">
      <router-link
        v-if="workOrderId && session.can('mfg_work_order:view')"
        class="text-link"
        :to="'/mfg/work-orders/' + workOrderId"
        >返回来源工单</router-link
      >
      <el-input
        v-model="workOrderId"
        placeholder="按工单编号筛选"
        aria-label="筛选生产工单编号"
        clearable
        @keydown.enter.prevent="load()"
      />
      <ReferenceSelect
        v-if="warehouses.length"
        v-model="warehouseId"
        :rows="warehouses"
        label="筛选作业仓库"
        optional
        @update:model-value="load()"
      />
      <el-select
        v-model="status"
        placeholder="全部状态"
        aria-label="生产单据状态"
        clearable
        @change="load()"
      >
        <el-option
          v-for="key in states"
          :key="key"
          :value="key"
          :label="manufacturingStates[key]"
        />
      </el-select>
      <el-select
        v-if="issue"
        v-model="direction"
        placeholder="领料及退料"
        aria-label="筛选领退料方向"
        clearable
        @change="load()"
      >
        <el-option value="issue" label="领料" /><el-option value="return" label="退料" />
      </el-select>
      <el-button @click="load()">查询</el-button>
      <template v-if="session.can(permission + ':create')">
        <el-button type="primary" @click="start('issue')">{{
          issue ? '新建领料单' : '新建完工入库单'
        }}</el-button>
        <el-button v-if="issue" @click="start('return')">新建退料单</el-button>
      </template>
    </div>
    <el-table v-loading="loading" :data="items" empty-text="暂无生产单据">
      <el-table-column label="单据号" min-width="145"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="open(row.id)">{{
            row.documentNo
          }}</el-button></template
        ></el-table-column
      >
      <el-table-column label="工单" min-width="120"
        ><template #default="{ row }">{{
          row.workOrderNo ?? row.workOrderId
        }}</template></el-table-column
      >
      <el-table-column v-if="issue" label="方向" min-width="85"
        ><template #default="{ row }">{{
          row.direction === 'return' ? '退料' : '领料'
        }}</template></el-table-column
      >
      <el-table-column label="状态" min-width="90"
        ><template #default="{ row }">{{
          manufacturingStates[row.status] ?? '—'
        }}</template></el-table-column
      >
      <el-table-column prop="businessDate" label="业务日期" min-width="115" />
      <el-table-column label="仓库" min-width="125"
        ><template #default="{ row }">{{
          row.warehouseName ?? row.warehouseId
        }}</template></el-table-column
      >
    </el-table>
    <el-button v-if="cursor" class="mfg-list-pager" :loading="loading" @click="load(true)"
      >加载更多</el-button
    >
  </section>
  <p v-if="opening" role="status">正在读取生产单据。</p>
  <section v-if="detail" class="panel mfg-panel" aria-label="生产单据详情">
    <h2>{{ detail.documentNo }}</h2>
    <div class="mfg-toolbar">
      <el-tag>{{ manufacturingStates[detail.status] }}</el-tag>
      <el-tag v-if="detail.overIssue" type="warning">超领</el-tag>
      <el-button
        v-if="detail.status === 'draft' && session.can(permission + ':update')"
        :disabled="busy"
        @click="editor = 'edit'"
        >编辑草稿</el-button
      >
      <el-button
        v-for="action in actions"
        :key="action"
        :type="action === 'post' || action === 'submit' ? 'primary' : undefined"
        :loading="busy"
        @click="transition(action)"
        >{{ manufacturingActions[action] }}</el-button
      >
    </div>
    <el-table :data="detail.lines ?? []" empty-text="暂无生产明细">
      <el-table-column label="商品" min-width="145"
        ><template #default="{ row }">{{
          row.skuName ?? row.skuCode ?? row.skuId
        }}</template></el-table-column
      >
      <el-table-column prop="unitName" label="单位" min-width="85" />
      <el-table-column prop="qty" label="数量" min-width="100" />
      <el-table-column label="批次" min-width="115"
        ><template #default="{ row }">{{
          row.stockSelection?.batchNo ?? row.stockSelection?.batchId ?? '—'
        }}</template></el-table-column
      >
      <el-table-column label="序列号" min-width="125"
        ><template #default="{ row }">{{
          row.stockSelection?.serials?.join('、') || '—'
        }}</template></el-table-column
      >
      <template v-if="session.can('cost:view')">
        <el-table-column label="单位成本" min-width="105" align="right"
          ><template #default="{ row }">{{
            formatMoney(row.unitCost ?? row.estimatedUnitCost)
          }}</template></el-table-column
        >
        <el-table-column label="成本金额" min-width="105" align="right"
          ><template #default="{ row }">{{ formatMoney(row.amount) }}</template></el-table-column
        >
      </template>
    </el-table>
    <WorkflowProgressPanel
      v-if="detail.workflowInstanceId"
      business-type="mfg_issue_doc"
      :business-id="detail.id"
      :instance-id="detail.workflowInstanceId"
    />
  </section>
  <ProductionStockEditor
    v-if="editor"
    :area="area"
    :work-order-id="workOrderId || undefined"
    :source="editor === 'edit' ? detail : undefined"
    :direction="newDirection"
    @close="editor = undefined"
    @saved="saved"
  />
  <el-dialog
    v-if="addingWorkflow && detail"
    :model-value="true"
    title="提交领退料单"
    width="min(560px, 94vw)"
    :close-on-click-modal="false"
    @close="addingWorkflow = false"
  >
    <el-alert v-if="commandError" :title="commandError" type="error" :closable="false" />
    <WorkflowStarterSelector
      v-model="selections"
      business-type="mfg_issue_doc"
      :business-id="detail.id"
      :disabled="busy"
      @ready="workflowReady = $event"
    />
    <template #footer
      ><el-button @click="addingWorkflow = false">取消</el-button
      ><el-button
        type="primary"
        :disabled="!workflowReady"
        :loading="busy"
        @click="transition('submit')"
        >确认提交</el-button
      ></template
    >
  </el-dialog>
  <el-dialog
    v-if="rejection && detail"
    :model-value="true"
    title="驳回生产单据"
    width="min(480px, 94vw)"
    :close-on-click-modal="false"
    @close="rejection = false"
  >
    <el-alert v-if="commandError" :title="commandError" type="error" :closable="false" />
    <el-input
      v-model="reason"
      type="textarea"
      aria-label="驳回原因"
      maxlength="200"
      placeholder="填写驳回原因"
    />
    <template #footer
      ><el-button @click="rejection = false">取消</el-button
      ><el-button
        type="danger"
        :disabled="!reason.trim()"
        :loading="busy"
        @click="transition('reject')"
        >确认驳回</el-button
      ></template
    >
  </el-dialog>
</template>
