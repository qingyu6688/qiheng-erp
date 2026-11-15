<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { stockDocument, stockList } from '../api/stock-operations'
import { isCancelled, messageOf } from '../api/http'
import { reversalTypes, stockActionLabels, stockSpecs, stockStatusLabels } from '../types/stock-operations'
import type { StockDocument, StockKind } from '../types/stock-operations'
import { useSessionStore } from '../stores/session'
import StockDocumentEditor from '../components/stock-operations/StockDocumentEditor.vue'
import StockCommandDialog from '../components/stock-operations/StockCommandDialog.vue'

const props = defineProps<{ kind: StockKind }>(), route = useRoute(), router = useRouter(), session = useSessionStore()
const spec = computed(() => stockSpecs.find(item => item.path === props.kind)!), detailId = computed(() => typeof route.params.id === 'string' ? route.params.id : '')
const rows = ref<StockDocument[]>([]), value = ref<StockDocument>(), loading = ref(false), error = ref(''), status = ref('')
const cursors = ref<(string | null)[]>([null]), next = ref<string | null>(null), editing = ref(false), action = ref('')
let revision = 0
async function load(): Promise<void> {
  const current = ++revision; loading.value = true; error.value = ''
  try {
    if (detailId.value) { const result = await stockDocument(props.kind, detailId.value); if (current === revision) value.value = result }
    else { const result = await stockList(props.kind, { status: status.value || undefined, cursor: cursors.value.at(-1) ?? undefined, limit: 20 }); if (current === revision) { rows.value = result.items; next.value = result.nextCursor } }
  } catch (cause: unknown) { if (current === revision && !isCancelled(cause)) error.value = messageOf(cause) }
  finally { if (current === revision) loading.value = false }
}
function search(): void { cursors.value = [null]; void load() }
function paginate(forward: boolean): void { if (forward && next.value) cursors.value.push(next.value); else if (!forward) cursors.value.pop(); void load() }
function choose(name: string): void { if (name === 'update') editing.value = true; else action.value = name }
async function saved(document: StockDocument): Promise<void> { editing.value = false; if (detailId.value === document.id) await load(); else await router.push('/' + props.kind + '/' + document.id) }
async function closeEditor(): Promise<void> { editing.value = false; await load() }
async function commandSaved(): Promise<void> { action.value = ''; await load() }
function sourceLabel(type?: string): string { return reversalTypes.find(item => item.value === type)?.label ?? '原单据' }
watch(() => [props.kind, detailId.value, session.companyId], () => { editing.value = false; action.value = ''; value.value = undefined; rows.value = []; status.value = ''; cursors.value = [null]; next.value = null; void load() }, { immediate: true })
</script>
<template>
  <div class="page-heading"><div><router-link v-if="detailId" class="text-link" :to="'/' + kind">返回{{ spec.title }}</router-link><h1>{{ detailId ? value?.documentNo ?? spec.singular : spec.title }}</h1></div>
    <div class="sales-actions"><el-button v-if="!detailId && session.can(spec.permission + ':create')" type="primary" @click="editing = true">新建{{ spec.singular }}</el-button><el-button v-for="name in value?.allowedActions ?? []" :key="name" :type="['approve', 'post', 'ship'].includes(name) ? 'primary' : undefined" @click="choose(name)">{{ stockActionLabels[name] ?? name }}</el-button></div>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" /><p v-if="loading">正在加载…</p>
  <template v-if="!detailId"><section class="panel table-panel" :aria-busy="loading"><form class="filters" @submit.prevent="search"><div class="filter-field small"><label for="stock-status">状态</label><el-select id="stock-status" v-model="status" clearable><el-option v-for="state in spec.states" :key="state" :value="state" :label="stockStatusLabels[state] ?? state" /></el-select></div><el-button native-type="submit">查询</el-button></form>
    <el-table :data="rows" row-key="id"><el-table-column label="单号" min-width="240"><template #default="{ row }"><router-link class="text-link" :to="'/' + kind + '/' + row.id">{{ row.documentNo }}</router-link></template></el-table-column><el-table-column label="仓库 / 原单" min-width="220"><template #default="{ row }">{{ row.warehouseName ?? (row.fromWarehouseName ? row.fromWarehouseName + ' → ' + row.toWarehouseName : row.sourceNo) }}</template></el-table-column><el-table-column prop="businessDate" label="业务日期" min-width="130" /><el-table-column label="状态" min-width="150"><template #default="{ row }"><el-tag type="info">{{ stockStatusLabels[row.status] ?? row.status }}</el-tag></template></el-table-column><template #empty>{{ loading ? '正在加载…' : error ? '加载失败，请重新查询' : '暂无单据' }}</template></el-table>
    <footer class="table-footer"><span>第 {{ cursors.length }} 页</span><div><el-button :disabled="loading || cursors.length === 1" @click="paginate(false)">上一页</el-button><el-button :disabled="loading || !next" @click="paginate(true)">下一页</el-button></div></footer>
  </section></template>
  <template v-else-if="value"><section class="panel sales-detail-header"><dl><dt>状态</dt><dd><el-tag type="info">{{ stockStatusLabels[value.status] ?? value.status }}</el-tag></dd><dt>业务日期</dt><dd>{{ value.businessDate }}</dd><dt>仓库 / 路线</dt><dd>{{ value.warehouseName ?? (value.fromWarehouseName ? value.fromWarehouseName + ' → ' + value.toWarehouseName : '—') }}</dd><dt>版本</dt><dd>{{ value.version }}</dd></dl><p v-if="value.sourceNo">{{ sourceLabel(value.sourceType) }}：{{ value.sourceNo }}</p><p v-if="value.snapshotAt">盘点快照 {{ value.snapshotAt }}。实盘数对应此时点，过账按快照差额调整，不覆盖期间收发货。</p><p v-if="value.reason">原因：{{ value.reason }}</p><p v-if="value.remark">备注：{{ value.remark }}</p></section>
    <section v-if="value.lines?.length" class="panel table-panel"><el-table :data="value.lines" row-key="id"><el-table-column prop="skuName" label="商品" min-width="150" /><template v-if="kind === 'stock-transfers'"><el-table-column label="调拨数量" min-width="150"><template #default="{ row }">{{ row.qty }} {{ row.unitName }}</template></el-table-column><el-table-column prop="baseQty" label="基础数量" min-width="130" /><el-table-column prop="receivedBaseQty" label="已调入" min-width="130" /><el-table-column prop="remainingQty" label="在途数量" min-width="130" /><el-table-column prop="diffBaseQty" label="差异数量" min-width="130" /></template><template v-else><el-table-column prop="baseUnitName" label="基础单位" /><el-table-column prop="bookQty" label="快照账面数" min-width="130" /><el-table-column prop="countedQty" label="时点实盘数" min-width="130" /><el-table-column prop="diffQty" label="盘点差额" min-width="130" /></template><el-table-column v-if="session.can('cost:view')" prop="costAmount" label="成本金额" min-width="130" /></el-table></section>
  </template>
  <StockDocumentEditor v-if="editing" :key="kind + (value?.id ?? '')" :kind="kind" :original="detailId ? value : undefined" @close="closeEditor" @saved="saved" />
  <StockCommandDialog v-if="action && value" :key="action + value.id" :kind="kind" :document="value" :action="action" @close="action = ''; load()" @saved="commandSaved" />
</template>
