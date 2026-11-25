<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { salesQuotes } from '../api/sales-quotes'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'
import type { SalesQuoteStatus, SalesQuoteSummary } from '../types/sales-quotes'
import { quoteStatusLabels } from '../types/sales-quotes'
import SalesQuoteDrawer from '../components/sales/SalesQuoteDrawer.vue'
import FinancialAmount from '../components/FinancialAmount.vue'
import StatusTag from '../components/StatusTag.vue'

const route = useRoute(), router = useRouter(), session = useSessionStore()
const rows = ref<SalesQuoteSummary[]>([]), status = ref(''), error = ref(''), loading = ref(false)
const selected = ref<string>(), creating = ref(false), cursor = ref<string | null>(null), nextCursor = ref<string | null>(null), history = ref<(string | null)[]>([])
const controller = new AbortController()
let revision = 0
async function load(reset = false): Promise<void> {
  if (reset) { rows.value = []; cursor.value = null; nextCursor.value = null; history.value = [] }
  const current = ++revision
  loading.value = true; error.value = ''
  try {
    const result = await salesQuotes({ status: status.value || undefined, cursor: cursor.value ?? undefined, limit: 20 }, controller.signal)
    if (current === revision) { rows.value = result.items; nextCursor.value = result.nextCursor }
  } catch (cause: unknown) { if (current === revision && !isCancelled(cause)) error.value = messageOf(cause) }
  finally { if (current === revision) loading.value = false }
}
function page(next: boolean): void {
  if (next && nextCursor.value) { history.value.push(cursor.value); cursor.value = nextCursor.value }
  else if (!next && history.value.length) cursor.value = history.value.pop() ?? null
  else return
  void load()
}
async function close(): Promise<void> { selected.value = undefined; creating.value = false; if (route.params.id) await router.replace('/sales-quotes') }
watch(() => route.params.id, (id) => { selected.value = typeof id === 'string' && /^[1-9][0-9]*$/.test(id) ? id : undefined }, { immediate: true })
watch(() => [session.companyId, session.user?.id, session.can('sales_quote:view')], () => { selected.value = undefined; creating.value = false; rows.value = []; ++revision; if (session.companyId && session.can('sales_quote:view')) void load(true) })
watch(status, () => { void load(true) })
onScopeDispose(() => { ++revision; controller.abort() })
void load()
</script>
<template>
  <header class="page-heading"><div><h1>销售报价</h1><p class="quote-help">确认报价有效期与价格，按需关联商机，再转为销售订单。</p></div><el-button v-if="session.can('sales_quote:create')" type="primary" @click="selected = undefined; creating = true">新建报价</el-button></header>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <section class="panel"><div class="quote-toolbar"><label>状态 <el-select v-model="status" aria-label="报价状态筛选"><el-option value="" label="全部状态" /><el-option v-for="(label, value) in quoteStatusLabels" :key="value" :value="value" :label="label" /></el-select></label><el-button :loading="loading" @click="load(true)">刷新列表</el-button></div>
    <el-table :data="rows" v-loading="loading" row-key="id" empty-text="暂无销售报价">
      <el-table-column prop="documentNo" label="报价单号" min-width="200" /><el-table-column prop="customerName" label="客户" min-width="180" /><el-table-column prop="validTo" label="有效期至" min-width="120" />
      <el-table-column label="价税合计（元）" min-width="160" align="right"><template #default="{ row }"><FinancialAmount :value="row.totalAmount" /></template></el-table-column>
      <el-table-column label="状态" min-width="110"><template #default="{ row }"><StatusTag :label="quoteStatusLabels[row.status as SalesQuoteStatus]" /></template></el-table-column>
      <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="selected = row.id; creating = false">查看</el-button></template></el-table-column>
    </el-table>
    <footer class="quote-toolbar"><span>每页20条</span><div><el-button :disabled="!history.length || loading" @click="page(false)">上一页</el-button><el-button :disabled="!nextCursor || loading" @click="page(true)">下一页</el-button></div></footer>
  </section>
  <SalesQuoteDrawer v-if="creating || selected" :key="`${session.companyId}-${selected ?? 'new'}`" :id="selected" @close="close" @saved="load()" />
</template>
<style scoped>.quote-help { color: var(--erp-text-2); font-size: 13px; margin: 8px 0 0; }.quote-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 18px 24px; }.quote-toolbar label { display: flex; align-items: center; gap: 10px; }.quote-toolbar .el-select { width: 160px; }</style>
