<script setup lang="ts">
import { formatMoney } from '../../utils/format'
import type { CostRow } from '../../types/manufacturing'

defineProps<{ rows: CostRow[]; varianceOnly?: boolean }>()
const total = [
  { key: 'materialCost', label: '本期材料' },
  { key: 'laborCost', label: '直接人工' },
  { key: 'overheadCost', label: '制造费用' },
  { key: 'wipBegin', label: '期初在制' },
  { key: 'wipEnd', label: '期末在制' },
] as const
const variance = [
  { key: 'finishedCost', label: '完工实际成本' },
  { key: 'estimatedCost', label: '估算入库成本' },
  { key: 'variance', label: '完工成本差异' },
  { key: 'inventoryVariance', label: '在库差异' },
  { key: 'issuedVariance', label: '已出库差异' },
  { key: 'shortCloseExpense', label: '短交成本差异' },
] as const
</script>

<template>
  <el-table :data="rows" empty-text="暂无生产成本明细">
    <el-table-column label="工单" min-width="145" fixed>
      <template #default="{ row }">{{ row.documentNo ?? row.workOrderId }}</template>
    </el-table-column>
    <el-table-column
      v-if="rows.some((row) => row.period)"
      prop="period"
      label="会计期间"
      min-width="100"
    />
    <template v-if="!varianceOnly">
      <el-table-column
        v-for="column in total"
        :key="column.key"
        :label="column.label"
        min-width="100"
        align="right"
      >
        <template #default="{ row }">{{ formatMoney(row[column.key]) }}</template>
      </el-table-column>
    </template>
    <el-table-column
      v-for="column in variance"
      :key="column.key"
      :label="column.label"
      min-width="114"
      align="right"
    >
      <template #default="{ row }">{{ formatMoney(row[column.key]) }}</template>
    </el-table-column>
  </el-table>
</template>
