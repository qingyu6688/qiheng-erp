<script setup lang="ts">
import { computed } from 'vue'
import { formatMoney } from '../utils/format'

const props = defineProps<{ value?: string | null; signed?: boolean }>()
const formatted = computed(() => formatMoney(props.value))
const isZero = computed(() => /^-?0+(?:\.0+)?$/.test(props.value ?? ''))
const muted = computed(() => isZero.value || formatted.value === '—')
const display = computed(() => {
  if (isZero.value) return '0.00'
  const positive = props.signed && !muted.value && !props.value?.startsWith('-')
  return (positive ? '+' : '') + formatted.value
})
</script>

<template>
  <span class="financial-amount" :class="{ 'is-muted': muted }">{{ display }}</span>
</template>

<style scoped>
.financial-amount {
  color: var(--erp-primary-7);
  font-size: 16px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.financial-amount.is-muted {
  color: var(--erp-text-2);
  font-size: inherit;
  font-weight: 400;
}
</style>
