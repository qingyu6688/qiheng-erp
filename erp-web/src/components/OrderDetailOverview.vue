<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { isCancelled } from '../api/http'
import { orderSettlementSummary, type OrderSettlementSummary } from '../api/order-related'
import { useSessionStore } from '../stores/session'
import { statusLabels } from '../types/sales'
import { formatDateTime, formatMoney, formatQuantity } from '../utils/format'
import { orderDetailProgress } from '../utils/order-detail-progress'
import type { OrderDetailKind, OrderProgressInput } from '../utils/order-detail-progress'
import StatusTag from './StatusTag.vue'
import { paymentTermLabel } from '../utils/payment-terms'

const props = defineProps<{
  kind: OrderDetailKind
  order: OrderProgressInput & {
    id: string
    version: number
    totalAmount?: string
    taxAmount?: string
    businessDate?: string
    termType?: string | null
    termValue?: number | null
    deliveryDate?: string
    afterSaleStatus?: string
    reason?: string
    remark?: string
    createdAt?: unknown
    updatedAt?: unknown
    submittedAt?: unknown
    approvedAt?: unknown
  }
  party?: string
  representative?: string
}>()
const sale = computed(() => props.kind === 'sales-orders')
const session = useSessionStore()
const mayReadBalances = computed(
  () =>
    session.can('settlement:view') &&
    session.can(sale.value ? 'shipment:view' : 'goods_receipt:view'),
)
const mayReadCash = computed(() => session.can(sale.value ? 'receipt:view' : 'payment:view'))
const totals = ref<OrderSettlementSummary>()
const totalsError = ref(false)
const loadingTotals = ref(false)
const retry = ref(0)

watch(
  [
    () => props.kind,
    () => props.order,
    () => props.order.version,
    () => session.user,
    () => session.companyId,
    mayReadBalances,
    mayReadCash,
    retry,
  ],
  async (_, _previous, onCleanup) => {
    totals.value = undefined
    totalsError.value = false
    loadingTotals.value = false
    if (!session.companyId || !mayReadBalances.value) return
    const controller = new AbortController()
    onCleanup(() => controller.abort())
    loadingTotals.value = true
    try {
      const result = await orderSettlementSummary(props.kind, props.order.id, controller.signal)
      if (!controller.signal.aborted) totals.value = result
    } catch (error: unknown) {
      if (!controller.signal.aborted && !isCancelled(error)) totalsError.value = true
    } finally {
      if (!controller.signal.aborted) loadingTotals.value = false
    }
  },
  { immediate: true },
)
const overview = computed(() => orderDetailProgress(props.kind, props.order))
const settlementLabels: Record<string, string> = {
  none: '未开始',
  partial: '部分结算',
  full: '已确认金额结清',
}
const settlement = computed(() => settlementLabels[props.order.settlementStatus ?? ''] ?? '—')
const stepState = {
  done: '已完成',
  current: '当前步骤',
  pending: '未完成',
  skipped: '已关闭剩余数量',
}

function displayTime(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  const time = formatDateTime(value)
  return time !== '—' ? time : undefined
}

const dates = computed(() => ({
  submit: displayTime(props.order.submittedAt),
  approve: displayTime(props.order.approvedAt),
}))
const createdAt = computed(() => displayTime(props.order.createdAt))
const updatedAt = computed(() => displayTime(props.order.updatedAt))
</script>

