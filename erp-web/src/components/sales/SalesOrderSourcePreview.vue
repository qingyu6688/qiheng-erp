<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue'
import { ElSkeleton } from 'element-plus/es/components/skeleton/index.mjs'
import 'element-plus/theme-chalk/el-skeleton.css'
import { document as salesDocument } from '../../api/sales'
import { isCancelled, messageOf } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import type { SalesDocument } from '../../types/sales'
import { formatMoney, formatQuantity } from '../../utils/format'
import FinancialAmount from '../FinancialAmount.vue'

const props = defineProps<{ id: string }>()
const emit = defineEmits<{ close: [] }>()
const session = useSessionStore(), value = ref<SalesDocument>(), error = ref(''), loading = ref(false)
const initialScope = `${session.user?.id ?? ''}|${session.companyId ?? ''}`
let revision = 0, controller: AbortController | undefined
async function load(): Promise<void> {
  const current = ++revision
  controller?.abort(); controller = new AbortController(); value.value = undefined; error.value = ''; loading.value = true
  try {
    const result = await salesDocument('sales-orders', props.id, controller.signal)
    if (current === revision) value.value = result
  } catch (cause: unknown) { if (current === revision && !isCancelled(cause)) error.value = messageOf(cause) }
  finally { if (current === revision) loading.value = false }
}
watch(() => props.id, () => { void load() }, { immediate: true })
watch(() => [session.companyId, session.user?.id, session.can('sales_order:view')], () => {
  if (`${session.user?.id ?? ''}|${session.companyId ?? ''}` !== initialScope || !session.can('sales_order:view')) { ++revision; controller?.abort(); value.value = undefined; emit('close') }
})
onScopeDispose(() => { ++revision; controller?.abort() })
</script>
<template>
  <el-drawer :model-value="true" :title="'销售订单 · ' + (value?.documentNo ?? '只读详情')" direction="rtl" size="min(1120px, 96vw)" append-to-body @close="emit('close')">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-skeleton v-if="loading" :rows="7" animated />
    <template v-else-if="value">
      <dl class="order-facts" aria-label="销售订单来源资料">
        <div><dt>客户</dt><dd>{{ value.customer?.name ?? value.partnerName }}</dd></div>
        <div><dt>订单日期</dt><dd>{{ value.businessDate }}</dd></div>
        <div><dt>价税合计</dt><dd><FinancialAmount :value="value.totalAmount" /></dd></div>
        <div><dt>交货日期</dt><dd>{{ value.deliveryDate ?? '—' }}</dd></div>
      </dl>
      <p class="source-help">此处仅查看订单，关闭后返回报价。</p>
      <el-table :data="value.lines ?? []" row-key="id" empty-text="暂无订单明细">
        <el-table-column label="商品" min-width="180"><template #default="{ row }">{{ row.sku?.name ?? row.skuName }}</template></el-table-column>
        <el-table-column label="数量" min-width="120" align="right"><template #default="{ row }">{{ formatQuantity(row.qty, row.unit?.name ?? row.unitName) }}</template></el-table-column>
        <el-table-column label="单价" min-width="120" align="right"><template #default="{ row }">{{ formatMoney(row.unitPrice) }}</template></el-table-column>
        <el-table-column label="含税金额" min-width="140" align="right"><template #default="{ row }"><FinancialAmount :value="row.totalAmount ?? row.receivableNet" /></template></el-table-column>
      </el-table>
    </template>
    <template #footer><el-button @click="emit('close')">关闭</el-button></template>
  </el-drawer>
</template>
<style scoped>
.source-help { color: var(--erp-text-2); font-size: 13px; margin: 20px 0; }
.order-facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px 28px; margin: 12px 0 24px; }
.order-facts dt { color: var(--erp-text-2); font-size: 12px; margin-bottom: 6px; }
.order-facts dd { margin: 0; line-height: 1.6; overflow-wrap: anywhere; }
</style>
