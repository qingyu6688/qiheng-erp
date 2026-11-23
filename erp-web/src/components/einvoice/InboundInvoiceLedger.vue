<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onScopeDispose, reactive, ref, watch } from 'vue'
import { inboundInvoices, syncInvoices } from '../../api/e-invoice'
import { isCancelled, messageOf } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import type { InboundFilters, InboundInvoiceRow } from '../../types/inbound-invoices'
import {
  inboundMatchLabels,
  inboundMatchTone,
  inboundSourceLabels,
  inboundStatusLabels,
} from '../../types/inbound-invoices'
import { today } from '../../types/sales'
import FinancialAmount from '../FinancialAmount.vue'
import StatusTag from '../StatusTag.vue'

const InboundInvoiceDrawer = defineAsyncComponent(() => import('./InboundInvoiceDrawer.vue'))
const session = useSessionStore(),
  rows = ref<InboundInvoiceRow[]>([]),
  error = ref(''),
  notice = ref(''),
  loading = ref(false),
  syncing = ref(false)
const filters = reactive<InboundFilters>({}),
  applied = ref<InboundFilters>({}),
  cursors = ref<(string | undefined)[]>([undefined])
const syncRange = reactive({ from: today(), to: today() })
const open = ref(false),
  selectedId = ref<string>(),
  panel = ref<{ mayLeave(): boolean }>(),
  listRoot = ref<HTMLElement>()
const mobile = window.matchMedia?.('(max-width: 767px)'),
  compact = ref(mobile?.matches ?? false)
const owner = session.companyId,
  lifecycle = new AbortController()
let revision = 0,
  request: AbortController | undefined
