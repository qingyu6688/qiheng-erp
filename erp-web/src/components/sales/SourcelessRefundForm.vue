<script setup lang="ts">
import { computed, onMounted, onScopeDispose, reactive, ref, watch } from 'vue'
import { allRecords } from '../../api/admin'
import { refundCreditItems } from '../../api/sourceless-returns'
import { ApiError, isCancelled, messageOf } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import type { DataRow } from '../../types/admin'
import type { SalesDocument } from '../../types/sales'
import { today } from '../../types/sales'
import type { RefundCreditItem, SourcelessCreditRefundInput } from '../../types/sourceless-returns'
import { creditAmount, refundError } from '../../utils/sourceless-returns'
import FinancialAmount from '../FinancialAmount.vue'

const props = defineProps<{ original?: SalesDocument; locked: boolean }>()
const emit = defineEmits<{
  save: [input: SourcelessCreditRefundInput]
  cancel: []
  dirty: [value: boolean]
  source: [id: string]
}>()
const session = useSessionStore()
const originCompany = session.companyId,
  originUser = session.user?.id
const form = reactive<SourcelessCreditRefundInput>({
  customerId: props.original?.customerId ?? '',
  cashAccountId: props.original?.cashAccountId ?? '',
  amount: props.original?.amount ?? '',
  businessDate: props.original?.businessDate ?? today(),
  reason: props.original?.reason ?? '',
  creditItemIds: [...(props.original?.creditItemIds ?? [])],
  version: props.original?.version,
})
const initial = JSON.stringify(form),
  error = ref(''),
  loading = ref(true),
  sourceLoading = ref(false)
const partners = ref<DataRow[]>([]),
  accounts = ref<DataRow[]>([]),
  page = ref<RefundCreditItem[]>([]),
  cursor = ref<string | null>(null)
const originalSources = ref([...(props.original?.creditSources ?? [])])
const known = reactive(new Map(originalSources.value.map((row) => [row.id, row])))
const selected = computed(() =>
  form.creditItemIds
    .map((id) => known.get(id))
    .filter((row): row is RefundCreditItem => Boolean(row)),
)
const available = computed(() => creditAmount(selected.value))
const visible = computed(() =>
  !allowed.value
    ? []
    : [
        ...new Map(
          [
            ...originalSources.value.filter((row) => row.partnerId === form.customerId),
            ...selected.value,
            ...page.value,
          ].map((row) => [row.id, row]),
        ).values(),
      ],
)
const allowed = computed(
  () =>
    Boolean(session.companyId) &&
    session.companyId === originCompany &&
    session.user?.id === originUser &&
    session.can('refund_request:view') &&
    session.can('sales_return:view') &&
    session.can(`refund_request:${props.original ? 'update' : 'create'}`),
)
let revision = 0,
  controller = new AbortController()
watch(
  () => JSON.stringify(form),
  (value) => emit('dirty', value !== initial),
  { immediate: true },
)
watch(
  () => form.customerId,
  () => {
    form.creditItemIds = []
    known.clear()
    originalSources.value = []
    page.value = []
    cursor.value = null
    void loadSources()
  },
)
watch(
  () => [session.companyId, session.user?.id],
  () => {
    revision++
    controller.abort()
    partners.value = []
    accounts.value = []
    known.clear()
    page.value = []
    Object.assign(form, {
      customerId: '',
      cashAccountId: '',
      amount: '',
      reason: '',
      creditItemIds: [],
    })
    emit('cancel')
  },
)

async function loadSources(more = false): Promise<void> {
  const current = ++revision,
    customer = form.customerId
  controller.abort()
  controller = new AbortController()
  sourceLoading.value = true
  error.value = ''
  if (!more) {
    page.value = []
    cursor.value = null
  }
  try {
    if (!customer || !allowed.value) return
    const result = await refundCreditItems(
      customer,
      more ? (cursor.value ?? undefined) : undefined,
      controller.signal,
    )
    if (current !== revision || customer !== form.customerId) return
    page.value = result.items
    cursor.value = result.nextCursor
    for (const row of result.items) if (known.has(row.id)) known.set(row.id, row)
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) {
      error.value = messageOf(cause)
      if (!allowed.value || (cause instanceof ApiError && [403, 404].includes(cause.status))) {
        known.clear()
        originalSources.value = []
        page.value = []
        form.creditItemIds = []
      }
    }
  } finally {
    if (current === revision) sourceLoading.value = false
  }
}
function toggle(row: RefundCreditItem, value: unknown): void {
  if (props.locked || !allowed.value) return
  if (value === true) {
    if (form.creditItemIds.includes(row.id)) return
    if (form.creditItemIds.length >= 200) {
      error.value = '每次最多选择200项信用额'
      return
    }
    known.set(row.id, row)
    form.creditItemIds.push(row.id)
  } else {
    form.creditItemIds = form.creditItemIds.filter((id) => id !== row.id)
    if (!originalSources.value.some((item) => item.id === row.id)) known.delete(row.id)
  }
}
onMounted(async () => {
  const company = session.companyId,
    user = session.user?.id
  try {
    const values = await Promise.all([
      session.can('partner:view') ? allRecords('partners') : [],
      session.can('cash_account:view') ? allRecords('cash-accounts', true) : [],
    ])
    if (company !== session.companyId || user !== session.user?.id) return
    ;[partners.value, accounts.value] = values
    await loadSources()
  } catch (cause: unknown) {
    if (company === session.companyId && user === session.user?.id && !isCancelled(cause))
      error.value = messageOf(cause)
  } finally {
    if (company === session.companyId && user === session.user?.id) loading.value = false
  }
})
onScopeDispose(() => {
  revision++
  controller.abort()
})
function save(): void {
  if (props.locked || loading.value || sourceLoading.value || !allowed.value) return
  const input = { ...form, reason: form.reason.trim(), creditItemIds: [...form.creditItemIds] }
  error.value = refundError(input, selected.value)
  if (!error.value) emit('save', input)
}
</script>

