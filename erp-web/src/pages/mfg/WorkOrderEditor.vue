<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { manufacturingList, orderFromSales, saveManufacturing } from '../../api/manufacturing'
import { isCancelled, messageOf } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import type { Bom, SalesWorkDraft, WorkOrder, WorkOrderDraft } from '../../types/manufacturing'
import type { SalesDocument } from '../../types/sales'
import { today } from '../../types/sales'
import { useWmsCommands } from '../wms/useWmsCommands'
import ReferenceSelect from './ReferenceSelect.vue'
import { useManufacturingCatalog } from './useManufacturing'

const props = defineProps<{ source?: WorkOrder; sales?: SalesDocument }>()
const emit = defineEmits<{ close: []; saved: [WorkOrder] }>()
const session = useSessionStore()
const { busy, error, run } = useWmsCommands()
const { warehouses, skus, units, unitsFor, loadCatalog, catalogError } = useManufacturingCatalog()
const billError = ref('')
const bills = ref<Bom[]>([])
const salesOrderLineId = ref('')
const sourceLine = computed(() =>
  props.sales?.lines?.find((line) => line.id === salesOrderLineId.value),
)
const draft = reactive<WorkOrderDraft>({
  skuId: props.source?.skuId ?? '',
  bomId: props.source?.bomId ?? '',
  unitId: props.source?.unitId ?? '',
  planQty: props.source?.planQty ?? '',
  issueWarehouseId: props.source?.issueWarehouseId ?? '',
  receiveWarehouseId: props.source?.receiveWarehouseId ?? '',
  planStartDate: props.source?.planStartDate ?? today(),
  planFinishDate: props.source?.planFinishDate ?? today(),
  overReceivePct: props.source?.overReceivePct ?? '0',
  remark: props.source?.remark ?? '',
  version: props.source?.version,
})

watch(sourceLine, (line) => {
  if (!line) return
  draft.skuId = line.skuId ?? line.sku?.id ?? ''
  draft.bomId = ''
  if (line.warehouseId || line.warehouse?.id) {
    draft.receiveWarehouseId = line.warehouseId ?? line.warehouse?.id ?? ''
    if (!draft.issueWarehouseId) draft.issueWarehouseId = draft.receiveWarehouseId
  }
})

watch(
  () => draft.skuId,
  async (sku, previous, cleanup) => {
    if (previous !== undefined) {
      draft.unitId = ''
      draft.bomId = ''
    }
    bills.value = []
    billError.value = ''
    if (!sku) return
    let current = true
    cleanup(() => {
      current = false
    })
    if (!props.sales) {
      const options = await unitsFor(sku)
      if (current && !draft.unitId) draft.unitId = options.find((item) => item.isBase)?.unitId ?? ''
    }
    if (!current || !session.can('mfg_bom:view')) return
    try {
      const result = await manufacturingList<Bom>('boms', {
        status: 'approved',
        skuId: sku,
        limit: 100,
      })
      if (current) bills.value = result.items
    } catch (cause: unknown) {
      if (current && !isCancelled(cause)) billError.value = messageOf(cause)
    }
  },
  { immediate: true },
)

async function save(): Promise<void> {
  if (
    !draft.issueWarehouseId ||
    !draft.receiveWarehouseId ||
    !draft.planStartDate ||
    !draft.planFinishDate
  ) {
    error.value = '请填写领料仓、完工仓和计划日期。'
    return
  }
  if (props.sales) {
    if (!sourceLine.value) {
      error.value = '请选择销售订单行。'
      return
    }
    const payload: SalesWorkDraft = {
      salesOrderId: props.sales.id,
      salesOrderLineId: sourceLine.value.id,
      bomId: draft.bomId || undefined,
      issueWarehouseId: draft.issueWarehouseId,
      receiveWarehouseId: draft.receiveWarehouseId,
      planStartDate: draft.planStartDate,
      planFinishDate: draft.planFinishDate,
      overReceivePct: draft.overReceivePct || '0',
      remark: draft.remark,
    }
    const result = await run('sales-work-order', payload, (key) => orderFromSales(payload, key))
    if (result) emit('saved', result)
    return
  }
  if (!draft.skuId || !draft.unitId || !draft.planQty.trim()) {
    error.value = '请填写产成品、计量单位和计划数量。'
    return
  }
  const input = {
    ...draft,
    bomId: draft.bomId || undefined,
    planQty: draft.planQty.trim(),
    overReceivePct: draft.overReceivePct || '0',
  }
  const result = await run('work-order-save', input, (key) =>
    saveManufacturing<WorkOrder>('work-orders', input, key, props.source?.id),
  )
  if (result) emit('saved', result)
}

