<script setup lang="ts">
import { computed } from 'vue'
import type { Condition } from '../../types/workflow'
import { fieldLabels } from '../../types/workflow'
const props = defineProps<{
  modelValue?: Condition
  fields: Record<string, string>
  disabled?: boolean
  depth?: number
}>()
const emit = defineEmits<{ 'update:modelValue': [value: Condition | undefined] }>()
const mode = computed(() =>
  props.modelValue?.all
    ? 'all'
    : props.modelValue?.any
      ? 'any'
      : props.modelValue
        ? 'single'
        : 'none',
)
const items = computed(() => props.modelValue?.all ?? props.modelValue?.any ?? [])
function changeMode(value: string): void {
  emit(
    'update:modelValue',
    value === 'none'
      ? undefined
      : value === 'single'
        ? { field: 'totalAmount', op: '>', value: '50000' }
        : value === 'all'
          ? { all: [{ field: 'totalAmount', op: '>', value: '50000' }] }
          : { any: [{ field: 'totalAmount', op: '>', value: '50000' }] },
  )
}
function field(key: 'field' | 'op' | 'value', value: string): void {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}
function replace(index: number, value?: Condition): void {
  const next = items.value.slice()
  if (value) next[index] = value
  else next.splice(index, 1)
  emit('update:modelValue', mode.value === 'all' ? { all: next } : { any: next })
}
function add(): void {
  const next = [...items.value, { field: 'totalAmount', op: '>', value: '50000' }]
  emit('update:modelValue', mode.value === 'all' ? { all: next } : { any: next })
}
</script>
<template>
  <div class="flow-condition">
    <el-select
      :model-value="mode"
      aria-label="条件方式"
      :disabled="disabled"
      @update:model-value="changeMode"
    >
      <el-option label="不限制" value="none" /><el-option
        label="单项条件"
        value="single"
      /><el-option v-if="(depth ?? 0) < 4" label="以下条件全部满足" value="all" /><el-option
        v-if="(depth ?? 0) < 4"
        label="以下条件任一满足"
        value="any"
      />
    </el-select>
    <div v-if="mode === 'single'" class="flow-condition-fields">
      <el-select
        :model-value="modelValue?.field"
        aria-label="条件字段"
        :disabled="disabled"
        @update:model-value="(value: unknown) => field('field', String(value))"
        ><el-option
          v-for="(_, name) in fields"
          :key="name"
          :label="fieldLabels[name] ?? name"
          :value="name"
      /></el-select>
      <el-select
        :model-value="modelValue?.op"
        aria-label="条件运算"
        :disabled="disabled"
        @update:model-value="(value: unknown) => field('op', String(value))"
        ><el-option
          v-for="[value, label] in [
            ['==', '等于'],
            ['!=', '不等于'],
            ['>', '大于'],
            ['>=', '大于等于'],
            ['<', '小于'],
            ['<=', '小于等于'],
            ['in', '属于（逗号分隔）'],
          ]"
          :key="value"
          :value="value"
          :label="label"
      /></el-select>
      <el-select
        v-if="modelValue?.field && fields[modelValue.field] === 'boolean'"
        :model-value="modelValue.value"
        aria-label="条件值"
        :disabled="disabled"
        @update:model-value="(value: unknown) => field('value', String(value))"
        ><el-option label="是" value="true" /><el-option label="否" value="false"
      /></el-select>
      <el-input
        v-else
        :model-value="modelValue?.value"
        aria-label="条件值"
        :disabled="disabled"
        placeholder="填写条件值"
        maxlength="200"
        @update:model-value="(value: unknown) => field('value', String(value))"
      />
    </div>
    <div v-for="(item, index) in items" :key="index" class="flow-condition-group">
      <ConditionEditor
        :model-value="item"
        :fields="fields"
        :depth="(depth ?? 0) + 1"
        :disabled="disabled"
        @update:model-value="(value: Condition | undefined) => replace(index, value)"
      /><button v-if="!disabled" class="text-link" @click="replace(index)">移除此条件</button>
    </div>
    <el-button
      v-if="['all', 'any'].includes(mode) && items.length < 20"
      :disabled="disabled"
      @click="add"
      >增加条件</el-button
    >
  </div>
</template>
