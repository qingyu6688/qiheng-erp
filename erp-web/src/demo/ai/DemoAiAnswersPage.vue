<script setup lang="ts">
import { onMounted, onScopeDispose, ref } from 'vue'
import { aiRead } from '../../api/ai'
import { isCancelled, messageOf } from '../../api/http'
import type { DemoAiAnswer } from '../ai-data'
import '../../styles/ai-workspace.css'

const query = ref('')
const category = ref('')
const rows = ref<DemoAiAnswer[]>([])
const loading = ref(false)
const error = ref('')
let revision = 0

async function load(): Promise<void> {
  const current = ++revision
  loading.value = true
  error.value = ''
  try {
    const result = await aiRead<DemoAiAnswer[]>('/demo/answers', { q: query.value.trim(), category: category.value })
    if (current === revision) rows.value = result
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
}
onMounted(load)
onScopeDispose(() => { revision++ })
</script>

<template>
  <div class="ai-page">
    <div class="page-heading"><div><h1>AI 回答样例</h1><p>查看业务查数、知识问答和权限边界的预设示例。</p></div></div>
    <el-alert title="以下为预设回答，使用虚构业务数据，不进行实时问答或调用模型。" type="info" :closable="false" class="ai-error" />
    <section class="panel ai-section" :aria-busy="loading">
      <form class="ai-search" @submit.prevent="load">
        <el-input v-model="query" aria-label="搜索回答样例" placeholder="搜索问题或关键词" clearable maxlength="200" />
        <el-select v-model="category" aria-label="回答样例类型" placeholder="全部类型" clearable style="min-width: 140px" @change="load">
          <el-option v-for="item in ['业务查数', '库存解释', '知识问答', '权限边界']" :key="item" :label="item" :value="item" />
        </el-select>
        <el-button native-type="submit" type="primary" :loading="loading">搜索</el-button>
      </form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <p v-else-if="loading" role="status">正在读取回答样例…</p>
      <ul v-else-if="rows.length" class="ai-list">
        <li v-for="row in rows" :key="row.id">
          <el-tag size="small" type="info">{{ row.category }}</el-tag>
          <router-link class="text-link answer-question" :to="'/ai/answers/' + row.id">{{ row.question }}</router-link>
          <p>{{ row.summary }}</p>
        </li>
      </ul>
      <p v-else class="ai-empty">没有找到相关回答样例，请调整关键词或类型。</p>
    </section>
  </div>
</template>

<style scoped>
.answer-question { margin-left: 10px; font-weight: 600; }
@media (max-width: 640px) { .ai-search { flex-wrap: wrap; } .answer-question { display: block; margin: 8px 0 0; } }
</style>
