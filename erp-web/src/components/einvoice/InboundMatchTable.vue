<script setup lang="ts">
import type { InboundMatch } from '../../types/inbound-invoices'
import { formatDateTime } from '../../utils/format'
import DocumentLineTable from '../DocumentLineTable.vue'
import FinancialAmount from '../FinancialAmount.vue'

defineProps<{ rows: InboundMatch[]; historical?: boolean }>()
const labels: Record<string, string> = {
  goods_receipt_line: '收货商品',
  purchase_order_fee: '随单费用',
  price_adjustment_line: '采购调价',
  purchase_fee_doc_line: '承运商费用',
}
</script>
<template>
  <DocumentLineTable
    :rows="rows"
    role="region"
    :aria-label="historical ? '已释放核对历史' : '当前核对分配'"
  >
    <el-table-column label="采购来源" min-width="230"
      ><template #default="{ row }">
        <strong>{{ row.sourceNo ?? '来源单据 #' + row.sourceId }}</strong>
        <p class="match-caption">
          {{ labels[row.sourceType] ?? '采购来源' }} · {{ row.businessDate }}
        </p>
        <p v-if="row.legacyMigrated" class="match-caption">旧版迁移，核对日期按旧更新时间还原</p>
        <p v-if="historical" class="match-caption">
          核对 {{ formatDateTime(row.matchedAt) }}<br />释放 {{ formatDateTime(row.releasedAt) }}
        </p>
      </template></el-table-column
    >
    <el-table-column label="票面未税额" min-width="130" align="right"
      ><template #default="{ row }"><FinancialAmount :value="row.invoiceUntaxedAmount" /></template
    ></el-table-column>
    <el-table-column label="票面税额" min-width="120" align="right"
      ><template #default="{ row }"><FinancialAmount :value="row.invoiceTaxAmount" /></template
    ></el-table-column>
    <el-table-column label="覆盖未税额" min-width="130" align="right"
      ><template #default="{ row }"><FinancialAmount :value="row.payableUntaxedAmount" /></template
    ></el-table-column>
    <el-table-column label="覆盖税额" min-width="120" align="right"
      ><template #default="{ row }"><FinancialAmount :value="row.payableTaxAmount" /></template
    ></el-table-column>
    <el-table-column label="未税差异" min-width="120" align="right"
      ><template #default="{ row }"
        ><FinancialAmount :value="row.untaxedDifference" signed /></template
    ></el-table-column>
    <el-table-column label="税额差异" min-width="120" align="right"
      ><template #default="{ row }"><FinancialAmount :value="row.taxDifference" signed /></template
    ></el-table-column>
    <el-table-column v-if="historical" label="释放原因" min-width="220" prop="releasedReason" />
  </DocumentLineTable>
</template>
<style scoped>
.match-caption {
  margin: 5px 0 0;
  color: var(--erp-text-2);
  font-size: 12px;
}
</style>
