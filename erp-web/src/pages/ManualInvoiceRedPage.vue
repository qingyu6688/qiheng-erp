<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue'
import { searchManualInvoiceReds } from '../api/manual-invoice-reds'
import { isCancelled, messageOf } from '../api/http'
import type { ManualInvoiceRed } from '../types/manual-invoice-reds'
import { manualRedStatusLabels } from '../types/manual-invoice-reds'
import { useSessionStore } from '../stores/session'
import FinancialAmount from '../components/FinancialAmount.vue'
import StatusTag from '../components/StatusTag.vue'
import SalesInvoiceDrawer from '../components/sales/SalesInvoiceDrawer.vue'

const session = useSessionStore()
const rows = ref<ManualInvoiceRed[]>([]),
  status = ref<ManualInvoiceRed['status'] | ''>('pending')
const loading = ref(false),
  error = ref(''),
  selected = ref<string>()
const cursor = ref<string | null>(null),
  next = ref<string | null>(null),
  history = ref<(string | null)[]>([])
const controller = new AbortController()
let revision = 0
async function load(reset = false): Promise<void> {
  if (reset) {
    cursor.value = null
    next.value = null
    history.value = []
    rows.value = []
  }
  const token = ++revision
  loading.value = true
  error.value = ''
  try {
    const result = await searchManualInvoiceReds(
      { status: status.value || undefined, cursor: cursor.value ?? undefined, limit: 20 },
      controller.signal,
    )
    if (token === revision) {
      rows.value = result.items
      next.value = result.nextCursor
    }
  } catch (cause: unknown) {
    if (token === revision && !isCancelled(cause)) {
      rows.value = []
      error.value = messageOf(cause)
    }
  } finally {
    if (token === revision) loading.value = false
  }
}
function page(forward: boolean): void {
  if (forward && next.value) {
    history.value.push(cursor.value)
    cursor.value = next.value
  } else if (!forward && history.value.length) cursor.value = history.value.pop() ?? null
  else return
  void load()
}
watch(status, () => {
  void load(true)
})
watch(
  () => session.companyId,
  () => {
    selected.value = undefined
    void load(true)
  },
)
onScopeDispose(() => {
  revision++
  controller.abort()
})
void load()
</script>

<template>
  <header class="page-heading">
    <div>
      <h1>手工销项红字</h1>
      <p class="red-list-help">登记已有红字发票后，由另一位财务人员确认；待确认时保留原票占用。</p>
    </div>
  </header>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <section class="panel">
    <div class="red-list-toolbar">
      <label
        >状态<el-select v-model="status" aria-label="手工红字状态筛选"
          ><el-option value="" label="全部状态" /><el-option
            v-for="(label, value) in manualRedStatusLabels"
            :key="value"
            :value="value"
            :label="label" /></el-select
      ></label>
      <el-button :loading="loading" @click="load(true)">刷新列表</el-button>
    </div>
    <el-table :data="rows" v-loading="loading" row-key="id" empty-text="此状态下暂无手工红字登记">
      <el-table-column prop="redInvoiceNo" label="红字票号" min-width="180" />
      <el-table-column prop="invoiceNo" label="原蓝字票号" min-width="180" />
      <el-table-column prop="redInvoiceDate" label="红字日期" min-width="120" />
      <el-table-column label="红字含税金额（元）" min-width="170" align="right"
        ><template #default="{ row }"><FinancialAmount :value="row.amount" /></template
      ></el-table-column>
      <el-table-column label="税额（元）" min-width="140" align="right"
        ><template #default="{ row }"><FinancialAmount :value="row.taxAmount" /></template
      ></el-table-column>
      <el-table-column label="状态" min-width="130"
        ><template #default="{ row }"
          ><StatusTag
            :label="manualRedStatusLabels[row.status as ManualInvoiceRed['status']]"
            :tone="row.status === 'confirmed' ? 'success' : 'neutral'" /></template
      ></el-table-column>
      <el-table-column label="操作" width="170" fixed="right"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="selected = row.invoiceId"
            >查看原票及红字</el-button
          ></template
        ></el-table-column
      >
    </el-table>
    <footer class="red-list-pagination">
      <span>每页20条</span
      ><el-button :disabled="loading || !history.length" @click="page(false)">上一页</el-button
      ><el-button :disabled="loading || !next" @click="page(true)">下一页</el-button>
    </footer>
  </section>
  <SalesInvoiceDrawer
    v-if="selected"
    :key="String(session.companyId) + '-' + selected"
    :id="selected"
    @close="selected = undefined"
    @changed="load()"
  />
</template>

<style scoped>
.red-list-help {
  color: var(--erp-text-2);
  font-size: 13px;
  margin: 8px 0 0;
}
.red-list-toolbar,
.red-list-pagination {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 18px 24px;
}
.red-list-toolbar {
  justify-content: space-between;
}
.red-list-toolbar label {
  display: flex;
  gap: 10px;
  align-items: center;
  font-size: 13px;
}
.red-list-toolbar .el-select {
  width: 160px;
}
.red-list-pagination {
  justify-content: flex-end;
  border-top: 1px solid var(--el-border-color-light);
}
.red-list-pagination span {
  margin-right: auto;
  color: var(--erp-text-2);
  font-size: 12px;
}
@media (max-width: 600px) {
  .red-list-toolbar,
  .red-list-pagination {
    padding-inline: 12px;
    flex-wrap: wrap;
  }
}
</style>
