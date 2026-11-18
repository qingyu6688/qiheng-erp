<script setup lang="ts">
import { computed } from 'vue'
import { useDict } from '../hooks/useDict'
import StatusTag from './StatusTag.vue'
import type { StatusTone } from '../types/status'
const props = defineProps<{ type: string; value?: string | null }>()
const { items, label } = useDict(props.type)
const tone = computed((): StatusTone => {
  const value = items.value.find((item) => item.value === props.value)?.color
  if (value === 'primary') return 'info'
  return value === 'success' || value === 'warning' || value === 'danger' ? value : 'neutral'
})
</script>
<template>
  <StatusTag v-if="value" :label="label(value)" :tone="tone" />
  <span v-else>—</span>
</template>
