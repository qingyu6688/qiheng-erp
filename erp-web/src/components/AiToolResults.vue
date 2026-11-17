<script setup lang="ts">
import { computed } from 'vue'
import type { AiToolResult } from '../types/ai'
import { reportLabels } from '../types/reporting'
import { formatDateTime } from '../utils/format'

const props = defineProps<{ runs: AiToolResult[] }>()
const emit = defineEmits<{ clarify: [text: string] }>()
const columnLabels: Record<string, string> = {
  ...reportLabels,
  id: '编号',
  code: '编码',
  name: '名称',
  departmentName: '部门',
  documentNo: '单号',
  orderNo: '订单号',
  status: '状态',
  totalAmount: '总金额',
  customerName: '客户',
  supplierName: '供应商',
  receivedQty: '已收货数量',
  orderedQty: '订单数量',
  shippedQty: '已发货数量',
  signedQty: '已签收数量',
  netBalance: '余额',
  balance: '余额',
  currency: '币种',
  matchingField: '匹配字段',
  lineCount: '明细行数',
  lineNo: '行号',
  unit: '单位',
  baseQty: '订单数量',
  pendingShipQty: '待发货数量',
  pendingReceiveQty: '待收货数量',
  returnedQty: '已退货数量',
  closedQty: '已关闭数量',
  shippableQty: '可发货数量',
  receivableQty: '可收货数量',
  netAvailableQty: '扣减需求后可用量',
}
const metadata = new Set(['detailUrl', 'sourceUrl', 'detailPath', 'link', 'lines'])
const progressFields = [
  'baseQty',
  'pendingShipQty',
  'shippedQty',
  'signedQty',
  'shippableQty',
  'pendingReceiveQty',
  'receivedQty',
  'receivableQty',
  'returnedQty',
  'closedQty',
]
const results = computed(() =>
  props.runs.map((run) => ({
    ...run,
    columns: [...new Set(run.rows.flatMap(Object.keys))].filter((key) => !metadata.has(key)),
    totals: Object.entries(run.summary ?? {}).filter(([key]) => columnLabels[key]),
    detail: localLink(run.detailUrl),
  })),
)

function localLink(value: unknown): string | undefined {
  if (typeof value !== 'string' || !/^\/[a-z0-9-]+(?:\/[a-z0-9-]+)*(?:\?[^#\\\s]*)?$/.test(value))
    return undefined
  const path = value.split('?')[0]
  const allowed = new Set([
    'reports',
    'inventory-balances',
    'sales-orders',
    'shipments',
    'sales-returns',
    'purchase-orders',
    'purchase-requisitions',
    'purchase-returns',
    'goods-receipts',
    'receipts',
    'payments',
    'account-transfers',
    'refund-requests',
    'inventory-trace',
    'opening-entries',
    'stock-transfers',
    'stock-counts',
    'stock-adjustments',
    'invoice-registrations',
    'document-reversals',
    'stock-misc-in',
    'stock-misc-out',
    'document-browser',
  ])
  return allowed.has(path?.split('/')[1] ?? '') ? value : undefined
}
function rowLink(row: Record<string, unknown>): string | undefined {
  return localLink(row.detailUrl ?? row.sourceUrl ?? row.detailPath ?? row.link)
}

function fields(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {}
}
function orderLines(row: Record<string, unknown>): Record<string, unknown>[] {
  if (!Array.isArray(row.lines)) return []
  return row.lines.map((value: unknown) => {
    const line = fields(value)
    const progress = fields(line.progress)
    return {
      lineNo: line.lineNo,
      skuName: fields(line.sku).name,
      unit: fields(line.unit).name,
      warehouseName: fields(line.warehouse).name,
      ...Object.fromEntries(
        progressFields
          .filter((key) => Object.hasOwn(progress, key))
          .map((key) => [key, progress[key]]),
      ),
    }
  })
}
function orderColumns(row: Record<string, unknown>): string[] {
  return [...new Set(orderLines(row).flatMap(Object.keys))]
}

function displayValue(value: unknown): string {
  return value === null || value === undefined
    ? '—'
    : typeof value === 'object'
      ? JSON.stringify(value)
      : String(value)
}
</script>

<template>
  <section v-for="(result, index) in results" :key="result.code + index" class="ai-tool-result">
    <header class="ai-tool-header">
      <h4>{{ result.label }}</h4>
      <router-link v-if="result.detail" :to="result.detail" class="text-link">查看来源</router-link>
    </header>
    <p v-if="result.asOf" class="ai-data-time">数据时间 {{ formatDateTime(result.asOf) }}</p>
    <template v-if="result.status === 'ambiguous'">
      <p>找到多个候选，请补充编码后重新查询。</p>
      <ul v-if="result.candidates?.length" class="ai-candidates">
        <li v-for="(candidate, candidateIndex) in result.candidates" :key="candidateIndex">
          <el-button
            link
            type="primary"
            v-if="candidate.code"
            @click="emit('clarify', candidate.code)"
          >
            {{ candidate.code ? candidate.code + ' · ' : '' }}{{ candidate.name }}
          </el-button>
          <span v-else>{{ candidate.name }}</span>
        </li>
      </ul>
    </template>
    <dl v-if="result.totals.length" class="ai-tool-totals">
      <div v-for="[key, value] in result.totals" :key="key">
        <dt>{{ columnLabels[key] }}</dt>
        <dd>{{ displayValue(value) }}</dd>
      </div>
    </dl>
    <el-table
      v-if="result.status !== 'ambiguous' && result.rows.length"
      :data="result.rows"
      size="small"
      max-height="320"
    >
      <el-table-column v-if="result.rows.some((row) => orderLines(row).length)" type="expand">
        <template #default="{ row }">
          <el-table :data="orderLines(row)" size="small" empty-text="暂无订单明细">
            <el-table-column
              v-for="column in orderColumns(row)"
              :key="column"
              :label="columnLabels[column]"
              min-width="106"
            >
              <template #default="{ row: line }">{{ displayValue(line[column]) }}</template>
            </el-table-column>
          </el-table>
        </template>
      </el-table-column>
      <el-table-column
        v-for="column in result.columns"
        :key="column"
        :label="columnLabels[column] ?? column"
        min-width="100"
      >
        <template #default="{ row }">{{ displayValue(row[column]) }}</template>
      </el-table-column>
      <el-table-column v-if="result.rows.some(rowLink)" label="来源" width="100">
        <template #default="{ row }">
          <router-link v-if="rowLink(row)" :to="rowLink(row)!" class="text-link"
            >查看单据</router-link
          >
        </template>
      </el-table-column>
    </el-table>
    <p v-else-if="result.status !== 'ambiguous' && !result.totals.length">查询无结果。</p>
    <p v-if="result.hasMore">仅显示本次查询返回的部分结果。</p>
  </section>
</template>

<style scoped>
.ai-tool-result {
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 10px;
  margin-top: 8px;
}
.ai-tool-result h4 {
  margin: 0;
}
.ai-tool-header,
.ai-tool-totals {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px 16px;
  justify-content: space-between;
}
.ai-tool-totals {
  justify-content: flex-start;
}
.ai-tool-totals dd {
  margin: 0;
  font-variant-numeric: tabular-nums;
}
.ai-tool-totals dt,
.ai-data-time {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.ai-candidates {
  margin: 0;
  padding-left: 18px;
}
</style>
