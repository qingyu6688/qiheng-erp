<script setup lang="ts">
import { onMounted, onScopeDispose, ref } from 'vue'
import { useRoute } from 'vue-router'
import { aiRead } from '../../api/ai'
import { isCancelled, messageOf } from '../../api/http'
import AiMarkdown from '../../components/AiMarkdown.vue'
import AiSources from '../../components/AiSources.vue'
import AiToolResults from '../../components/AiToolResults.vue'
import type { AiDocument, AiEvidence, AiSegment } from '../../types/ai'
import { formatDateTime } from '../../utils/format'
import type { DemoAiAnswer } from '../ai-data'
import '../../styles/ai-workspace.css'

const route = useRoute()
const kind = route.path.split('/')[2]
const id = String(route.params.id ?? '')
const document = ref<AiDocument>()
const segments = ref<AiSegment[]>([])
const evidence = ref<AiEvidence>()
const answer = ref<DemoAiAnswer>()
const loading = ref(true)
const error = ref('')
let active = true

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    if (kind === 'knowledge') {
      const [detail, content] = await Promise.all([
        aiRead<AiDocument>('/admin/documents/' + id),
        aiRead<AiSegment[]>('/admin/documents/' + id + '/segments'),
      ])
      if (active) { document.value = detail; segments.value = content }
    } else if (kind === 'sources') {
      const detail = await aiRead<AiEvidence>('/citations/' + id)
      if (active) evidence.value = detail
    } else {
      const detail = await aiRead<DemoAiAnswer>('/demo/answers/' + id)
      if (active) answer.value = detail
    }
  } catch (cause: unknown) {
    if (active && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (active) loading.value = false
  }
}
onMounted(load)
onScopeDispose(() => { active = false })
</script>

<template>
  <div class="ai-page">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-else-if="loading" role="status">正在读取详情…</p>
    <template v-else-if="document">
      <div class="page-heading"><div><h1>{{ document.title }}</h1><p>第 {{ document.versionNo }} 版 · {{ segments.length }} 个片段 · 更新于 {{ formatDateTime(document.updatedAt) }}</p></div></div>
      <section v-for="segment in segments" :key="segment.id" class="panel ai-section">
        <h2>第 {{ segment.seq }} 段 · {{ segment.heading }}</h2>
        <pre class="source-text">{{ segment.content }}</pre>
        <AiSources :sources="[{ segmentId: segment.id, label: '查看引用原文' }]" />
      </section>
    </template>
    <template v-else-if="evidence">
      <div class="page-heading"><div><h1>{{ evidence.title }}</h1><p>{{ evidence.heading }}</p></div></div>
      <section class="panel ai-section">
        <pre class="source-text">{{ evidence.content }}</pre>
        <router-link v-if="evidence.wikiSlug" class="text-link" :to="{ name: 'ai-wiki-article', params: { slug: evidence.wikiSlug } }">阅读相关百科</router-link>
      </section>
    </template>
    <template v-else-if="answer">
      <el-alert title="预设回答样例 · 内容来自虚构数据与示例资料，不是本次实时生成。" type="info" :closable="false" class="ai-error" />
      <section class="panel ai-section question"><h2>示例问题</h2><p>{{ answer.question }}</p></section>
      <section class="panel ai-section">
        <h2>AI 回答样例</h2>
        <p class="sample-time">数据截至 {{ formatDateTime(answer.asOf) }}</p>
        <AiMarkdown :content="answer.content" />
        <AiToolResults v-if="answer.tools.length" :runs="answer.tools" />
        <div class="sample-sources"><AiSources :sources="answer.sourceIds.map((segmentId, index) => ({ segmentId, label: `查看依据 [${index + 1}]` }))" /></div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.question { background: var(--erp-primary-1); }
.question p { color: var(--erp-text-1); }
.source-text { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; color: var(--erp-text-1); font-family: inherit; }
.sample-time { font-size: 12px; }
.sample-sources { border-top: 1px solid var(--erp-border-1); margin-top: 20px; padding-top: 12px; }
</style>