const currentPage = computed(() => cursors.value.at(-1))
const canRegister = computed(() => session.can('einv:inbound_register'))
function resize(event: MediaQueryListEvent): void {
  compact.value = event.matches
}
mobile?.addEventListener('change', resize)
function mayLeave(): boolean {
  return panel.value?.mayLeave() ?? true
}
defineExpose({ mayLeave })
async function load(): Promise<void> {
  request?.abort()
  request = new AbortController()
  const current = ++revision,
    active = (): boolean =>
      current === revision && owner === session.companyId && !lifecycle.signal.aborted
  const wrap = listRoot.value?.querySelector<HTMLElement>(
    '.el-table__body-wrapper .el-scrollbar__wrap',
  )
  const left = wrap?.scrollLeft ?? 0,
    top = wrap?.scrollTop ?? 0
  loading.value = true
  error.value = ''
  try {
    const result = await inboundInvoices(currentPage.value, { ...applied.value }, request.signal)
    if (!active()) return
    rows.value = result
    await nextTick()
    if (active()) {
      const currentWrap = listRoot.value?.querySelector<HTMLElement>(
        '.el-table__body-wrapper .el-scrollbar__wrap',
      )
      if (currentWrap) {
        currentWrap.scrollLeft = left
        currentWrap.scrollTop = top
      }
    }
  } catch (cause: unknown) {
    if (active() && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (active()) loading.value = false
  }
}
function search(): void {
  applied.value = {
    ...(filters.matchStatus ? { matchStatus: filters.matchStatus } : {}),
    ...(filters.source ? { source: filters.source } : {}),
    ...(filters.keyword?.trim() ? { keyword: filters.keyword.trim() } : {}),
  }
  cursors.value = [undefined]
  void load()
}
function page(forward: boolean): void {
  if (forward && rows.value.length >= 30) cursors.value.push(rows.value.at(-1)!.id)
  else if (!forward && cursors.value.length > 1) cursors.value.pop()
  else return
  void load()
}
function show(id?: string): void {
  if (!mayLeave()) return
  selectedId.value = id
  open.value = true
}
function saved(): void {
  void load()
}
async function sync(): Promise<void> {
  if (syncing.value || loading.value) return
  syncing.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await syncInvoices(syncRange.from, syncRange.to, lifecycle.signal)
    if (owner !== session.companyId || lifecycle.signal.aborted) return
    notice.value = `已同步 ${result.count} 张进项发票。`
    await load()
  } catch (cause: unknown) {
    if (owner === session.companyId && !lifecycle.signal.aborted && !isCancelled(cause))
      error.value = messageOf(cause)
  } finally {
    if (owner === session.companyId && !lifecycle.signal.aborted) syncing.value = false
  }
}
watch(
  () => session.companyId,
  () => {
    if (owner !== session.companyId) {
      open.value = false
      rows.value = []
      revision++
      request?.abort()
      lifecycle.abort()
    }
  },
)
onScopeDispose(() => {
  revision++
  request?.abort()
  lifecycle.abort()
  mobile?.removeEventListener('change', resize)
})
void load()
</script>
<template>
  <section ref="listRoot" class="inbound-ledger">
    <div class="ledger-toolbar">
      <div>
        <h2>进项台账</h2>
        <p class="muted">手工票与数电票统一核对采购应付，付款结清后仍可核对到票。</p>
      </div>
      <div class="ledger-actions">
        <el-button :loading="loading" :disabled="syncing" @click="load">刷新台账</el-button
        ><el-button v-if="canRegister" type="primary" :disabled="syncing" @click="show()"
          >登记手工票</el-button
        >
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="notice" :title="notice" type="success" :closable="false" />
    <form class="ledger-filters" aria-label="进项台账筛选" @submit.prevent="search">
      <div>
        <label for="inbound-status-filter">核对状态</label
        ><el-select
          id="inbound-status-filter"
          v-model="filters.matchStatus"
          aria-label="核对状态筛选"
          clearable
          ><el-option
            v-for="(label, value) in inboundMatchLabels"
            :key="value"
            :value="value"
            :label="label"
        /></el-select>
      </div>
      <div>
        <label for="inbound-source-filter">来源</label
        ><el-select
          id="inbound-source-filter"
          v-model="filters.source"
          aria-label="进项来源筛选"
          clearable
          ><el-option
            v-for="(label, value) in inboundSourceLabels"
            :key="value"
            :value="value"
            :label="label"
        /></el-select>
      </div>
      <div>
        <label for="inbound-search">号码或销方</label
        ><el-input
          id="inbound-search"
          v-model="filters.keyword"
          maxlength="100"
          clearable
          placeholder="搜索发票号码或销方名称"
        />
      </div>
      <el-button native-type="submit" :loading="loading" :disabled="syncing">查询台账</el-button>
    </form>
    <details class="sync-section">
      <summary>从数电服务商同步</summary>
      <form class="ledger-filters" @submit.prevent="sync">
        <div>
          <label for="inbound-sync-from">同步开始日期</label
          ><el-input
            id="inbound-sync-from"
            v-model="syncRange.from"
            type="date"
            :max="today()"
            :disabled="syncing"
          />
        </div>
        <div>
          <label for="inbound-sync-to">同步截止日期</label
          ><el-input
            id="inbound-sync-to"
            v-model="syncRange.to"
            type="date"
            :max="today()"
            :disabled="syncing"
          />
        </div>
        <el-button
          native-type="submit"
          :loading="syncing"
          :disabled="loading || !syncRange.from || !syncRange.to"
          >同步进项</el-button
        >
      </form>
    </details>
    <el-table :data="rows" :aria-busy="loading" row-key="id" aria-label="进项发票台账">
      <el-table-column
        label="发票号码 / 来源"
        :min-width="compact ? 190 : 230"
        :fixed="compact ? undefined : 'left'"
        ><template #default="{ row }"
          ><button type="button" class="text-link invoice-no" @click="show(row.id)">
            {{ row.invoiceNo }}
          </button>
          <p class="muted">
            {{ inboundSourceLabels[row.source as keyof typeof inboundSourceLabels] }}
          </p></template
        ></el-table-column
      >
      <el-table-column prop="sellerName" label="销方" min-width="180" />
      <el-table-column prop="invoiceDate" label="开票日期" min-width="120" />
      <el-table-column label="价税合计" min-width="140" align="right"
        ><template #default="{ row }"><FinancialAmount :value="row.amount" /></template
      ></el-table-column>
      <el-table-column label="发票状态" min-width="110"
        ><template #default="{ row }"
          ><StatusTag
            :label="inboundStatusLabels[row.status as keyof typeof inboundStatusLabels]"
            :tone="row.status === 'normal' ? 'neutral' : 'danger'"
          />
          <p v-if="row.source === 'einv'" class="muted">
            {{ row.checked ? '已勾选' : '未勾选' }}
          </p></template
        ></el-table-column
      >
      <el-table-column label="核对状态" min-width="130"
        ><template #default="{ row }"
          ><StatusTag
            v-if="row.status === 'normal'"
            :label="inboundMatchLabels[row.matchStatus as keyof typeof inboundMatchLabels]"
            :tone="inboundMatchTone(row.matchStatus)"
          /><span v-else>—</span>
          <p v-if="row.status === 'normal' && row.matchStatus === 'difference'" class="muted">
            {{ row.fullyAllocated ? '票面已分配完' : '票面尚未分配完' }}
          </p></template
        ></el-table-column
      >
      <el-table-column label="尚未分配（未税 / 税额）" min-width="195" align="right"
        ><template #default="{ row }"
          ><FinancialAmount :value="row.remainingUntaxedAmount" /><span class="muted"> / </span
          ><FinancialAmount :value="row.remainingTaxAmount" /></template
      ></el-table-column>
      <el-table-column label="差异绝对值" min-width="130" align="right"
        ><template #default="{ row }"><FinancialAmount :value="row.absoluteDifference" /></template
      ></el-table-column>
      <el-table-column label="操作" width="90" :fixed="compact ? undefined : 'right'"
        ><template #default="{ row }"
          ><el-button text @click="show(row.id)">查看</el-button></template
        ></el-table-column
      >
      <template #empty>{{
        loading ? '正在读取进项台账…' : error ? '读取失败，请重试' : '此条件下暂无进项发票'
      }}</template>
    </el-table>
    <footer class="ledger-toolbar">
      <span>第 {{ cursors.length }} 页</span>
      <div class="ledger-actions">
        <el-button :disabled="loading || cursors.length === 1" @click="page(false)"
          >上一页</el-button
        ><el-button :disabled="loading || rows.length < 30" @click="page(true)">下一页</el-button>
      </div>
    </footer>
  </section>
  <InboundInvoiceDrawer
    v-if="open"
    :key="String(session.companyId) + '-' + (selectedId ?? 'new')"
    ref="panel"
    :id="selectedId"
    @close="open = false"
    @saved="saved"
  />
</template>
<style scoped>
.inbound-ledger {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 18px;
  padding: 8px 0;
}
h2,
p {
  margin: 0;
}
h2 {
  font-size: 18px;
  margin-bottom: 6px;
}
.muted {
  color: var(--erp-text-2);
  font-size: 12px;
  line-height: 1.7;
}
.ledger-toolbar,
.ledger-actions,
.ledger-filters {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.ledger-toolbar {
  justify-content: space-between;
}
.ledger-filters {
  align-items: flex-end;
}
.ledger-filters > div {
  min-width: 170px;
}
.ledger-filters label {
  display: block;
  margin-bottom: 7px;
  color: var(--erp-text-2);
  font-size: 12px;
}
.ledger-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.sync-section {
  border-block: 1px solid var(--erp-border-1);
  padding: 12px 0;
}
.sync-section summary {
  cursor: pointer;
  color: var(--erp-text-2);
}
.sync-section[open] .ledger-filters {
  margin-top: 14px;
}
.invoice-no {
  font-weight: 600;
  overflow-wrap: anywhere;
  text-align: left;
}
</style>
