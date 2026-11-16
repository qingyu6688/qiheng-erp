<script setup lang="ts">
import { useDict } from '../hooks/useDict'
const props = defineProps<{
  type: string
  modelValue?: string | null
  disabled?: boolean
  label?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string | null] }>()
const { items, options, error, loading, reload, label: itemLabel } = useDict(props.type)
</script>
<template>
  <el-select
    :model-value="modelValue || ''"
    :aria-label="label ?? '字典项'"
    :loading="loading"
    :disabled="disabled"
    clearable
    @update:model-value="emit('update:modelValue', $event || null)"
  >
    <el-option
      v-if="modelValue && !options.some((item) => item.value === modelValue)"
      :value="modelValue"
      :label="
        itemLabel(modelValue) +
        (items.some((item) => item.value === modelValue) ? '（已停用）' : '')
      "
    />
    <el-option v-for="item in options" :key="item.id" :value="item.value" :label="item.label" />
  </el-select>
  <p v-if="error" role="alert">
    {{ error }} <button class="text-link" type="button" @click="reload(true)">重试</button>
  </p>
</template>
