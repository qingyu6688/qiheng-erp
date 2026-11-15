<script setup lang="ts">
import { computed } from 'vue'
import { useSessionStore } from '../../stores/session'

const props = defineProps<{ details: unknown }>()
const session = useSessionStore()
interface Dependency { type: string; number?: string; id?: string; relation: string }
const resources: Record<string, { label: string; path: string; permission: string }> = {
  shipment: { label: '发货单', path: 'shipments', permission: 'shipment:view' },
  sales_return: { label: '销售退货单', path: 'sales-returns', permission: 'sales_return:view' },
  goods_receipt: { label: '采购收货单', path: 'goods-receipts', permission: 'goods_receipt:view' },
  purchase_return: { label: '采购退货单', path: 'purchase-returns', permission: 'purchase_return:view' },
  receipt: { label: '收款单', path: 'receipts', permission: 'receipt:view' },
  payment: { label: '付款单', path: 'payments', permission: 'payment:view' },
  account_transfer: { label: '账户转账单', path: 'account-transfers', permission: 'account_transfer:view' },
  stock_adjustment: { label: '库存调整单', path: 'stock-adjustments', permission: 'stock_adjustment:view' },
  refund_request: { label: '退款申请', path: 'refund-requests', permission: 'refund_request:view' },
  invoice_registration: { label: '开票登记', path: 'invoice-registrations', permission: 'invoice_registration:view' },
  settlement_allocation: { label: '核销记录', path: 'settlements', permission: 'settlement:view' },
}
function dependency(value: unknown): Dependency | undefined {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return undefined
  const row = value as Record<string, unknown>
  if (typeof row.documentType !== 'string') return undefined
  return { type: row.documentType, number: typeof row.documentNo === 'string' && row.documentNo ? row.documentNo : undefined, id: typeof row.documentId === 'string' ? row.documentId : undefined, relation: typeof row.relation === 'string' ? row.relation : '存在下游引用，请先处理' }
}
const dependencies = computed(() => Array.isArray(props.details) ? props.details.map(dependency).filter((item): item is Dependency => item !== undefined) : [])
function label(item: Dependency): string { return resources[item.type]?.label ?? '关联单据' }
function target(item: Dependency): string | undefined {
  const resource = resources[item.type]
  if (!resource || !session.can(resource.permission)) return undefined
  if (item.type === 'settlement_allocation') return '/settlements'
  return item.id && /^[0-9]+$/.test(item.id) ? '/' + resource.path + '/' + item.id : undefined
}
</script>
<template>
  <section v-if="dependencies.length" aria-label="冲销依赖单据">
    <h3>请先处理以下单据</h3>
    <ul><li v-for="(item, index) in dependencies" :key="index"><router-link v-if="target(item)" class="text-link" :to="target(item)!">{{ label(item) }}{{ item.number ? ' · ' + item.number : '' }}</router-link><span v-else>{{ label(item) }}{{ item.number ? ' · ' + item.number : '' }}</span>：{{ item.relation }}</li></ul>
  </section>
</template>
