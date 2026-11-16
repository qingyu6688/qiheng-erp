<script setup lang="ts">
import { computed } from 'vue'
import { useDict } from '../hooks/useDict'
const props = defineProps<{ type: string; value?: string | null }>()
const { items, label } = useDict(props.type)
const color = computed(() => {
  const value = items.value.find((item) => item.value === props.value)?.color
  return value === 'success' || value === 'warning' || value === 'danger' || value === 'primary'
    ? value
    : 'info'
})
</script>
<template>
  <el-tag v-if="value" :type="color">{{ label(value) }}</el-tag
  ><span v-else>—</span>
</template>
