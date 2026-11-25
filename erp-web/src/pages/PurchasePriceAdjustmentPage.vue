<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { priceAdjustments } from '../api/price-adjustments'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'
import { priceStatusLabels } from '../types/price-adjustments'
import type { PriceAdjustment, PriceAdjustmentStatus } from '../types/price-adjustments'
import FinancialAmount from '../components/FinancialAmount.vue'
import PurchasePriceAdjustmentDrawer from '../components/procurement/PurchasePriceAdjustmentDrawer.vue'
import StatusTag from '../components/StatusTag.vue'

const route = useRoute(),
  router = useRouter(),
  session = useSessionStore()
const rows = ref<PriceAdjustment[]>([]),
  status = ref(''),
  error = ref(''),
  loading = ref(false)
const cursor = ref<string | null>(null),
  nextCursor = ref<string | null>(null),
  history = ref<(string | null)[]>([])
const selected = ref<string>(),
  creating = ref(false)
const open = computed(() => creating.value || Boolean(selected.value))
let revision = 0
const controller = new AbortController()

async function load(reset = false): Promise<void> {
  if (reset) {
    cursor.value = null
    history.value = []
    rows.value = []
    nextCursor.value = null
  }
  const current = ++revision
  loading.value = true
  error.value = ''
  try {
    const result = await priceAdjustments(
      { status: status.value || undefined, cursor: cursor.value ?? undefined, limit: 20 },
      controller.signal,
    )
    if (current === revision) {
      rows.value = result.items
      nextCursor.value = result.nextCursor
    }
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
}

function page(next: boolean): void {
  if (next && nextCursor.value) {
    history.value.push(cursor.value)
    cursor.value = nextCursor.value
  } else if (!next && history.value.length) cursor.value = history.value.pop() ?? null
  else return
  void load()
}

function create(): void {
  selected.value = undefined
  creating.value = true
}
async function close(): Promise<void> {
  selected.value = undefined
  creating.value = false
  if (route.params.id) await router.replace('/purchase-price-adjustments')
}
function saved(): void {
  void load()
}

watch(
  () => route.params.id,
  (id) => {
    selected.value = typeof id === 'string' && /^[1-9][0-9]*$/.test(id) ? id : undefined
  },
  { immediate: true },
)
watch(
  () => session.companyId,
  () => {
    selected.value = undefined
    creating.value = false
    rows.value = []
    void load(true)
  },
)
watch(status, () => {
  void load(true)
})
onScopeDispose(() => {
  revision++
  controller.abort()
})
void load()
</script>

<template>
  <header class="page-heading">
    <div>
      <h1>采购调价</h1>
      <p class="price-page-help">调整已收货商品的结算价格，复核后同步应付和成本。</p>
    </div>
    <el-button
      v-if="session.can('price_adjustment:create') && session.can('cost:view')"
      type="primary"
      @click="create"
      >新建调价单</el-button
    >
  </header>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <section class="panel">
    <div class="price-list-toolbar">
      <label
        >状态
        <el-select v-model="status" aria-label="调价单状态筛选"
          ><el-option value="" label="全部状态" />
          <el-option
            v-for="(label, value) in priceStatusLabels"
            :key="value"
            :value="value"
            :label="label"
          /> </el-select
      ></label>
      <el-button :loading="loading" @click="load(true)">刷新列表</el-button>
    </div>
    <el-table :data="rows" v-loading="loading" empty-text="暂无采购调价单" row-key="id">
      <el-table-column prop="documentNo" label="调价单号" min-width="180" />
      <el-table-column prop="partnerName" label="供应商" min-width="160" />
      <el-table-column prop="warehouseName" label="原收货仓库" min-width="140" />
      <el-table-column prop="businessDate" label="调价日期" min-width="120" />
      <el-table-column label="含税调整（元）" min-width="140" align="right"
        ><template #default="{ row }"><FinancialAmount :value="row.amount" signed /></template
      ></el-table-column>
      <el-table-column label="状态" min-width="110"
        ><template #default="{ row }"
          ><StatusTag :label="priceStatusLabels[row.status as PriceAdjustmentStatus]" /></template
      ></el-table-column>
      <el-table-column label="操作" width="100" fixed="right"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="selected = row.id">查看</el-button></template
        ></el-table-column
      >
    </el-table>
    <footer class="price-list-pagination">
      <span>每页20条</span
      ><el-button :disabled="!history.length || loading" @click="page(false)">上一页</el-button>
      <el-button :disabled="!nextCursor || loading" @click="page(true)">下一页</el-button>
    </footer>
  </section>
  <PurchasePriceAdjustmentDrawer
    v-if="open"
    :key="String(session.companyId) + '-' + (selected ?? 'new')"
    :id="selected"
    @close="close"
    @saved="saved"
  />
</template>

<style scoped>
.price-page-help {
  color: var(--erp-text-2);
  font-size: 13px;
  margin: 8px 0 0;
}
.price-list-toolbar,
.price-list-pagination {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 18px 24px;
}
.price-list-toolbar {
  justify-content: space-between;
}
.price-list-toolbar label {
  display: flex;
  gap: 10px;
  align-items: center;
  font-size: 13px;
}
.price-list-toolbar .el-select {
  width: 160px;
}
.price-list-pagination {
  justify-content: flex-end;
  border-top: 1px solid var(--el-border-color-light);
}
.price-list-pagination span {
  margin-right: auto;
  color: var(--erp-text-2);
  font-size: 12px;
}
</style>
