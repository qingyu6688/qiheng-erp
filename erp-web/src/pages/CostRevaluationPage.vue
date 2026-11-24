<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { costRevaluations } from '../api/cost-revaluations'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'
import type { CostRevaluationSummary, CostRevaluationStatus } from '../types/cost-revaluations'
import { revaluationStatusLabels } from '../types/cost-revaluations'
import CostRevaluationDrawer from '../components/stock-operations/CostRevaluationDrawer.vue'
import FinancialAmount from '../components/FinancialAmount.vue'
import StatusTag from '../components/StatusTag.vue'

const route = useRoute(), router = useRouter(), session = useSessionStore()
const rows = ref<CostRevaluationSummary[]>([]), status = ref(''), error = ref(''), loading = ref(false)
const cursor = ref<string | null>(null), nextCursor = ref<string | null>(null), history = ref<(string | null)[]>([])
const selected = ref<string>(), creating = ref(false)
const controller = new AbortController()
let revision = 0
async function load(reset = false): Promise<void> {
  if (reset) { cursor.value = null; nextCursor.value = null; history.value = []; rows.value = [] }
  const current = ++revision
  loading.value = true; error.value = ''
  try {
    const result = await costRevaluations({ status: status.value || undefined, cursor: cursor.value ?? undefined, limit: 20 }, controller.signal)
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
async function close(): Promise<void> { selected.value = undefined; creating.value = false; if (route.params.id) await router.replace('/cost-revaluations') }
watch(() => route.params.id, (id) => { selected.value = typeof id === 'string' && /^[1-9][0-9]*$/.test(id) ? id : undefined }, { immediate: true })
watch(() => [session.companyId, session.user?.id, session.can('cost:view'), session.can('cost_revaluation:view')], () => {
  selected.value = undefined; creating.value = false; rows.value = []; ++revision
  if (session.companyId && session.can('cost:view') && session.can('cost_revaluation:view')) void load(true)
})
watch(status, () => { void load(true) })
onScopeDispose(() => { ++revision; controller.abort() })
void load()
</script>

<template>
  <header class="page-heading"><div><h1>成本重估</h1><p class="revaluation-help">预览库存及出库成本差异，经审批后追加调整记录。</p></div>
    <el-button v-if="session.can('cost_revaluation:create')" type="primary" @click="selected = undefined; creating = true">新建重估任务</el-button>
  </header>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <section class="panel">
    <div class="revaluation-toolbar"><label>状态 <el-select v-model="status" aria-label="重估状态筛选"><el-option value="" label="全部状态" /><el-option v-for="(label, value) in revaluationStatusLabels" :key="value" :value="value" :label="label" /></el-select></label><el-button :loading="loading" @click="load(true)">刷新列表</el-button></div>
    <el-table :data="rows" v-loading="loading" row-key="id" empty-text="暂无成本重估任务">
      <el-table-column prop="documentNo" label="重估任务" min-width="200" />
      <el-table-column prop="warehouseName" label="仓库" min-width="130" />
      <el-table-column prop="skuName" label="商品" min-width="160" />
      <el-table-column prop="startDate" label="起算日" min-width="120" />
      <el-table-column label="库存成本调整（元）" min-width="175" align="right"><template #default="{ row }"><FinancialAmount :value="row.stockDelta" signed /></template></el-table-column>
      <el-table-column label="状态" min-width="110"><template #default="{ row }"><StatusTag :label="revaluationStatusLabels[row.status as CostRevaluationStatus]" /></template></el-table-column>
      <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="selected = row.id; creating = false">查看</el-button></template></el-table-column>
    </el-table>
    <footer class="revaluation-toolbar"><span>每页20条</span><div><el-button :disabled="!history.length || loading" @click="page(false)">上一页</el-button><el-button :disabled="!nextCursor || loading" @click="page(true)">下一页</el-button></div></footer>
  </section>
  <CostRevaluationDrawer v-if="selected || creating" :key="`${session.companyId}-${selected ?? 'new'}`" :id="selected" @close="close" @saved="load()" />
</template>

<style scoped>
.revaluation-help { color: var(--erp-text-2); font-size: 13px; margin: 8px 0 0; }
.revaluation-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 18px 24px; }
.revaluation-toolbar label { display: flex; gap: 10px; align-items: center; }
.revaluation-toolbar .el-select { width: 160px; }
</style>
