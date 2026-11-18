<script setup lang="ts">
import { Box, Document, ShoppingCart, Tickets, Wallet } from '@element-plus/icons-vue'
import { onMounted, onScopeDispose, ref } from 'vue'
import type { WorkflowListRow } from '../../types/workflow'
import { businessLabels } from '../../types/workflow'
import { formatDateTime, formatMoney } from '../../utils/format'

defineProps<{
  rows: WorkflowListRow[]
  active?: string
  selection: string[]
  selectionGroup?: string
  busy: boolean
  loading: boolean
}>()
const emit = defineEmits<{ open: [row: WorkflowListRow]; toggle: [row: WorkflowListRow] }>()
const time = ref(Date.now())
let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  timer = setInterval(() => {
    time.value = Date.now()
  }, 60000)
})
onScopeDispose(() => clearInterval(timer))

function elapsed(created: string): string {
  const date = Date.parse(created)
  if (!Number.isFinite(date)) return '—'
  const minutes = Math.floor((time.value - date) / 60000)
  if (minutes < 1) return '刚到达'
  if (minutes < 60) return `已等待 ${minutes} 分钟`
  const hours = Math.floor(minutes / 60)
  return hours < 24 ? `已等待 ${hours} 小时` : `已等待 ${Math.floor(hours / 24)} 天`
}
function icon(type: WorkflowListRow['businessType']) {
  if (['sales_order', 'purchase_order', 'purchase_requisition'].includes(type)) return ShoppingCart
  if (['refund_request', 'gl_voucher', 'credit_increase', 'ar_bad_debt'].includes(type))
    return Wallet
  if (
    [
      'stock_count',
      'stock_adjustment',
      'stock_misc_doc',
      'mfg_issue_doc',
      'mfg_receipt_doc',
    ].includes(type)
  )
    return Box
  if (type === 'wf_generic_request') return Tickets
  return Document
}
</script>

<template>
  <section class="panel workflow-todo-list" aria-label="待办列表" :aria-busy="loading">
    <slot name="filters" />
    <p v-if="loading && !rows.length" class="todo-empty" role="status">正在读取待办…</p>
    <p v-else-if="!rows.length" class="todo-empty">暂无符合条件的待办</p>
    <ul v-else class="todo-items">
      <li v-for="row in rows" :key="row.id" :class="{ 'is-current': active === row.id }">
        <input
          v-if="row.batchGroup && row.taskVersion !== undefined"
          type="checkbox"
          :checked="selection.includes(row.id)"
          :disabled="busy || (Boolean(selectionGroup) && selectionGroup !== row.batchGroup)"
          :aria-label="'选择 ' + row.title + ' · ' + row.nodeName"
          @change="emit('toggle', row)"
        />
        <button
          type="button"
          class="todo-open"
          :aria-pressed="active === row.id"
          :disabled="busy"
          @click="emit('open', row)"
        >
          <span class="todo-icon" aria-hidden="true"
            ><component :is="icon(row.businessType)"
          /></span>
          <span class="todo-content">
            <strong>{{ row.title }}</strong>
            <span>{{
              [businessLabels[row.businessType], row.starterName, row.nodeName]
                .filter(Boolean)
                .join(' · ')
            }}</span>
            <time :datetime="row.itemCreatedAt" :title="formatDateTime(row.itemCreatedAt)">{{
              elapsed(row.itemCreatedAt)
            }}</time>
          </span>
          <span v-if="row.totalAmount != null" class="todo-amount">{{
            formatMoney(row.totalAmount)
          }}</span>
        </button>
      </li>
    </ul>
    <slot name="pagination" />
  </section>
</template>

<style scoped>
.workflow-todo-list {
  overflow: hidden;
}
.todo-empty {
  padding: 24px 16px;
  color: var(--erp-text-3);
  text-align: center;
}
.todo-items {
  padding: 0;
  margin: 0;
  list-style: none;
}
.todo-items li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  border-top: 1px solid var(--erp-border-1);
  border-inline-start: 3px solid transparent;
  padding-inline-start: 12px;
}
.todo-items li:hover {
  background: var(--erp-fill-1);
}
.todo-items li.is-current {
  border-inline-start-color: var(--erp-primary-6);
  background: var(--erp-primary-1);
}
.todo-items input {
  flex: none;
  margin: 18px 0 0;
  accent-color: var(--erp-primary-6);
}
.todo-open {
  display: flex;
  flex: 1;
  min-width: 0;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 12px 12px 0;
  text-align: start;
  border: 0;
  background: transparent;
  color: var(--erp-text-1);
  font: inherit;
  cursor: pointer;
}
.todo-open:disabled {
  cursor: default;
}
.todo-open:focus-visible {
  outline: 2px solid var(--erp-primary-6);
  outline-offset: -2px;
}
.todo-icon {
  display: grid;
  place-items: center;
  flex: 0 0 28px;
  height: 28px;
  border-radius: var(--erp-radius-sm);
  background: var(--erp-primary-1);
  color: var(--erp-primary-6);
}
.todo-icon svg {
  width: 16px;
  height: 16px;
}
.todo-content {
  display: grid;
  flex: 1;
  min-width: 0;
  gap: 3px;
  overflow-wrap: anywhere;
}
.todo-content span,
.todo-content time {
  font-size: 12px;
  color: var(--erp-text-3);
}
.todo-items li.is-current .todo-content span,
.todo-items li.is-current .todo-content time {
  color: var(--erp-text-2);
}
.todo-amount {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
</style>
