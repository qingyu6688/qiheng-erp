<script setup lang="ts">
import { onMounted, onScopeDispose, ref } from 'vue'
import { businessUsers } from '../api/platform'
import type { BusinessUser } from '../api/platform'
import { isCancelled, messageOf } from '../api/http'
const props = defineProps<{
  modelValue?: string
  disabled?: boolean
  name?: string
  label?: string
  includeUnassigned?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const rows = ref<BusinessUser[]>([]),
  loading = ref(false),
  error = ref('')
let revision = 0
async function load(keyword = ''): Promise<void> {
  const current = ++revision
  loading.value = true
  error.value = ''
  try {
    const result = await businessUsers(keyword)
    if (current === revision) rows.value = result.items
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
}
onMounted(() => void load())
onScopeDispose(() => ++revision)
</script>
<template>
  <el-select
    :model-value="props.modelValue || ''"
    :aria-label="label ?? '业务员'"
    filterable
    remote
    clearable
    :remote-method="load"
    :loading="loading"
    :disabled="disabled"
    placeholder="默认本人；来源单据自动继承"
    @update:model-value="emit('update:modelValue', String($event ?? ''))"
  >
    <el-option v-if="includeUnassigned" value="0" label="未分配（历史数据）" />
    <el-option
      v-if="
        modelValue &&
        !(includeUnassigned && modelValue === '0') &&
        !rows.some((row) => row.id === modelValue)
      "
      :value="modelValue"
      :label="name || '原业务员（' + modelValue + '）'"
    />
    <el-option v-for="row in rows" :key="row.id" :value="row.id" :label="row.name" />
  </el-select>
  <p v-if="error" role="alert" class="form-error">
    {{ error }} <button class="text-link" type="button" @click="load()">重试</button>
  </p>
</template>
