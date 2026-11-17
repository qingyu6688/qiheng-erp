<script setup lang="ts">
import { computed } from 'vue'
import { renderAiMarkdown } from '../utils/ai-markdown'

const props = defineProps<{ content: string; wikiLinks?: boolean }>()
const rendered = computed(() => renderAiMarkdown(props.content, props.wikiLinks))
</script>

<template>
  <!-- 仅知识百科允许生成站内条目链接，生成的 HTML 仍需清洗。 -->
  <!-- eslint-disable-next-line vue/no-v-html -->
  <div class="ai-markdown" v-html="rendered" />
</template>

<style scoped>
.ai-markdown {
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.ai-markdown > :deep(:first-child) {
  margin-top: 0;
}
.ai-markdown > :deep(:last-child) {
  margin-bottom: 0;
}
.ai-markdown :deep(pre),
.ai-markdown :deep(code) {
  background-color: var(--el-fill-color-light);
  border-radius: 4px;
  padding: 2px 4px;
}
.ai-markdown :deep(pre) {
  overflow: auto;
  padding: 10px;
}
.ai-markdown :deep(table) {
  border-collapse: collapse;
  width: 100%;
}
.ai-markdown :deep(th),
.ai-markdown :deep(td) {
  padding: 6px 10px;
  border: 1px solid var(--el-border-color);
}
.ai-markdown :deep(th) {
  text-align: left;
  background-color: var(--el-fill-color-light);
}
.ai-markdown :deep(ul),
.ai-markdown :deep(ol) {
  padding-inline-start: 24px;
}
.ai-markdown :deep(a) {
  color: var(--el-color-primary);
  text-decoration: underline;
}
</style>
