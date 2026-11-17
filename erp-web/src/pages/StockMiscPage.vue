<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { changeStockMisc, getStockMisc, listStockMisc, stockLoans } from '../api/stock-misc'
import type { StockMiscAction, StockMiscDirection, StockMiscDocument, StockMiscStatus } from '../api/stock-misc'
import { isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import { dictionaries } from '../api/platform'
import StockMiscEditor from '../components/stock-operations/StockMiscEditor.vue'
import { useSessionStore } from '../stores/session'
import { formatMoney, formatQuantity } from '../utils/format'
import { stockActionLabels } from '../types/stock-operations'

const props = defineProps<{ direction: StockMiscDirection }>()
const session = useSessionStore(), route = useRoute(), router = useRouter()
const path = computed(() => '/stock-misc-' + props.direction)
const title = computed(() => props.direction === 'in' ? '其他入库' : '其他出库')
const id = computed(() => typeof route.params.id === 'string' ? route.params.id : '')
const statuses: Record<StockMiscStatus, string> = {
  draft: '草稿', pending: '待审核', approved: '已审核', posted: '已过账', reversed: '已冲销', voided: '已作废',
}
const labels = ref<Record<string, string>>({})
const busy = ref(false), loading = ref(false), error = ref('')
const rows = ref<StockMiscDocument[]>([]), document = ref<StockMiscDocument>()
const editing = ref(false), loansOnly = ref(false), status = ref(''), keyword = ref('')
const cursors = ref<(string | undefined)[]>([undefined]), next = ref<string | null>()
const action = ref<StockMiscAction>(), reason = ref('')
const commandOpen = computed({ get: () => Boolean(action.value), set: open => { if (!open) action.value = undefined } })
let revision = 0, key = ''

async function load(reset = true): Promise<void> {
  const sequence = ++revision
  if (reset) cursors.value = [undefined]
  loading.value = true
  error.value = ''
  try {
    const params = { cursor: cursors.value.at(-1), keyword: keyword.value || undefined }
    if (id.value) {
      const result = await getStockMisc(id.value)
      if (sequence === revision) {
        if (result.direction !== props.direction) await router.replace('/stock-misc-' + result.direction + '/' + result.id)
        else document.value = result
      }
    } else {
      const page = loansOnly.value ? await stockLoans(params) : await listStockMisc(props.direction, { ...params, status: status.value || undefined })
      if (sequence === revision) {
        rows.value = page.items
        next.value = page.nextCursor
      }
    }
  } catch (cause: unknown) {
    if (!isCancelled(cause) && sequence === revision) error.value = messageOf(cause)
  } finally {
    if (sequence === revision) loading.value = false
  }
}

async function loadLabels(): Promise<void> {
  const company = session.companyId
  try {
    const result = await dictionaries('stock_misc_purpose')
    if (company === session.companyId) labels.value = Object.fromEntries((result.items?.stock_misc_purpose ?? []).map(({ value, label }) => [value, label]))
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  }
}

function changePage(forward: boolean): void {
  if (forward && next.value) cursors.value.push(next.value)
  if (!forward && cursors.value.length > 1) cursors.value.pop()
  void load(false)
}

function choose(nextAction: 'update' | StockMiscAction): void {
  if (nextAction === 'update') {
    editing.value = true
    return
  }
  error.value = ''
  reason.value = ''
  key = newIdempotencyKey()
  action.value = nextAction
}

async function execute(): Promise<void> {
  if (busy.value || !document.value || !action.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await changeStockMisc(document.value.id, action.value, { version: document.value.version, reason: reason.value }, key)
    if (result.id === id.value) document.value = result
    action.value = undefined
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

async function saved(result: StockMiscDocument): Promise<void> {
  editing.value = false
  if (result.id === id.value) document.value = result
  else await router.push(path.value + '/' + result.id)
}

watch(() => [props.direction, id.value, session.companyId], () => {
  document.value = undefined
  rows.value = []
  editing.value = false
  action.value = undefined
  if (props.direction === 'in') loansOnly.value = false
  void load()
}, { immediate: true })
watch(() => session.companyId, () => { labels.value = {}; void loadLabels() }, { immediate: true })
watch([loansOnly, status], () => { void load() })
</script>

<template>
  <div class="page-heading">
    <div>
      <router-link v-if="id" class="text-link" :to="path">返回{{ title }}</router-link>
      <h1>{{ id ? document?.documentNo ?? title + '单' : title }}</h1>
    </div>
    <div class="sales-actions">
      <el-button v-if="!id && session.can('stock_misc_doc:create')" type="primary" @click="editing = true">新建{{ title }}单</el-button>
      <el-button v-for="name in document?.allowedActions ?? []" :key="name" :type="name === 'void' || name === 'reject' ? 'danger' : 'primary'" plain @click="choose(name)">{{ stockActionLabels[name] }}</el-button>
      <router-link v-if="document?.status === 'posted' && session.can('reversal:create')" to="/document-reversals">办理冲销</router-link>
    </div>
  </div>
  <el-alert v-if="error && !action" :title="error" type="error" :closable="false" />
  <div v-if="!id" class="content-panel">
    <div class="filters">
      <el-input v-model="keyword" aria-label="单号" clearable placeholder="搜索单号" @keyup.enter="load()" @clear="load()" />
      <el-select v-model="status" aria-label="单据状态" :disabled="loansOnly"><el-option value="" label="全部状态" /><el-option v-for="(label, value) in statuses" :key="value" :value="value" :label="label" /></el-select>
      <el-checkbox v-if="direction === 'out'" v-model="loansOnly">借出未还</el-checkbox>
      <el-button :loading="loading" @click="load()">查询</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" :empty-text="loansOnly ? '暂无未归还的借出单' : '暂无单据'">
      <el-table-column label="单号" min-width="190"><template #default="{ row }"><router-link :to="path + '/' + row.id">{{ row.documentNo }}</router-link></template></el-table-column>
      <el-table-column label="用途" min-width="110"><template #default="{ row }">{{ labels[row.purpose] ?? row.purpose }}</template></el-table-column>
      <el-table-column prop="warehouseName" label="仓库" min-width="130" />
      <el-table-column prop="partnerName" label="往来单位" min-width="130" />
      <el-table-column prop="businessDate" label="业务日期" width="115" />
      <el-table-column v-if="direction === 'out'" prop="expectedReturnDate" label="预计归还" width="115" />
      <el-table-column label="状态" width="105"><template #default="{ row }">{{ statuses[row.status as StockMiscStatus] }}</template></el-table-column>
    </el-table>
    <div class="table-footer">
      <span>第 {{ cursors.length }} 页</span>
      <div><el-button :disabled="loading || cursors.length === 1" @click="changePage(false)">上一页</el-button><el-button :disabled="loading || !next" @click="changePage(true)">下一页</el-button></div>
    </div>
  </div>
  <div v-else v-loading="loading" class="content-panel">
    <template v-if="document">
      <dl class="stock-misc-summary">
        <div><dt>用途</dt><dd>{{ labels[document.purpose] ?? document.purpose }}</dd></div>
        <div><dt>状态</dt><dd>{{ statuses[document.status] }}</dd></div>
        <div><dt>仓库</dt><dd>{{ document.warehouseName ?? document.warehouseId }}</dd></div>
        <div><dt>业务日期</dt><dd>{{ document.businessDate }}</dd></div>
        <div v-if="document.expectedReturnDate"><dt>预计归还</dt><dd>{{ document.expectedReturnDate }}</dd></div>
        <div v-if="document.originalLoanId"><dt>原借出单</dt><dd><router-link :to="'/stock-misc-out/' + document.originalLoanId">查看借出单</router-link></dd></div>
        <div v-if="document.partnerId"><dt>往来单位</dt><dd>{{ document.partnerName ?? document.partnerId }}</dd></div>
      </dl>
      <p v-if="document.remark">{{ document.remark }}</p>
      <el-table :data="document.lines ?? []" empty-text="暂无商品明细">
        <el-table-column prop="skuName" label="商品" min-width="140" />
        <el-table-column prop="unitName" label="单位" width="90" />
        <el-table-column label="数量" width="120"><template #default="{ row }">{{ formatQuantity(row.qty) }}</template></el-table-column>
        <el-table-column label="基础数量" width="120"><template #default="{ row }">{{ formatQuantity(row.baseQty) }}</template></el-table-column>
        <el-table-column label="批次 / 库位" min-width="160"><template #default="{ row }">{{ row.stockSelection?.batchNo || row.batchId || '—' }} / {{ row.locationKey || '—' }}</template></el-table-column>
        <el-table-column v-if="session.can('cost:view')" label="过账成本" width="140"><template #default="{ row }">{{ formatMoney(row.amount) }}</template></el-table-column>
      </el-table>
    </template>
  </div>
  <StockMiscEditor v-if="editing" :direction="direction" :original="document" @close="editing = false" @saved="saved" />
  <el-dialog v-model="commandOpen" :title="action ? stockActionLabels[action] : ''" width="min(520px, 94vw)" :close-on-click-modal="false">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="document">{{ document.documentNo }} · {{ statuses[document.status] }}</p>
    <el-form label-position="top">
      <el-form-item v-if="action === 'reject' || action === 'void'" :label="action === 'reject' ? '驳回原因' : '备注'"><el-input v-model="reason" type="textarea" maxlength="200" /></el-form-item>
    </el-form>
    <template #footer><el-button :disabled="busy" @click="action = undefined">取消</el-button><el-button type="primary" :loading="busy" @click="execute">确认</el-button></template>
  </el-dialog>
</template>

<style scoped>
.stock-misc-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
}
.stock-misc-summary dt { color: var(--el-text-color-secondary); }
.stock-misc-summary dd { margin: 4px 0 0; }
</style>
