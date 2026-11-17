<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { manufacturingDetail, manufacturingList, saveManufacturing } from '../../api/manufacturing'
import { isCancelled, messageOf } from '../../api/http'
import StockSelectionFields from '../../components/stock-operations/StockSelectionFields.vue'
import { today } from '../../types/sales'
import type {
  ProductionDocument,
  ProductionDraft,
  ProductionLine,
  ProductionLineDraft,
  StockArea,
  WorkMaterial,
  WorkOrder,
} from '../../types/manufacturing'
import { useWmsCommands } from '../wms/useWmsCommands'
import ReferenceSelect from './ReferenceSelect.vue'
import {
  remainingMaterial,
  useManufacturingCatalog,
  useManufacturingList,
} from './useManufacturing'

const props = defineProps<{
  area: StockArea
  workOrderId?: string
  source?: ProductionDocument
  direction?: 'issue' | 'return'
}>()
const emit = defineEmits<{ close: []; saved: [ProductionDocument] }>()
const issue = props.area === 'issues'
const { busy, error, run } = useWmsCommands()
const { warehouses, catalogError, loadCatalog } = useManufacturingCatalog()
const selected = ref<WorkOrder>()
const orderError = ref('')
const readingOrder = ref(false)
const orderKeyword = ref('')
const form = reactive<ProductionDraft>({
  workOrderId: props.source?.workOrderId ?? props.workOrderId ?? '',
  warehouseId: props.source?.warehouseId ?? '',
  businessDate: props.source?.businessDate ?? today(),
  direction: issue ? (props.source?.direction ?? props.direction ?? 'issue') : undefined,
  remark: props.source?.remark ?? '',
  version: props.source?.version,
  lines:
    props.source?.lines?.map((line) => ({
      materialId: line.materialId,
      skuId: line.skuId,
      qty: line.qty,
      stockSelection: line.stockSelection,
      originalIssueLineId: line.originalIssueLineId,
    })) ?? [],
})
const returning = computed(() => issue && form.direction === 'return')
const orders = useManufacturingList((cursor) =>
  manufacturingList<WorkOrder>('work-orders', {
    cursor,
    keyword: orderKeyword.value.trim() || undefined,
  }),
)
const orderOptions = computed(() => {
  const rows = orders.items.value.filter(({ status }) =>
    ['released', 'in_progress', 'completed'].includes(status),
  )
  return selected.value && !rows.some(({ id }) => id === selected.value?.id)
    ? [selected.value, ...rows]
    : rows
})
const warehouseRows = computed(() => {
  const order = selected.value
  if (!order) return []
  const ids = new Set(
    issue
      ? [
          order.issueWarehouseId,
          ...(order.materials ?? []).map((row) => row.issueWarehouseId).filter(Boolean),
        ]
      : [order.receiveWarehouseId],
  )
  return [...ids]
    .filter((id): id is string => typeof id === 'string' && !!id)
    .map((id) => warehouses.value.find((warehouse) => warehouse.id === id) ?? { id })
})

const originalIssues = useManufacturingList((cursor) =>
  manufacturingList<ProductionDocument>('issues', {
    cursor,
    workOrderId: form.workOrderId,
    warehouseId: form.warehouseId,
    status: 'posted',
    direction: 'issue',
  }),
)
const originalDoc = ref('')
const originalLines = ref<ProductionLine[]>([])
const originalError = ref('')
let originalRevision = 0

const materials = computed(() =>
  (selected.value?.materials ?? []).filter(
    (row) => (row.issueWarehouseId || selected.value?.issueWarehouseId) === form.warehouseId,
  ),
)

function freshLine(): ProductionLineDraft {
  return { qty: '', skuId: issue ? undefined : selected.value?.skuId }
}

function fillRemaining(): void {
  form.lines = materials.value.flatMap((row) => {
    const qty = remainingMaterial(row.requiredQty, row.issuedQty, row.returnedQty)
    return qty !== '0' && !qty.startsWith('-')
      ? [{ materialId: row.id, skuId: row.componentSkuId, qty }]
      : []
  })
  if (!form.lines.length) form.lines.push(freshLine())
}

function changeWarehouse(): void {
  originalDoc.value = ''
  originalLines.value = []
  originalRevision++
  if (issue && !returning.value) fillRemaining()
  else form.lines = [freshLine()]
  if (returning.value && form.workOrderId && form.warehouseId) void originalIssues.load()
}

