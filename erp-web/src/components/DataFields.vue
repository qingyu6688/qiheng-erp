<script setup lang="ts">
import type { FormField, SelectOption } from '../types/admin'
import AreaCascader from './AreaCascader.vue'
import DictionarySelect from './DictionarySelect.vue'

defineProps<{
  fields: FormField[]
  values: Record<string, unknown>
  options?: Record<string, SelectOption[]>
  editing?: boolean
  disabled?: boolean
}>()
const emit = defineEmits<{ change: [key: string, value: unknown] }>()
function text(value: unknown): string {
  return value == null ? '' : String(value)
}
function selection(value: unknown): string | string[] {
  return Array.isArray(value) ? value.map(String) : text(value)
}
</script>
<template>
  <el-form label-position="top" class="admin-form" @submit.prevent>
    <el-form-item
      v-for="field in fields"
      v-show="!(editing && field.createOnly)"
      :key="field.key"
      :label="field.label"
      :for="'field-' + field.key"
      :required="field.required"
    >
      <AreaCascader
        v-if="field.kind === 'area'"
        :model-value="text(values[field.key])"
        :label="field.label"
        :disabled="disabled || (editing && field.readonlyOnEdit)"
        @update:model-value="emit('change', field.key, $event)"
      />
      <DictionarySelect
        v-else-if="field.kind === 'dict'"
        :type="field.dictionary ?? ''"
        :model-value="text(values[field.key])"
        :label="field.label"
        :disabled="disabled || (editing && field.readonlyOnEdit)"
        @update:model-value="emit('change', field.key, $event)"
      />
      <el-select
        v-else-if="field.kind === 'tags'"
        :model-value="selection(values[field.key])"
        multiple
        filterable
        allow-create
        default-first-option
        :aria-label="field.label"
        :disabled="disabled || (editing && field.readonlyOnEdit)"
        @update:model-value="emit('change', field.key, $event)"
      />
      <el-switch
        v-else-if="field.kind === 'boolean'"
        :id="'field-' + field.key"
        :model-value="Boolean(values[field.key])"
        :disabled="disabled || (editing && field.readonlyOnEdit)"
        @update:model-value="emit('change', field.key, $event)"
      />
      <el-select
        v-else-if="field.kind === 'select' || field.kind === 'multiple'"
        :id="'field-' + field.key"
        :model-value="selection(values[field.key])"
        :multiple="field.kind === 'multiple'"
        filterable
        clearable
        :disabled="disabled || (editing && field.readonlyOnEdit)"
        @update:model-value="emit('change', field.key, $event)"
      >
        <el-option
          v-for="option in options?.[field.key] ?? field.options ?? []"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
      <el-input-number
        v-else-if="field.kind === 'number'"
        :id="'field-' + field.key"
        :model-value="Number(values[field.key] || 0)"
        :min="0"
        :max="field.max"
        :disabled="disabled || (editing && field.readonlyOnEdit)"
        @update:model-value="emit('change', field.key, $event ?? 0)"
      />
      <el-input
        v-else
        :id="'field-' + field.key"
        :model-value="text(values[field.key])"
        :type="
          field.kind === 'password'
            ? 'password'
            : field.kind === 'date'
              ? 'date'
              : field.kind === 'textarea'
                ? 'textarea'
                : 'text'
        "
        :maxlength="field.max"
        :show-password="field.kind === 'password'"
        :disabled="disabled || (editing && field.readonlyOnEdit)"
        @update:model-value="emit('change', field.key, $event)"
      />
    </el-form-item>
  </el-form>
</template>
