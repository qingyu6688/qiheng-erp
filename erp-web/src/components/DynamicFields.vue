<script setup lang="ts">
import type { CustomField, FieldValue, FieldValues } from '../api/platform-rules'

const props = defineProps<{ fields: CustomField[]; modelValue: FieldValues; prefix: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: FieldValues] }>()

function current(field: CustomField): FieldValue {
  return Object.hasOwn(props.modelValue, field.fieldKey)
    ? (props.modelValue[field.fieldKey] ?? null)
    : field.defaultValue
}
function asText(field: CustomField): string {
  const value = current(field)
  return typeof value === 'string' ? value : ''
}
function change(field: CustomField, value: FieldValue): void {
  emit('update:modelValue', { ...props.modelValue, [field.fieldKey]: value })
}
</script>

<template>
  <div class="dynamic-fields">
    <el-form-item
      v-for="field in fields"
      :key="field.fieldKey"
      :label="field.label"
      :required="field.required"
      :for="prefix + field.fieldKey"
    >
      <el-select
        v-if="field.type === 'select' || field.type === 'multi_select'"
        :id="prefix + field.fieldKey"
        :model-value="current(field) ?? undefined"
        :multiple="field.type === 'multi_select'"
        clearable
        :aria-label="field.label"
        @update:model-value="change(field, $event ?? null)"
      >
        <el-option v-for="value in field.choices" :key="value" :label="value" :value="value" />
      </el-select>
      <el-select
        v-else-if="field.type === 'bool'"
        :id="prefix + field.fieldKey"
        :model-value="current(field) ?? undefined"
        :aria-label="field.label"
        clearable
        @update:model-value="change(field, $event ?? null)"
      >
        <el-option label="是" :value="true" /><el-option label="否" :value="false" />
      </el-select>
      <el-input
        v-else
        :id="prefix + field.fieldKey"
        :model-value="asText(field)"
        :type="
          field.type === 'textarea'
            ? 'textarea'
            : field.type === 'date'
              ? 'date'
              : field.type === 'datetime'
                ? 'datetime-local'
                : 'text'
        "
        :placeholder="
          field.type === 'user'
            ? '输入用户编号'
            : field.type === 'department'
              ? '输入部门编号'
              : undefined
        "
        :inputmode="
          field.type === 'number' || field.type === 'user' || field.type === 'department'
            ? 'numeric'
            : field.type === 'decimal'
              ? 'decimal'
              : 'text'
        "
        @update:model-value="change(field, $event)"
      />
    </el-form-item>
  </div>
</template>

<style scoped>
.dynamic-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(240px, 100%), 1fr));
  gap: 0 16px;
}
.dynamic-fields :deep(.el-select) {
  width: 100%;
}
</style>