watch(
  () => form.workOrderId,
  async (id, previous, cleanup) => {
    selected.value = undefined
    orderError.value = ''
    if (!id) return
    let current = true
    cleanup(() => {
      current = false
    })
    readingOrder.value = true
    try {
      const order = await manufacturingDetail<WorkOrder>('work-orders', id)
      if (!current) return
      selected.value = order
      if (!props.source || previous !== undefined) {
        form.warehouseId = issue ? order.issueWarehouseId : order.receiveWarehouseId
        changeWarehouse()
      } else if (returning.value && form.warehouseId) {
        void originalIssues.load()
      }
    } catch (cause: unknown) {
      if (current && !isCancelled(cause)) orderError.value = messageOf(cause)
    } finally {
      if (current) readingOrder.value = false
    }
  },
  { immediate: true },
)

async function readOriginal(id: string): Promise<void> {
  const current = ++originalRevision
  originalLines.value = []
  originalError.value = ''
  if (!id) return
  try {
    const result = await manufacturingDetail<ProductionDocument>('issues', id)
    if (current === originalRevision) originalLines.value = result.lines ?? []
  } catch (cause: unknown) {
    if (current === originalRevision && !isCancelled(cause)) originalError.value = messageOf(cause)
  }
}

function selectOriginal(line: ProductionLineDraft): void {
  const original = originalLines.value.find(({ id }) => id === line.originalIssueLineId)
  if (!original) return
  line.materialId = original.materialId
  line.skuId = original.skuId
  line.qty = ''
  line.stockSelection = original.stockSelection?.batchId
    ? { batchId: original.stockSelection.batchId }
    : undefined
}

function materialFor(line: ProductionLineDraft): WorkMaterial | undefined {
  return materials.value.find(({ id }) => id === line.materialId)
}

function chooseMaterial(line: ProductionLineDraft): void {
  const item = materialFor(line)
  line.skuId = item?.componentSkuId
  line.stockSelection = undefined
}

async function save(): Promise<void> {
  if (!selected.value || !form.workOrderId || !form.warehouseId || !form.businessDate) {
    error.value = '请选择工单、仓库和业务日期。'
    return
  }
  if (
    !form.lines.length ||
    form.lines.some(
      (line) =>
        !line.qty.trim() ||
        (issue && !line.materialId) ||
        (returning.value && !line.originalIssueLineId),
    )
  ) {
    error.value = returning.value ? '请填写原领料行和退料数量。' : '请完整填写商品明细和数量。'
    return
  }
  const payload: ProductionDraft = {
    ...form,
    lines: form.lines.map((line) =>
      issue
        ? { ...line, qty: line.qty.trim() }
        : { qty: line.qty.trim(), stockSelection: line.stockSelection },
    ),
  }
  const result = await run('production-draft', payload, (key) =>
    saveManufacturing<ProductionDocument>(props.area, payload, key, props.source?.id),
  )
  if (result) emit('saved', result)
}

onMounted(() => {
  void loadCatalog(['warehouses'])
  if (!props.source && !props.workOrderId) void orders.load()
})
</script>

