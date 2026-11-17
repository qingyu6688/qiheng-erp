<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue'
import { aiRead } from '../api/ai'
import { isCancelled, messageOf } from '../api/http'
import type { AiEvidence } from '../types/ai'

const props = defineProps<{ sources: { segmentId: string; label: string }[] }>()
const open = ref(false)
const loading = ref(false)
const error = ref('')
const evidence = ref<AiEvidence>()
let requestId = 0

function clear(): void {
  requestId++
  open.value = false
  evidence.value = undefined
  error.value = ''
  loading.value = false
}

async function preview(segmentId: string): Promise<void> {
  clear()
  const current = requestId
  open.value = true
  loading.value = true
  try {
    const result = await aiRead<AiEvidence>(`/citations/${encodeURIComponent(segmentId)}`)
    if (requestId === current) evidence.value = result
  } catch (cause: unknown) {
    if (requestId === current && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (requestId === current) loading.value = false
  }
}

watch(() => JSON.stringify(props.sources.map((item) => item.segmentId)), clear)
onScopeDispose(clear)
</script>

<template>
  <div v-if="sources.length" class="ai-sources">
    <span class="source-label">引用来源</span>
    <ul>
      <li v-for="source in sources" :key="source.segmentId + source.label">
        <el-button link type="primary" @click="preview(source.segmentId)">{{
          source.label
        }}</el-button>
      </li>
    </ul>
    <el-dialog
      v-model="open"
      title="引用原文"
      append-to-body
      width="min(640px, 94vw)"
      @close="clear"
    >
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <p v-else-if="loading" role="status">正在读取原文…</p>
      <template v-else-if="evidence">
        <h3>{{ evidence.title }}</h3>
        <p v-if="evidence.heading || evidence.page">
          {{ evidence.heading }} {{ evidence.page ? `· 第 ${evidence.page} 页` : '' }}
        </p>
        <pre class="source-content">{{ evidence.content }}</pre>
        <router-link
          v-if="evidence.wikiSlug"
          class="text-link"
          :to="{ name: 'ai-wiki-article', params: { slug: evidence.wikiSlug } }"
          @click="clear"
          >打开 Wiki 页面</router-link
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.source-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ai-sources ul {
  list-style: none;
  padding: 0;
  margin: 2px 0 0;
}
.ai-sources li :deep(button > span) {
  white-space: normal;
  overflow-wrap: anywhere;
  text-align: left;
}
.source-content {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.7;
  max-height: 55vh;
  overflow: auto;
}
</style>
