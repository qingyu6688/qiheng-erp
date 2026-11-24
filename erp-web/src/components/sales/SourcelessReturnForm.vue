<script setup lang="ts">
import { computed, onMounted, onScopeDispose, reactive, ref, watch } from 'vue'
import { allRecords, record } from '../../api/admin'
import { isCancelled, messageOf } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import type { DataRow } from '../../types/admin'
import type { SalesDocument, SalesSkuUnit } from '../../types/sales'
import { today } from '../../types/sales'
import type { SourcelessReturnInput } from '../../types/sourceless-returns'
import {
  newReturnLine,
  returnDraft,
  returnError,
  returnInput,
} from '../../utils/sourceless-returns'
import StockSelectionFields from '../stock-operations/StockSelectionFields.vue'
import BusinessUserSelect from '../BusinessUserSelect.vue'

const props = defineProps<{ original?: SalesDocument; locked: boolean }>()
const emit = defineEmits<{
  save: [input: SourcelessReturnInput]
  cancel: []
  dirty: [value: boolean]
}>()
const session = useSessionStore(),
  form = reactive(returnDraft(today(), props.original))
const originCompany = session.companyId,
  originUser = session.user?.id
const initial = JSON.stringify(form),
  error = ref(''),
  loading = ref(true)
const partners = ref<DataRow[]>([]),
  warehouses = ref<DataRow[]>([]),
  skus = ref<DataRow[]>([])
const units = reactive<Record<string, SalesSkuUnit[]>>({})
const canCost = computed(() => session.can('cost:view'))
const allowed = computed(
  () =>
    Boolean(session.companyId) &&
    session.companyId === originCompany &&
    session.user?.id === originUser &&
    session.can('sales_return:view') &&
    session.can('sales_return:sourceless') &&
    session.can(`sales_return:${props.original ? 'update' : 'create'}`),
)
let revision = 0
watch(
  () => JSON.stringify(form),
  (value) => emit('dirty', value !== initial),
  { immediate: true },
)
watch(
  canCost,
  (value) => {
    if (!value)
      for (const line of form.lines) {
        delete line.manualUnitCost
        delete line.manualCostReason
      }
  },
  { immediate: true },
)
watch(
  () => form.warehouseId,
  () => {
    for (const line of form.lines) line.stockSelection = null
  },
)
watch(
  () => [session.companyId, session.user?.id],
  () => {
    revision++
    partners.value = []
    warehouses.value = []
    skus.value = []
    Object.assign(form, {
      customerId: '',
      warehouseId: '',
      reason: '',
      legacyReference: '',
      salesUserId: null,
      lines: [],
    })
    emit('cancel')
  },
)

