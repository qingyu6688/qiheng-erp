<script setup lang="ts">
import { computed } from 'vue'
import type { DataRow } from '../../types/admin'
import { catalogLabel } from './useManufacturing'

const model = defineModel<string | null>()
const props = defineProps<{
  rows: DataRow[]
  label: string
  optional?: boolean
  disabled?: boolean
}>()
const options = computed(() =>
  model.value && !props.rows.some((row) => row.id === model.value)
    ? [{ id: model.value }, ...props.rows]
    : props.rows,
)
</script>

<template>
  <el-select
    v-if="rows.length || disabled"
    v-model="model"
    filterable
    :clearable="optional"
    :disabled="disabled"
    :aria-label="label"
    :placeholder="optional ? '请选择（可留空）' : '请选择'"
  >
    <el-option v-for="row in options" :key="row.id" :label="catalogLabel(row)" :value="row.id" />
  </el-select>
  <el-input v-else v-model="model" :aria-label="label" placeholder="输入授权资料编号" />
</template>
