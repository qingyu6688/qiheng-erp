<script setup lang="ts">
import { useDisplayDensity } from '../hooks/useDisplayDensity'

withDefaults(defineProps<{ label?: string }>(), { label: '表格密度' })
const density = useDisplayDensity()

function updateDensity(event: Event): void {
  if (!density || !(event.currentTarget instanceof HTMLSelectElement)) return
  const select = event.currentTarget
  const next = select.value
  if (next === 'compact' || next === 'default' || next === 'comfortable') density.value = next
  select.value = density.value
}
</script>

<template>
  <label v-if="density !== undefined" class="density-select">
    {{ label }}
    <select :value="density" @change="updateDensity">
      <option value="compact">紧凑</option>
      <option value="default">默认</option>
      <option value="comfortable">宽松</option>
    </select>
  </label>
</template>

<style scoped>
.density-select {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--erp-text-2);
}

.density-select select {
  min-height: var(--erp-control);
  padding-inline: 8px;
  color: var(--erp-text-1);
  background: var(--erp-bg);
  border: 1px solid var(--erp-border-2);
  border-radius: var(--erp-radius);
  font-size: 14px;
}
</style>