onMounted(() => {
  void loadCatalog(props.sales ? ['warehouses'] : ['warehouses', 'skus'])
})
</script>

<template>
  <el-dialog
    :model-value="true"
    :title="sales ? '从销售订单生成工单' : source ? '编辑生产工单' : '新建生产工单'"
    width="min(760px, 95vw)"
    :close-on-click-modal="false"
    :show-close="!busy"
    @close="emit('close')"
  >
    <el-alert
      v-if="error || catalogError || billError"
      :title="error || catalogError || billError"
      type="error"
      :closable="false"
    />
    <el-form label-position="top" :disabled="busy" @submit.prevent="save">
      <div class="mfg-form">
        <el-form-item v-if="sales" label="销售订单行">
          <el-select
            v-model="salesOrderLineId"
            aria-label="销售订单行"
            placeholder="选择待生产的商品行"
          >
            <el-option
              v-for="line in sales.lines ?? []"
              :key="line.id"
              :value="line.id"
              :label="`${line.sku?.code ?? ''} ${line.skuName ?? line.sku?.name ?? line.skuId} · 单据量 ${line.qty ?? line.baseQty ?? '—'}`"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="产成品"
          ><ReferenceSelect v-model="draft.skuId" :rows="skus" label="产成品"
        /></el-form-item>
        <el-form-item label="BOM 版本">
          <el-select
            v-if="session.can('mfg_bom:view')"
            v-model="draft.bomId"
            aria-label="工单 BOM 版本"
            clearable
            placeholder="留空使用生效的默认版本"
          >
            <el-option
              v-for="bill in bills"
              :key="bill.id"
              :label="bill.versionNo + (bill.isDefault ? '（默认）' : '')"
              :value="bill.id"
            />
          </el-select>
          <el-input
            v-else
            v-model="draft.bomId"
            aria-label="工单 BOM 版本"
            placeholder="按需输入 BOM 编号"
          />
        </el-form-item>
        <template v-if="!sales">
          <el-form-item label="计量单位">
            <el-select
              v-if="session.can('sku:view')"
              v-model="draft.unitId"
              aria-label="工单计量单位"
              placeholder="请选择单位"
            >
              <el-option
                v-for="unit in units[draft.skuId] ?? []"
                :key="unit.unitId"
                :label="unit.unitName"
                :value="unit.unitId"
              />
            </el-select>
            <el-input
              v-else
              v-model="draft.unitId"
              aria-label="工单计量单位"
              placeholder="输入单位编号"
            />
          </el-form-item>
          <el-form-item label="计划数量"
            ><el-input v-model="draft.planQty" aria-label="工单计划数量" inputmode="decimal"
          /></el-form-item>
        </template>
        <el-form-item label="领料仓"
          ><ReferenceSelect v-model="draft.issueWarehouseId" :rows="warehouses" label="工单领料仓"
        /></el-form-item>
        <el-form-item label="完工仓"
          ><ReferenceSelect
            v-model="draft.receiveWarehouseId"
            :rows="warehouses"
            label="工单完工仓"
            :disabled="Boolean(sales)"
        /></el-form-item>
        <el-form-item label="计划开工"
          ><el-input v-model="draft.planStartDate" aria-label="计划开工日期" type="date"
        /></el-form-item>
        <el-form-item label="计划完工"
          ><el-input v-model="draft.planFinishDate" aria-label="计划完工日期" type="date"
        /></el-form-item>
        <el-form-item label="超产比例上限（%）"
          ><el-input v-model="draft.overReceivePct" aria-label="超产比例上限" inputmode="decimal"
        /></el-form-item>
        <el-form-item label="备注"
          ><el-input v-model="draft.remark" maxlength="200"
        /></el-form-item>
      </div>
      <p v-if="sales">工单数量按所选销售行提交时的缺货量计算。</p>
      <div class="mfg-toolbar">
        <el-button native-type="submit" type="primary" :loading="busy">{{
          sales ? '生成工单' : '保存工单'
        }}</el-button>
        <el-button @click="emit('close')">取消</el-button>
      </div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.mfg-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(220px, 100%), 1fr));
  gap: 0 14px;
}
.mfg-form :deep(.el-select) {
  width: 100%;
}
.mfg-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.mfg-toolbar :deep(.el-button) {
  margin-left: 0;
}
</style>