async function skuUnits(skuId: string): Promise<void> {
  if (!skuId || !session.can('sku:view')) return
  const current = revision
  const sku = await record('skus/' + encodeURIComponent(skuId))
  if (current !== revision) return
  units[skuId] = Array.isArray(sku.units) ? (sku.units as SalesSkuUnit[]) : []
}
async function chooseSku(line: SourcelessReturnInput['lines'][number]): Promise<void> {
  const skuId = line.skuId
  line.unitId = ''
  line.stockSelection = null
  try {
    await skuUnits(skuId)
    if (line.skuId !== skuId || !form.lines.includes(line)) return
    line.unitId = units[skuId]?.find((unit) => unit.isBase)?.unitId ?? ''
    const sku = skus.value.find((item) => item.id === skuId)
    line.taxRate = String(sku?.defaultTaxRate ?? '0.13')
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  }
}
onMounted(async () => {
  const current = ++revision
  try {
    const values = await Promise.all([
      session.can('partner:view') ? allRecords('partners') : [],
      session.can('warehouse:view') ? allRecords('warehouses', true) : [],
      session.can('sku:view') ? allRecords('skus') : [],
    ])
    if (current !== revision) return
    ;[partners.value, warehouses.value, skus.value] = values
    await Promise.all([...new Set(form.lines.map((line) => line.skuId))].map(skuUnits))
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
})
onScopeDispose(() => {
  revision++
})
function save(): void {
  if (props.locked || loading.value || !allowed.value) return
  const input = returnInput(form, canCost.value)
  error.value = returnError(input)
  if (!error.value) emit('save', input)
}
</script>

<template>
  <section class="sourceless-form">
    <h2>{{ original ? '更正无原单退货' : '新建无原单退货' }}</h2>
    <p class="muted">
      按历史凭据退回，不占用本系统发货数量。保存草稿后可预览当前成本，提交后由另一人复核。
    </p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form label-position="top" :disabled="locked || loading || !allowed" @submit.prevent="save">
      <div class="sourceless-fields">
        <el-form-item label="客户"
          ><el-select
            v-if="partners.length"
            v-model="form.customerId"
            aria-label="退货客户"
            filterable
            ><el-option
              v-for="item in partners"
              :key="item.id"
              :value="item.id"
              :label="String(item.name)" /></el-select
          ><el-input v-else v-model="form.customerId" aria-label="退货客户编号"
        /></el-form-item>
        <el-form-item label="退回仓库"
          ><el-select
            v-if="warehouses.length"
            v-model="form.warehouseId"
            aria-label="退回仓库"
            filterable
            ><el-option
              v-for="item in warehouses"
              :key="item.id"
              :value="item.id"
              :label="String(item.name)" /></el-select
          ><el-input v-else v-model="form.warehouseId" aria-label="退回仓库编号"
        /></el-form-item>
        <el-form-item label="退货日期"
          ><el-input v-model="form.businessDate" type="date" aria-label="退货日期"
        /></el-form-item>
        <BusinessUserSelect
          :model-value="form.salesUserId ?? ''"
          :disabled="locked || loading || !allowed"
          label="业务员"
          @update:model-value="form.salesUserId = $event || null"
        />
        <el-form-item label="历史依据"
          ><el-input
            v-model="form.legacyReference"
            aria-label="历史依据"
            maxlength="500"
            placeholder="旧系统单号或历史凭据说明"
        /></el-form-item>
        <el-form-item label="退货原因"
          ><el-input v-model="form.reason" aria-label="退货原因" maxlength="200"
        /></el-form-item>
      </div>
      <el-table :data="form.lines" empty-text="请添加退货商品" class="return-edit-lines">
        <el-table-column label="商品" min-width="180"
          ><template #default="{ row }"
            ><el-select
              v-if="skus.length"
              v-model="row.skuId"
              aria-label="退货商品"
              filterable
              @change="chooseSku(row)"
              ><el-option
                v-for="item in skus"
                :key="item.id"
                :value="item.id"
                :label="String(item.code) + ' ' + String(item.name)" /></el-select
            ><el-input
              v-else
              v-model="row.skuId"
              aria-label="退货商品编号"
              @change="chooseSku(row)" /></template
        ></el-table-column>
        <el-table-column label="单位" min-width="115"
          ><template #default="{ row }"
            ><el-select v-if="units[row.skuId]?.length" v-model="row.unitId" aria-label="退货单位"
              ><el-option
                v-for="unit in units[row.skuId]"
                :key="unit.unitId"
                :value="unit.unitId"
                :label="unit.unitName" /></el-select
            ><el-input v-else v-model="row.unitId" aria-label="退货单位编号" /></template
        ></el-table-column>
        <el-table-column label="数量" min-width="120"
          ><template #default="{ row }"
            ><el-input v-model="row.qty" aria-label="退货数量" inputmode="decimal" /></template
        ></el-table-column>
        <el-table-column label="含税退货单价" min-width="150"
          ><template #default="{ row }"
            ><el-input
              v-model="row.unitPrice"
              aria-label="含税退货单价"
              inputmode="decimal" /></template
        ></el-table-column>
        <el-table-column label="税率" min-width="100"
          ><template #default="{ row }"
            ><el-input v-model="row.taxRate" aria-label="退货税率" inputmode="decimal" /></template
        ></el-table-column>
        <el-table-column label="质检结果" min-width="120"
          ><template #default="{ row }"
            ><el-select v-model="row.qualityResult" aria-label="退货质检"
              ><el-option label="合格" value="passed" /><el-option
                label="待检"
                value="pending" /><el-option label="不合格" value="failed" /></el-select></template
        ></el-table-column>
        <el-table-column v-if="canCost" label="补填基本单位成本及依据" min-width="240"
          ><template #default="{ row }"
            ><el-input
              v-model="row.manualUnitCost"
              aria-label="补填基本单位成本"
              inputmode="decimal"
              placeholder="仅缺少自动来源时填写" /><el-input
              v-model="row.manualCostReason"
              aria-label="补填成本依据"
              maxlength="200"
              placeholder="成本依据，零成本也须说明" /></template
        ></el-table-column>
        <el-table-column label="批次、库位与序列号" min-width="220"
          ><template #default="{ row }"
            ><StockSelectionFields
              v-model="row.stockSelection"
              :warehouse-id="form.warehouseId"
              :sku-id="row.skuId"
              mode="in" /></template
        ></el-table-column>
        <el-table-column label="操作" width="75" fixed="right"
          ><template #default="{ $index }"
            ><el-button text @click="form.lines.splice($index, 1)">移除</el-button></template
          ></el-table-column
        >
      </el-table>
      <div class="form-actions">
        <el-button :disabled="form.lines.length >= 500" @click="form.lines.push(newReturnLine())"
          >添加商品</el-button
        ><span class="muted">共 {{ form.lines.length }} 行，税率0.13表示13%</span
        ><el-button type="primary" native-type="submit" :loading="locked">保存草稿</el-button
        ><el-button @click="emit('cancel')">取消更正</el-button>
      </div>
    </el-form>
  </section>
</template>

<style scoped>
.sourceless-fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 16px;
}
.sourceless-fields :deep(.el-select) {
  width: 100%;
}
.form-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-block: 16px;
}
@media (max-width: 767px) {
  .sourceless-fields {
    grid-template-columns: 1fr;
  }
}
</style>