<template>
  <section class="sourceless-form">
    <h2>{{ original ? '更正信用额退款' : '新建信用额退款' }}</h2>
    <p class="muted">
      选择已过账的无原单退货信用额。提交后预占额度，另一人审批通过后，再登记实际付款。
    </p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form label-position="top" :disabled="locked || loading || !allowed" @submit.prevent="save">
      <div class="refund-fields">
        <el-form-item label="客户"
          ><el-select
            v-if="partners.length"
            v-model="form.customerId"
            aria-label="退款客户"
            filterable
            ><el-option
              v-for="item in partners"
              :key="item.id"
              :value="item.id"
              :label="String(item.name)" /></el-select
          ><el-input v-else v-model="form.customerId" aria-label="退款客户编号"
        /></el-form-item>
        <el-form-item label="退款账户"
          ><el-select
            v-if="accounts.length"
            v-model="form.cashAccountId"
            aria-label="退款账户"
            filterable
            ><el-option
              v-for="item in accounts"
              :key="item.id"
              :value="item.id"
              :label="String(item.name)" /></el-select
          ><el-input v-else v-model="form.cashAccountId" aria-label="退款账户编号"
        /></el-form-item>
        <el-form-item label="退款金额"
          ><el-input v-model="form.amount" aria-label="退款金额" inputmode="decimal"
        /></el-form-item>
        <el-form-item label="退款日期"
          ><el-input v-model="form.businessDate" aria-label="退款日期" type="date"
        /></el-form-item>
        <el-form-item label="退款原因"
          ><el-input v-model="form.reason" aria-label="退款原因" maxlength="200"
        /></el-form-item>
      </div>
      <div class="credit-summary">
        <strong>已选 {{ form.creditItemIds.length }} 项 · 可用余额</strong
        ><FinancialAmount :value="available" /><span class="muted"
          >保存不占用，提交时重新核对实际余额。</span
        >
      </div>
      <el-table
        :data="visible"
        row-key="id"
        empty-text="当前客户暂无可用退货信用额"
        :aria-busy="sourceLoading"
      >
        <el-table-column label="选择" width="70"
          ><template #default="{ row }"
            ><el-checkbox
              :model-value="form.creditItemIds.includes(row.id)"
              :aria-label="'选择信用额 ' + row.sourceNo"
              :disabled="
                locked ||
                !allowed ||
                (!form.creditItemIds.includes(row.id) &&
                  (row.openAmount === '0.00' || row.sourceStatus === 'reversed'))
              "
              @change="toggle(row, $event)" /></template
        ></el-table-column>
        <el-table-column label="退货单 / 商品" min-width="230"
          ><template #default="{ row }"
            ><el-button text :disabled="locked" @click="emit('source', row.sourceId)">{{
              row.sourceNo
            }}</el-button>
            <div>{{ row.skuName }}</div>
            <span v-if="row.sourceStatus === 'reversed'" class="muted"
              >原退货已冲销，请移除该选项</span
            ></template
          ></el-table-column
        >
        <el-table-column label="退回仓库" prop="warehouseName" min-width="140" />
        <el-table-column label="原信用额" min-width="155" align="right"
          ><template #default="{ row }"><FinancialAmount :value="row.amount" /></template
        ></el-table-column>
        <el-table-column label="当前可用余额" min-width="165" align="right"
          ><template #default="{ row }"><FinancialAmount :value="row.openAmount" /></template
        ></el-table-column>
      </el-table>
      <div class="form-actions">
        <el-button
          :loading="sourceLoading"
          :disabled="locked || !form.customerId"
          @click="loadSources()"
          >从最新重载</el-button
        ><el-button
          v-if="cursor"
          :loading="sourceLoading"
          :disabled="locked"
          @click="loadSources(true)"
          >下一页信用额</el-button
        ><span class="muted">每页20条，已选来源保留</span
        ><el-button type="primary" native-type="submit" :disabled="sourceLoading" :loading="locked"
          >保存草稿</el-button
        ><el-button @click="emit('cancel')">取消更正</el-button>
      </div>
    </el-form>
  </section>
</template>

<style scoped>
.refund-fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 16px;
}
.refund-fields :deep(.el-select) {
  width: 100%;
}
.credit-summary,
.form-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-block: 16px;
}
@media (max-width: 767px) {
  .refund-fields {
    grid-template-columns: 1fr;
  }
}
</style>
