<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue'
import { workbenchSummary } from '../api/workbench'
import type { WorkbenchSummary } from '../api/workbench'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'
import { formatMoney, formatQuantity, formatRelativeTime, formatDateTime } from '../utils/format'
import { documentLink } from '../types/reporting'
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
  <div class="page-heading">
    <div>
      <h1>工作台</h1>
      <p>
        {{ today }}<span v-if="value"> · 数据更新 {{ formatDateTime(value.asOf) }}</span>
      </p>
    </div>
    <div class="sales-actions">
      <router-link
        v-if="session.can('sales_order:create') && session.company"
        class="el-button"
        to="/sales-orders/new"
        >新建销售订单</router-link
      ><router-link
        v-if="session.can('receipt:view') && session.company"
        class="el-button"
        to="/receipts"
        >登记收款</router-link
      ><el-button :loading="loading" @click="load">刷新</el-button>
    </div>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <p v-if="!session.company">当前账户没有可访问的公司，请联系管理员配置数据范围。</p>
  <p v-if="loading" role="status">正在加载待办…</p>
  <template v-if="value"
    ><div class="workbench-stats">
      <router-link
        v-for="block in value.blocks"
        :key="block.key"
        class="workbench-stat"
        :to="block.url"
        ><span>{{ block.label }}</span
        ><strong
          >{{ block.count
          }}<small>{{ block.key === 'inventory-shortage' ? '个商品' : '张单据' }}</small></strong
        ></router-link
      >
    </div>
    <div class="workbench-columns">
      <div>
        <section class="panel table-panel">
          <div class="panel-heading">
            <h2>待办</h2>
            <span class="muted">只显示当前有权处理的单据，最多 10 条</span>
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
            <h2>库存提醒</h2>
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
      <aside>
        <section v-if="value.periods" class="panel workbench-side">
          <h2>本月期间</h2>
          <div v-for="period in value.periods" :key="period.id">
            <p>
              <el-tag :type="period.status === 'open' ? 'primary' : 'info'">{{
                period.status === 'open' ? '开放' : '已关闭'
              }}</el-tag>
              {{ period.period }}
            </p>
            <p>{{ period.startDate }} 至 {{ period.endDate }}</p>
          </div>
          <p v-if="!value.periods.length">当天尚未开启会计期间</p>
          <router-link class="text-link" to="/accounting-periods">查看会计期间与关账</router-link>
        </section>
        <section v-if="value.recentActions" class="panel workbench-side">
          <h2>我最近的操作</h2>
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
