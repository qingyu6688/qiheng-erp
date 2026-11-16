<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue'
import {
  ArrowRight,
  Box,
  Calendar,
  Clock,
  Document,
  List,
  Plus,
  Refresh,
  ShoppingCart,
  Tickets,
  Van,
  Wallet,
  Warning,
} from '@element-plus/icons-vue'
import { workbenchSummary } from '../api/workbench'
import type { WorkbenchSummary } from '../api/workbench'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'
import { formatMoney, formatQuantity, formatRelativeTime, formatDateTime } from '../utils/format'
import { documentLink } from '../types/reporting'
import WorkbenchTradeSummary from '../components/WorkbenchTradeSummary.vue'

const metricIcons: Record<string, typeof Tickets> = {
  'sales-orders': Tickets,
  'purchase-orders': ShoppingCart,
  'purchase-requisitions': Document,
  shipments: Van,
  receipts: Wallet,
  payments: Wallet,
  'refund-requests': Wallet,
  'inventory-shortage': Box,
}
const session = useSessionStore(),
  value = ref<WorkbenchSummary>(),
  error = ref(''),
  loading = ref(false)
let revision = 0,
  controller = new AbortController()
async function load(): Promise<void> {
  const current = ++revision
  controller.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  value.value = undefined
  if (!session.companyId) {
    loading.value = false
    return
  }
  try {
    const data = await workbenchSummary(controller.signal)
    if (current === revision) value.value = data
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
}
const today = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  dateStyle: 'full',
}).format(new Date())
watch(
  () => session.companyId,
  () => {
    void load()
  },
  { immediate: true },
)
onScopeDispose(() => {
  ++revision
  controller.abort()
})
</script>
<template>
  <div class="page-heading workbench-heading">
    <div>
      <h1>工作台</h1>
      <p>
        <Calendar class="icon" aria-hidden="true" />{{ today
        }}<span v-if="value"> · 数据更新 {{ formatDateTime(value.asOf) }}</span>
      </p>
    </div>
    <div class="sales-actions">
      <router-link
        v-if="session.can('sales_order:create') && session.company"
        class="el-button el-button--large el-button--primary"
        to="/sales-orders/new"
        ><Plus class="icon" aria-hidden="true" />新建销售订单</router-link
      ><router-link
        v-if="session.can('receipt:view') && session.company"
        class="el-button el-button--large"
        to="/receipts"
        >登记收款</router-link
      ><el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <WorkbenchTradeSummary v-if="session.can('report:sales_summary') || session.can('report:purchase_summary')" />
  <p v-if="!session.company" class="panel workbench-message">
    当前账户没有可访问的公司，请联系管理员配置数据范围。
  </p>
  <p v-if="loading" role="status" class="panel workbench-message">正在加载待办…</p>
  <template v-if="value">
    <div v-if="value.blocks.length" class="workbench-stats">
      <router-link
        v-for="block in value.blocks"
        :key="block.key"
        class="workbench-stat"
        :data-metric="block.key"
        :to="block.url"
      >
        <div>
          <span class="metric-label">{{ block.label }}</span>
          <strong
            >{{ block.count
            }}<small>{{ block.key === 'inventory-shortage' ? '个商品' : '张单据' }}</small></strong
          >
        </div>
        <span class="metric-icon"
          ><component :is="metricIcons[block.key] ?? Document" aria-hidden="true"
        /></span>
      </router-link>
    </div>
    <div class="workbench-columns">
      <div>
        <section class="panel table-panel">
          <div class="panel-heading">
            <h2><List class="icon" aria-hidden="true" />待办</h2>
            <span class="muted">最多显示 10 条</span>
          </div>
          <el-table :data="value.todos"
            ><el-table-column prop="label" label="事项" min-width="150" /><el-table-column
              label="单据"
              min-width="220"
              ><template #default="{ row }"
                ><router-link class="text-link" :to="row.url">{{
                  row.documentNo
                }}</router-link></template
              ></el-table-column
            ><el-table-column prop="partnerName" label="对象" min-width="130" /><el-table-column
              label="金额"
              align="right"
              ><template #default="{ row }">{{
                formatMoney(row.amount)
              }}</template></el-table-column
            ><el-table-column prop="businessDate" label="业务日期" min-width="115" /><template
              #empty
              >暂无需要处理的事项</template
            ></el-table
          >
        </section>
        <section v-if="value.inventoryAlerts" class="panel table-panel">
          <div class="panel-heading">
            <h2><Warning class="icon" aria-hidden="true" />库存提醒</h2>
            <router-link class="text-link" to="/inventory-balances">查看实时库存</router-link>
          </div>
          <el-table :data="value.inventoryAlerts"
            ><el-table-column label="商品" min-width="190"
              ><template #default="{ row }"
                >{{ row.skuName }}<small class="cell-secondary">{{ row.skuCode }}</small></template
              ></el-table-column
            ><el-table-column prop="warehouseName" label="仓库" /><el-table-column
              label="可用"
              align="right"
              ><template #default="{ row }">{{
                formatQuantity(row.availableQty)
              }}</template></el-table-column
            ><el-table-column label="待审核需求" align="right"
              ><template #default="{ row }">{{
                formatQuantity(row.pendingDemandQty)
              }}</template></el-table-column
            ><template #empty>当前没有需求超过可用库存的商品</template></el-table
          >
        </section>
      </div>
      <aside v-if="value.periods || value.recentActions" aria-label="会计期间与操作动态">
        <section v-if="value.periods" class="panel workbench-side">
          <h2><Calendar class="icon" aria-hidden="true" />本月期间</h2>
          <div v-for="period in value.periods" :key="period.id" class="period-summary">
            <p>
              <el-tag :type="period.status === 'open' ? 'primary' : 'info'">{{
                period.status === 'open' ? '开放' : '已关闭'
              }}</el-tag>
              {{ period.period }}
            </p>
            <p>{{ period.startDate }} 至 {{ period.endDate }}</p>
          </div>
          <p v-if="!value.periods.length">当天尚未开启会计期间</p>
          <router-link class="text-link period-link" to="/accounting-periods"
            >查看会计期间与关账<ArrowRight aria-hidden="true"
          /></router-link>
        </section>
        <section v-if="value.recentActions" class="panel workbench-side">
          <h2><Clock class="icon" aria-hidden="true" />我最近的操作</h2>
          <ul class="recent-actions">
            <li v-for="(item, index) in value.recentActions" :key="index">
              <router-link
                v-if="documentLink(item.objectType, item.objectId)"
                :to="documentLink(item.objectType, item.objectId)!"
                class="text-link"
                >查看相关单据</router-link
              ><span v-else>资料操作</span><small>{{ formatRelativeTime(item.createdAt) }}</small>
            </li>
          </ul>
          <p v-if="!value.recentActions.length">暂无操作记录</p>
        </section>
      </aside>
    </div>
  </template>
