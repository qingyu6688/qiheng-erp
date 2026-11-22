<script setup lang="ts">
import { computed, inject, onMounted, onScopeDispose, ref } from 'vue'
import { useRoute } from 'vue-router'
import { aiRead } from '../../api/ai'
import { isCancelled, messageOf } from '../../api/http'
import AiMarkdown from '../../components/AiMarkdown.vue'
import AiSources from '../../components/AiSources.vue'
import type { AiWikiPage } from '../../types/ai'
import '../../styles/ai-workspace.css'

const route = useRoute()
const readOnlyPreview = inject('erp.readOnlyPreview', false)
const slug = computed(() => (typeof route.params.slug === 'string' ? route.params.slug : ''))
const query = ref('')
const loading = ref(false)
const error = ref('')
const pages = ref<AiWikiPage[]>([])
const page = ref<AiWikiPage>()
let revision = 0

async function load(): Promise<void> {
  const current = ++revision
  error.value = ''
  loading.value = true
  pages.value = []
  page.value = undefined
  try {
    if (slug.value) {
      const detail = await aiRead<AiWikiPage>(`/wiki/${encodeURIComponent(slug.value)}`)
      if (current === revision) page.value = detail
    } else {
      const items = await aiRead<AiWikiPage[]>('/wiki', { q: query.value.trim() || undefined })
      if (current === revision) pages.value = items
    }
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
}

onMounted(load)
onScopeDispose(() => {
  revision++
})
</script>

<template>
  <div class="ai-page">
    <div class="page-heading">
      <div>
        <h1>{{ page?.title || '知识百科' }}</h1>
        <p v-if="page?.summary">{{ page.summary }}</p>
      </div>
      <router-link v-if="slug && !readOnlyPreview" class="text-link" :to="{ name: 'ai-wiki' }"
        >返回知识百科</router-link
      >
    </div>
    <el-alert v-if="readOnlyPreview && !slug" title="示例知识条目，仅供阅读；查看正文和引用原文会在右侧打开。" type="info" :closable="false" class="ai-error" />
    <section class="panel ai-section" :aria-busy="loading">
      <form v-if="!slug" class="ai-search" @submit.prevent="load">
        <el-input
          v-model="query"
          aria-label="搜索 Wiki"
          placeholder="搜索标题或关键词"
          clearable
          maxlength="200"
        />
        <el-button native-type="submit" type="primary" :loading="loading">搜索</el-button>
      </form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <p v-else-if="loading" role="status">正在读取知识百科…</p>
      <template v-else-if="page">
        <article><AiMarkdown :content="page.content" wiki-links /></article>
        <AiSources
          class="wiki-sources"
          :sources="
            page.sourceIds.map((segmentId, index) => ({
              segmentId,
              label: `查看来源 [${index + 1}]`,
            }))
          "
        />
      </template>
      <ul v-else-if="pages.length" class="ai-list">
        <li v-for="item in pages" :key="item.id">
          <router-link
            class="text-link"
            :to="{ name: 'ai-wiki-article', params: { slug: item.slug } }"
            >{{ item.title }}</router-link
          >
          <p class="ai-wrap">{{ item.summary }}</p>
        </li>
      </ul>
      <p v-else class="ai-empty">
        {{ query.trim() ? '没有找到相关页面。' : '暂无可阅读的页面。' }}
      </p>
    </section>
  </div>
</template>

<style scoped>
.wiki-sources {
  margin-top: 20px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}
</style>
