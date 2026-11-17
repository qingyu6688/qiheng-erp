<script setup lang="ts">
import { computed } from 'vue'
import type { AiToolResult } from '../types/ai'

const props = defineProps<{ runs: AiToolResult[] }>()
const columnLabels: Record<string, string> = { id: '编号', code: '编码', name: '名称' }
const results = computed(() =>
  props.runs.map((run) => ({
    ...run,
    columns: [...new Set(run.rows.flatMap(Object.keys))],
  })),
)

function displayValue(value: unknown): string {
  return value === null || value === undefined
    ? '—'
    : typeof value === 'object'
      ? JSON.stringify(value)
      : String(value)
}
</script>

<template>
  <section v-for="(result, index) in results" :key="result.code + index" class="ai-tool-result">
    <h4>{{ result.label }}</h4>
    <el-table v-if="result.rows.length" :data="result.rows" size="small" max-height="320">
      <el-table-column
        v-for="column in result.columns"
        :key="column"
        :label="columnLabels[column] ?? column"
        min-width="100"
      >
        <template #default="{ row }">{{ displayValue(row[column]) }}</template>
      </el-table-column>
    </el-table>
    <p v-else>查询无结果。</p>
    <p v-if="result.hasMore">仅显示本次查询返回的部分结果。</p>
  </section>
</template>

<style scoped>
.ai-tool-result {
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 10px;
  margin-top: 8px;
}
.ai-tool-result h4 {
  margin: 0 0 8px;
}
</style>
