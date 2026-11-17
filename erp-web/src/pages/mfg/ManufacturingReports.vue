<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { allRecords } from '../../api/admin'
import { isCancelled, messageOf } from '../../api/http'
import { manufacturingList } from '../../api/manufacturing'
import { useSessionStore } from '../../stores/session'
import type { DataRow } from '../../types/admin'
import { manufacturingStates } from '../../types/manufacturing'
import type { CostRow } from '../../types/manufacturing'
import ManufacturingCostTable from './ManufacturingCostTable.vue'
import ReferenceSelect from './ReferenceSelect.vue'
import { useManufacturingCatalog, useManufacturingList } from './useManufacturing'

type ReportKind = 'progress' | 'material-variance' | 'cost-detail'
interface ReportRow extends Partial<CostRow> {
  id?: string
  skuCode?: string
  skuName?: string
  componentSkuId?: string
  componentSkuCode?: string
  componentSkuName?: string
  status?: string
  planQty?: string
  completedQty?: string
  requiredQty?: string
  issuedQty?: string
  returnedQty?: string
  varianceQty?: string
  planFinishDate?: string
}
const session = useSessionStore()
const kind = ref<ReportKind>('progress')
const warehouseId = ref(''),
  periodId = ref(''),
  workOrderId = ref('')
const periods = ref<DataRow[]>([]),
  periodError = ref('')
const canCost = computed(() => session.can('cost:view'))
const { warehouses, loadCatalog } = useManufacturingCatalog()
const { items, cursor, loading, error, load } = useManufacturingList((cursor) =>
  manufacturingList<ReportRow>(`reports/${kind.value}`, {
    warehouseId: warehouseId.value || undefined,
    periodId: kind.value === 'cost-detail' ? periodId.value || undefined : undefined,
    workOrderId: workOrderId.value.trim() || undefined,
    cursor,
  }),
)

function selectKind(name: string | number): void {
  if (
    name === 'progress' ||
    name === 'material-variance' ||
    (name === 'cost-detail' && canCost.value)
  ) {
    kind.value = name
    void load()
  }
}

onMounted(async () => {
  void load()
  void loadCatalog(['warehouses'])
  if (canCost.value && session.can('accounting_period:view')) {
    try {
      periods.value = await allRecords('accounting-periods', true)
    } catch (cause: unknown) {
      if (!isCancelled(cause)) periodError.value = messageOf(cause)
    }
  }
})
</script>

<template>
  <el-alert
    v-if="error || periodError"
    :title="error || periodError"
    type="error"
    :closable="false"
  />
  <section class="panel mfg-panel" aria-label="生产报表">
    <el-tabs :model-value="kind" @update:model-value="selectKind">
      <el-tab-pane label="工单进度" name="progress" />
      <el-tab-pane label="用料差异" name="material-variance" />
      <el-tab-pane v-if="canCost" label="生产成本明细" name="cost-detail" />
    </el-tabs>
    <el-form class="mfg-toolbar" @submit.prevent="load()">
      <ReferenceSelect
        v-if="warehouses.length"
        v-model="warehouseId"
        :rows="warehouses"
        label="生产报表仓库"
        optional
      />
      <el-input v-model="workOrderId" placeholder="按工单编号筛选" aria-label="生产报表工单编号" />
      <el-select
        v-if="kind === 'cost-detail' && periods.length"
        v-model="periodId"
        clearable
        placeholder="全部期间"
        aria-label="成本明细会计期间"
      >
        <el-option
          v-for="period in periods"
          :key="period.id"
          :value="period.id"
          :label="String(period.period)"
        />
      </el-select>
      <el-button native-type="submit" :loading="loading">查询</el-button>
    </el-form>
    <ManufacturingCostTable
      v-if="kind === 'cost-detail' && canCost"
      v-loading="loading"
      :rows="items as CostRow[]"
    />
    <el-table v-else v-loading="loading" :data="items" empty-text="暂无报表记录">
      <el-table-column label="工单号" min-width="145"
        ><template #default="{ row }">
          <router-link
            v-if="session.can('mfg_work_order:view') && (row.workOrderId || row.id)"
            class="text-link"
            :to="'/mfg/work-orders/' + (row.workOrderId ?? row.id)"
            >{{ row.documentNo ?? row.workOrderId ?? row.id }}</router-link
          >
          <span v-else>{{ row.documentNo ?? row.workOrderId ?? row.id }}</span>
        </template></el-table-column
      >
      <el-table-column :label="kind === 'progress' ? '产成品' : '物料'" min-width="145"
        ><template #default="{ row }">{{
          row.skuName ??
          row.skuCode ??
          row.componentSkuName ??
          row.componentSkuCode ??
          row.componentSkuId ??
          '—'
        }}</template></el-table-column
      >
      <template v-if="kind === 'progress'">
        <el-table-column label="工单状态" min-width="110"
          ><template #default="{ row }">{{
            manufacturingStates[row.status] ?? '—'
          }}</template></el-table-column
        >
        <el-table-column prop="planQty" label="计划数量" min-width="100" />
        <el-table-column prop="completedQty" label="已完工" min-width="100" />
        <el-table-column prop="planFinishDate" label="计划完工日" min-width="115" />
      </template>
      <template v-else>
        <el-table-column prop="requiredQty" label="应领" min-width="95" />
        <el-table-column prop="issuedQty" label="已领" min-width="95" />
        <el-table-column prop="returnedQty" label="已退" min-width="95" />
        <el-table-column prop="varianceQty" label="用料差异" min-width="100" />
      </template>
    </el-table>
    <el-button v-if="cursor" class="mfg-list-pager" :loading="loading" @click="load(true)"
      >加载更多</el-button
    >
  </section>
</template>
