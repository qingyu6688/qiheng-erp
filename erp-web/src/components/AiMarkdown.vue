<script setup lang="ts">
import { computed, inject } from 'vue'
import { renderAiMarkdown } from '../utils/ai-markdown'

const props = defineProps<{ content: string; wikiLinks?: boolean }>()
const rendered = computed(() => renderAiMarkdown(props.content, props.wikiLinks))
const previewWiki = inject<((path: string) => void) | undefined>('erp.previewWiki', undefined)
function followWiki(event: MouseEvent): void {
  if (!previewWiki || !(event.target instanceof Element)) return
  const path = event.target.closest('a')?.getAttribute('href')
  if (path && /^\/ai\/wiki\/[a-z0-9-]{1,96}$/.test(path)) {
    event.preventDefault()
    previewWiki(path)
  }
}
</script>

<template>
  <!-- 仅知识百科允许生成站内条目链接，生成的 HTML 仍需清洗。 -->
  <!-- eslint-disable-next-line vue/no-v-html -->
  <div class="ai-markdown" @click="followWiki" v-html="rendered" />
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
  border-radius: var(--erp-radius-sm);
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