<template>
  <el-dialog
    :model-value="true"
    :title="(source ? '编辑' : '新建') + (issue ? '生产领退料单' : '完工入库单')"
    width="min(920px, 95vw)"
    :close-on-click-modal="false"
    :show-close="!busy"
    @close="emit('close')"
  >
    <el-alert
      v-if="
        error ||
        orderError ||
        orders.error.value ||
        originalError ||
        originalIssues.error.value ||
        catalogError
      "
      :title="
        error ||
        orderError ||
        orders.error.value ||
        originalError ||
        originalIssues.error.value ||
        catalogError
      "
      type="error"
      :closable="false"
    />
    <el-form label-position="top" :disabled="busy" @submit.prevent="save">
      <div class="mfg-form">
        <el-form-item label="生产工单">
          <el-select
            v-model="form.workOrderId"
            aria-label="作业生产工单"
            placeholder="选择已下达工单"
            :disabled="Boolean(source || workOrderId)"
            :loading="readingOrder || orders.loading.value"
          >
            <el-option
              v-for="item in orderOptions"
              :key="item.id"
              :value="item.id"
              :label="`${item.documentNo} · ${item.skuName ?? item.skuCode ?? item.skuId}`"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="issue" label="作业方向">
          <el-select
            v-model="form.direction"
            aria-label="领退料方向"
            :disabled="Boolean(source)"
            @change="changeWarehouse"
          >
            <el-option value="issue" label="领料" /><el-option value="return" label="退料" />
          </el-select>
        </el-form-item>
        <el-form-item label="作业仓库"
          ><ReferenceSelect
            v-model="form.warehouseId"
            :rows="warehouseRows"
            label="作业仓库"
            :disabled="!selected || !issue"
            @update:model-value="changeWarehouse"
        /></el-form-item>
        <el-form-item label="业务日期"
          ><el-input v-model="form.businessDate" type="date" aria-label="作业业务日期"
        /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" maxlength="200" /></el-form-item>
      </div>
      <div v-if="!source && !workOrderId" class="mfg-toolbar">
        <el-input
          v-model="orderKeyword"
          placeholder="搜索工单号"
          aria-label="搜索待作业工单"
          @keydown.enter.prevent="orders.load()"
        />
        <el-button @click="orders.load()">搜索工单</el-button>
        <el-button
          v-if="orders.cursor.value"
          :loading="orders.loading.value"
          @click="orders.load(true)"
          >加载更多工单</el-button
        >
      </div>
      <template v-if="selected">
        <div v-if="returning" class="mfg-toolbar">
          <el-select
            v-model="originalDoc"
            aria-label="原领料单"
            placeholder="选择已过账的原领料单"
            :loading="originalIssues.loading.value"
            @change="readOriginal"
          >
            <el-option
              v-for="item in originalIssues.items.value"
              :key="item.id"
              :value="item.id"
              :label="item.documentNo"
            />
          </el-select>
          <el-button
            v-if="originalIssues.cursor.value"
            :loading="originalIssues.loading.value"
            @click="originalIssues.load(true)"
            >加载更多原领料单</el-button
          >
        </div>
        <p v-if="!issue">
          产成品 {{ selected.skuName ?? selected.skuCode ?? selected.skuId }} ·
          数量按商品基本单位录入。
        </p>
        <p v-else>领退料数量按所选商品的基本单位录入。</p>
        <div v-for="(line, index) in form.lines" :key="index" class="mfg-lines">
          <div class="mfg-form">
            <el-form-item v-if="returning" label="原领料明细">
              <el-select
                v-model="line.originalIssueLineId"
                aria-label="原领料明细"
                placeholder="请选择原领料行"
                @change="selectOriginal(line)"
              >
                <el-option
                  v-if="
                    line.originalIssueLineId &&
                    !originalLines.some((item) => item.id === line.originalIssueLineId)
                  "
                  :label="line.originalIssueLineId"
                  :value="line.originalIssueLineId"
                />
                <el-option
                  v-for="item in originalLines"
                  :key="item.id"
                  :value="item.id"
                  :label="`${item.skuName ?? item.skuCode ?? item.skuId} · 已领 ${item.qty}`"
                />
              </el-select>
            </el-form-item>
            <template v-else-if="issue">
              <el-form-item label="工单物料">
                <el-select
                  v-model="line.materialId"
                  aria-label="工单物料"
                  filterable
                  placeholder="选择冻结物料"
                  @change="chooseMaterial(line)"
                >
                  <el-option
                    v-for="item in materials"
                    :key="item.id"
                    :value="item.id"
                    :label="item.skuName ?? item.skuCode ?? item.componentSkuId"
                  />
                </el-select>
              </el-form-item>
              <el-form-item v-if="materialFor(line)?.substitutes?.length" label="实领物料">
                <el-select
                  v-model="line.skuId"
                  aria-label="实领或替代物料"
                  filterable
                  placeholder="选择物料"
                  @change="line.stockSelection = undefined"
                >
                  <el-option
                    v-if="materialFor(line)"
                    :value="materialFor(line)!.componentSkuId"
                    label="原物料"
                  />
                  <el-option
                    v-for="item in materialFor(line)?.substitutes ?? []"
                    :key="item.substituteSkuId"
                    :value="item.substituteSkuId"
                    :label="`${item.skuName ?? item.skuCode ?? item.substituteSkuId} · 换算 ${item.ratio}`"
                  />
                </el-select>
              </el-form-item>
            </template>
            <el-form-item label="数量"
              ><el-input v-model="line.qty" inputmode="decimal" aria-label="作业数量"
            /></el-form-item>
          </div>
          <div v-if="form.warehouseId && (line.skuId || !issue)" class="mfg-form-extra">
            <StockSelectionFields
              v-model="line.stockSelection"
              :warehouse-id="form.warehouseId"
              :sku-id="line.skuId || selected.skuId"
              :mode="returning ? 'return' : issue ? 'out' : 'in'"
            />
          </div>
          <el-button link type="danger" @click="form.lines.splice(index, 1)">移除明细</el-button>
        </div>
        <div class="mfg-toolbar">
          <el-button @click="form.lines.push(freshLine())">添加明细</el-button>
          <el-button v-if="issue && !returning" @click="fillRemaining">带出本仓待领物料</el-button>
        </div>
      </template>
      <div class="mfg-toolbar">
        <el-button native-type="submit" type="primary" :disabled="!selected" :loading="busy"
          >保存草稿</el-button
        >
        <el-button @click="emit('close')">取消</el-button>
      </div>
    </el-form>
  </el-dialog>
</template>