</template>

<style scoped>
.workbench-heading h1 {
  font-size: 26px;
  line-height: 1.3;
  letter-spacing: -0.015em;
}
.workbench-heading p {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
}
.workbench-heading p .icon {
  width: 15px;
  height: 15px;
}
.workbench-heading .sales-actions .icon {
  width: 15px;
  height: 15px;
  margin-right: 5px;
}
.workbench-message {
  padding: 18px 20px;
}
.workbench-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}
.workbench-stat {
  --metric-color: var(--el-color-primary);
  --metric-tint: var(--el-color-primary-light-9);
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
  min-height: 110px;
  padding: 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-bg-color);
  text-decoration: none;
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease;
}
.workbench-stat:hover {
  border-color: var(--el-color-primary-light-6);
  box-shadow: 0 4px 14px #2736590b;
}
.workbench-stat[data-metric^='purchase'] {
  --metric-color: #7051b7;
  --metric-tint: #f0ebff;
}
.workbench-stat[data-metric='shipments'],
.workbench-stat[data-metric='inventory-shortage'] {
  --metric-color: var(--el-color-warning);
  --metric-tint: var(--el-color-warning-light-9);
}
.workbench-stat[data-metric='receipts'],
.workbench-stat[data-metric='payments'],
.workbench-stat[data-metric='refund-requests'] {
  --metric-color: var(--el-color-success);
  --metric-tint: var(--el-color-success-light-9);
}
.metric-label {
  font-weight: 500;
}
.metric-icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  color: var(--metric-color);
  background: var(--metric-tint);
}
.metric-icon svg {
  width: 21px;
  height: 21px;
}
.workbench-stat strong {
  display: block;
  margin-top: 5px;
  color: var(--el-text-color-primary);
  font-size: 29px;
  line-height: 1.25;
  font-variant-numeric: tabular-nums;
}
.workbench-stat small {
  margin-left: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
}
.workbench-columns {
  display: grid;
  gap: 18px;
}
.workbench-columns:has(aside) {
  grid-template-columns: minmax(0, 1fr) minmax(230px, 27%);
}
.workbench-columns > * {
  min-width: 0;
}
.workbench-columns .panel + .panel {
  margin-top: 18px;
}
.workbench-side {
  padding: 18px 20px;
}
.workbench-columns h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 16px;
}
.workbench-columns h2 .icon {
  color: var(--el-color-primary);
}
.workbench-columns .panel-heading {
  gap: 8px;
  flex-wrap: wrap;
}
.workbench-columns .panel-heading > :last-child {
  font-size: 13px;
}
.period-summary {
  margin: 12px 0;
  padding: 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}
.period-summary p {
  margin: 0;
}
.period-summary p + p {
  margin-top: 6px;
  font-size: 13px;
}
.period-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.period-link svg {
  width: 13px;
  height: 13px;
}
.recent-actions {
  list-style: none;
  margin: 10px 0 0;
  padding: 0;
}
.recent-actions li {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 9px 0;
}
.recent-actions li + li {
  border-top: 1px solid var(--el-border-color-light);
}
.recent-actions small {
  color: var(--el-text-color-secondary);
}
@media (max-width: 1160px) {
  .workbench-columns:has(aside) {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 1050px) {
  .workbench-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 480px) {
  .workbench-stats {
    gap: 10px;
  }
  .workbench-stat {
    padding: 12px 10px;
  }
  .metric-icon {
    width: 28px;
    height: 28px;
    border-radius: 7px;
  }
  .metric-icon svg {
    width: 17px;
    height: 17px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .workbench-stat {
    transition: none;
  }
}
</style>