<template>
  <section class="panel order-overview" aria-label="订单摘要">
    <div class="order-overview__meta">
      <span v-if="party">{{ sale ? '客户' : '供应商' }} {{ party }}</span>
      <span v-if="order.businessDate">业务日期 {{ order.businessDate }}</span>
      <span>账期 {{ paymentTermLabel(order.termType, order.termValue) }}</span>
      <span v-if="order.deliveryDate">交货日期 {{ order.deliveryDate }}</span>
      <span v-if="representative">{{ sale ? '销售员' : '采购员' }} {{ representative }}</span>
      <span v-if="createdAt">制单 {{ createdAt }}</span>
      <span v-if="updatedAt">更新 {{ updatedAt }}</span>
      <span>版本 {{ order.version }}</span>
    </div>
    <dl class="order-overview__metrics">
      <div>
        <dt>订单金额</dt>
        <dd data-order-metric="amount">{{ formatMoney(order.totalAmount) }}</dd>
        <dd v-if="order.taxAmount != null" class="order-overview__hint">
          税额 {{ formatMoney(order.taxAmount) }}
        </dd>
      </div>
      <div v-for="item in overview.quantities" :key="item.key">
        <dt>已{{ item.label }}</dt>
        <dd :data-order-metric="item.key">
          {{
            item.progress?.percent ??
            (item.progress?.complete ? '已完成' : item.progress?.skipped ? '已关闭剩余' : '—')
          }}
        </dd>
        <dd v-if="item.progress && !item.progress.skipped" class="order-overview__hint">
          {{ formatQuantity(item.progress.actual) }} /
          {{ formatQuantity(item.progress.required) }} 基础数量
        </dd>
      </div>
      <div>
        <dt>结算状态</dt>
        <dd data-order-metric="settlement">
          <StatusTag
            :label="settlement"
            :tone="
              order.settlementStatus === 'full'
                ? 'success'
                : order.settlementStatus === 'partial'
                  ? 'info'
                  : 'neutral'
            "
          />
        </dd>
        <dd v-if="sale && order.afterSaleStatus" class="order-overview__hint">
          售后 {{ statusLabels[order.afterSaleStatus] ?? order.afterSaleStatus }}
        </dd>
      </div>
      <div v-if="mayReadCash && totals?.allocatedCashAmount != null">
        <dt>{{ sale ? '已核销收款' : '已核销付款' }}</dt>
        <dd data-order-metric="allocated-cash">{{ formatMoney(totals.allocatedCashAmount) }}</dd>
      </div>
      <div v-if="totals?.openAmount != null">
        <dt>{{ sale ? '应收未清' : '应付未清' }}</dt>
        <dd data-order-metric="open-amount">{{ formatMoney(totals.openAmount) }}</dd>
        <dd class="order-overview__hint">按已确认往来计算</dd>
      </div>
    </dl>
    <p v-if="loadingTotals" class="order-overview__note" role="status">正在读取结算金额…</p>
    <p v-else-if="totalsError" class="order-overview__note" role="status">
      结算金额暂时无法读取。<el-button link type="primary" @click="retry++">重新读取</el-button>
    </p>
    <p
      v-else-if="
        totals?.scope === 'visible' &&
        (totals.openAmount != null || totals.allocatedCashAmount != null)
      "
      class="order-overview__note"
    >
      结算金额仅汇总当前可见范围。
    </p>
    <ol class="order-overview__steps" aria-label="订单进度">
      <li
        v-for="(step, index) in overview.steps"
        :key="step.key"
        :data-step="step.key"
        :data-state="step.state"
        :aria-current="step.state === 'current' ? 'step' : undefined"
      >
        <span class="order-overview__marker" aria-hidden="true">{{
          step.state === 'done' ? '✓' : step.state === 'skipped' ? '·' : index + 1
        }}</span>
        <span class="order-overview__stage">
          {{ step.label
          }}<span class="order-overview__accessible">，{{ stepState[step.state] }}</span>
          <small
            v-if="
              step.state === 'done' &&
              (step.key === 'submit' || step.key === 'approve') &&
              dates[step.key]
            "
            >{{ dates[step.key] }}</small
          >
          <small v-else-if="step.state === 'skipped'">已关闭剩余</small>
        </span>
      </li>
    </ol>
    <p v-if="order.reason" class="order-overview__note">原因 {{ order.reason }}</p>
    <p v-if="order.remark" class="order-overview__note">备注 {{ order.remark }}</p>
  </section>
</template>

<style scoped>
.order-overview {
  display: grid;
  gap: 12px;
  padding: 16px;
  margin-bottom: 12px;
}
.order-overview__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
  color: var(--el-text-color-secondary);
}
.order-overview__meta span,
.order-overview__note {
  overflow-wrap: anywhere;
}
.order-overview__metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(132px, 1fr));
  gap: 12px;
  margin: 0;
}
.order-overview__metrics > div {
  min-width: 0;
}
.order-overview__metrics dt,
.order-overview__metrics .order-overview__hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.order-overview__metrics dd {
  margin: 4px 0 0;
  font-size: 20px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.order-overview__metrics .order-overview__hint {
  font-weight: normal;
}
.order-overview__steps {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
  list-style: none;
  border-top: 1px solid var(--el-border-color-light);
  padding: 12px 0 0;
  margin: 0;
}
.order-overview__steps li {
  display: flex;
  flex: 1;
  align-items: start;
  gap: 6px;
  min-width: 86px;
  color: var(--el-text-color-secondary);
}
.order-overview__marker {
  display: grid;
  place-items: center;
  flex: 0 0 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--el-fill-color);
  line-height: 1;
  font-weight: 600;
}
.order-overview__stage {
  color: var(--el-text-color-primary);
}
.order-overview__stage small {
  display: block;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}
.order-overview__steps [data-state='done'] .order-overview__marker {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
}
.order-overview__steps [aria-current='step'] .order-overview__marker {
  color: var(--el-color-white);
  background: var(--el-color-primary);
}
.order-overview__steps [aria-current='step'] .order-overview__stage {
  font-weight: 600;
}
.order-overview__accessible {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
.order-overview__note {
  margin: 0;
}
@media (max-width: 520px) {
  .order-overview {
    padding: 12px;
  }
  .order-overview__metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .order-overview__steps {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
