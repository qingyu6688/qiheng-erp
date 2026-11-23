<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { procurementDocument } from '../../api/procurement'
import { isCancelled, messageOf } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import type { ProcurementDocument } from '../../types/procurement'
import { documentStateLabel, documentStateTag } from '../../types/document-ui'
import { formatQuantity } from '../../utils/format'
import DocumentLineTable from '../DocumentLineTable.vue'
import FinancialAmount from '../FinancialAmount.vue'
import StatusTag from '../StatusTag.vue'

const props = defineProps<{ id: string }>()
const emit = defineEmits<{ close: [] }>()
const session = useSessionStore()
const value = ref<ProcurementDocument>(),
  loading = ref(false),
  error = ref('')
let request: AbortController | undefined
let revision = 0
async function load(): Promise<void> {
  const current = ++revision
  request?.abort()
  request = new AbortController()
  value.value = undefined
  loading.value = true
  error.value = ''
  try {
    const document = await procurementDocument('goods-receipts', props.id, request.signal)
    if (current === revision) value.value = document
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
}
function money(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined
}
watch(
  () => [props.id, session.companyId],
  () => {
    void load()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  revision++
  request?.abort()
})
</script>

<template>
  <el-drawer
    :model-value="true"
    :title="value ? '采购收货 · ' + value.documentNo : '采购收货详情'"
    direction="rtl"
    size="min(1100px, 96vw)"
    append-to-body
    destroy-on-close
    @close="emit('close')"
  >
    <div class="source-detail" :aria-busy="loading">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <p v-if="loading" role="status">正在读取收货单…</p>
      <template v-else-if="value">
        <StatusTag
          class="source-state"
          :label="documentStateLabel('goods-receipts', value.status)"
          :tone="documentStateTag('goods-receipts', value.status)"
        />
        <dl class="source-summary">
          <div>
            <dt>供应商</dt>
            <dd>{{ value.supplierName ?? value.supplier?.name ?? '—' }}</dd>
          </div>
          <div>
            <dt>收货仓库</dt>
            <dd>{{ value.warehouseName ?? value.warehouse?.name ?? '—' }}</dd>
          </div>
          <div>
            <dt>业务日期</dt>
            <dd>{{ value.businessDate ?? '—' }}</dd>
          </div>
          <div>
            <dt>含税应付</dt>
            <dd><FinancialAmount :value="value.payableAmount ?? value.amount" /></dd>
          </div>
          <div>
            <dt>税额</dt>
            <dd><FinancialAmount :value="value.taxAmount" /></dd>
          </div>
          <div v-if="value.dueDate">
            <dt>到期日</dt>
            <dd>{{ value.dueDate }}</dd>
          </div>
        </dl>
        <DocumentLineTable :rows="value.lines ?? []">
          <el-table-column label="商品" min-width="200"
            ><template #default="{ row }"
              ><strong>{{ row.skuName ?? row.sku?.name ?? '—' }}</strong></template
            ></el-table-column
          >
          <el-table-column label="数量" min-width="120" align="right"
            ><template #default="{ row }"
              >{{ formatQuantity(row.qty) }} {{ row.unitName ?? row.unit?.name }}</template
            ></el-table-column
          >
          <el-table-column label="含税应付" min-width="140" align="right"
            ><template #default="{ row }"
              ><FinancialAmount :value="money(row.payableAmount ?? row.payableNet)" /></template
          ></el-table-column>
          <el-table-column label="税额" min-width="120" align="right"
            ><template #default="{ row }"
              ><FinancialAmount :value="money(row.taxAmount)" /></template
          ></el-table-column>
          <el-table-column
            v-if="session.can('cost:view')"
            label="含费用成本"
            min-width="140"
            align="right"
            ><template #default="{ row }"
              ><FinancialAmount :value="money(row.landedCostAmount ?? row.costAmount)" /></template
          ></el-table-column>
        </DocumentLineTable>
        <p v-if="value.remark" class="source-remark">备注：{{ value.remark }}</p>
      </template>
      <el-button v-if="!loading" @click="load">刷新单据</el-button>
    </div>
    <template #footer><el-button @click="emit('close')">关闭</el-button></template>
  </el-drawer>
</template>

<style scoped>
.source-detail {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 20px;
}
.source-state {
  justify-self: start;
}
.source-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 24px;
  margin: 0;
}
.source-summary dt {
  color: var(--erp-text-2);
  font-size: 12px;
  margin-bottom: 10px;
}
.source-summary dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.source-remark {
  color: var(--erp-text-2);
}
@media (max-width: 640px) {
  .source-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
