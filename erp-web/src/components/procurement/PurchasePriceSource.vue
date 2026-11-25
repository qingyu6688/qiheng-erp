<script setup lang="ts">
import type { PurchasePriceSource } from '../../types/purchase-pricing'
import { purchasePriceSourceLabels } from '../../types/purchase-pricing'
import { formatPrice } from '../../utils/format'
defineProps<{ source?: PurchasePriceSource | null }>()
</script>
<template>
  <div v-if="source" class="purchase-price-source">
    <span v-if="source.source === 'manual'">手工录入 · </span>
    <span>{{ purchasePriceSourceLabels[source.referenceSource] }}</span>
    <strong v-if="source.suggestedUnitPrice !== null">{{ formatPrice(source.suggestedUnitPrice) }}</strong>
    <span v-if="source.priceListVersion !== null">版本 {{ source.priceListVersion }}</span>
    <el-tag v-if="source.abovePriceList" type="warning" size="small">超出协议价</el-tag>
  </div>
</template>
<style scoped>
.purchase-price-source { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; margin-top: 6px; color: var(--erp-text-2); font-size: 12px; line-height: 1.6; }
.purchase-price-source strong { color: var(--erp-primary-7); font-variant-numeric: tabular-nums; }
</style>
