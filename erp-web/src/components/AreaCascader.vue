<script setup lang="ts">
import { onMounted, onScopeDispose, ref } from 'vue'
import { areaTree } from '../api/platform'
import type { AreaNode } from '../api/platform'
import { isCancelled, messageOf } from '../api/http'
defineProps<{ modelValue?: string | null; disabled?: boolean; label?: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string | null] }>()
const nodes = ref<AreaNode[]>([]),
  loading = ref(false),
  error = ref('')
let active = true
async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await areaTree()
    if (active) nodes.value = result.items
  } catch (cause: unknown) {
    if (active && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (active) loading.value = false
  }
}
onMounted(() => void load())
onScopeDispose(() => {
  active = false
})
</script>
<template>
  <el-cascader
    :model-value="modelValue || undefined"
    :aria-label="label ?? '省市区'"
    :options="nodes"
    :props="{ value: 'id', label: 'name', emitPath: false, checkStrictly: true }"
    filterable
    clearable
    :disabled="disabled || loading"
    placeholder="请选择省、市、区县"
    @update:model-value="emit('update:modelValue', $event ? String($event) : null)"
  />
  <p v-if="error" role="alert">
    {{ error }} <button class="text-link" type="button" @click="load">重试</button>
  </p>
</template>
