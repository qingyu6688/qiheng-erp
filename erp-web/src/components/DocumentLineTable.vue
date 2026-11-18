<script setup lang="ts" generic="Row extends object">
import type { TableInstance } from 'element-plus'
import { useTemplateRef } from 'vue'
import { documentHeaderCellStyle } from '../utils/document-table-style'

defineProps<{ rows: Row[] }>()

const table = useTemplateRef<Pick<TableInstance, 'layout'>>('table')
</script>

<template>
  <el-table
    ref="table"
    class="document-line-table"
    table-layout="auto"
    :header-cell-style="documentHeaderCellStyle"
    :data="rows"
    row-key="id"
    :scrollbar-tabindex="table?.layout?.scrollX.value ? 0 : undefined"
  >
    <slot />
  </el-table>
</template>

<style scoped>
.document-line-table :deep(.el-scrollbar__wrap:focus-visible) {
  outline: 2px solid var(--erp-primary-6);
  outline-offset: -2px;
}
</style>
