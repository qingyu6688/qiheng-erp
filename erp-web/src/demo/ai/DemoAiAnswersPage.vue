<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref } from 'vue'
import { ChatDotRound } from '@element-plus/icons-vue'
import { aiRead } from '../../api/ai'
import { isCancelled, messageOf } from '../../api/http'
import type { DemoAiAnswer } from '../ai-data'
import DemoAiConversation from './DemoAiConversation.vue'
import '../../styles/ai-workspace.css'

const query = ref('')
const category = ref('')
const rows = ref<DemoAiAnswer[]>([])
const loading = ref(false)
const error = ref('')
const selectedId = ref('')
const selected = computed(() => rows.value.find((row) => row.id === selectedId.value))
let revision = 0

async function load(): Promise<void> {
  const current = ++revision
  loading.value = true
  error.value = ''
  try {
    const result = await aiRead<DemoAiAnswer[]>('/demo/answers', { q: query.value.trim(), category: category.value })
    if (current === revision) {
      rows.value = result
      if (!result.some((row) => row.id === selectedId.value)) selectedId.value = result[0]?.id ?? ''
    }
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
    <div class="page-heading"><div><h1>AI 对话样例</h1><p>选择场景，查看提问、回答与引用来源。以下均为预设内容，不调用模型。</p></div></div>
    <div class="conversation-workspace">
    <aside class="panel conversation-scenarios" aria-label="对话场景" :aria-busy="loading">
      <div class="scenario-heading"><ChatDotRound /><h2>示例会话</h2><span>{{ rows.length }}</span></div>
      <form class="scenario-search" @submit.prevent="load">
        <el-input v-model="query" aria-label="搜索回答样例" placeholder="搜索问题或关键词" clearable maxlength="200" />
        <el-select v-model="category" aria-label="回答样例类型" placeholder="全部类型" clearable @change="load">
          <el-option v-for="item in ['业务查数', '库存解释', '知识问答', '权限边界']" :key="item" :label="item" :value="item" />
        </el-select>
        <el-button native-type="submit" :loading="loading">搜索</el-button>
      </form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <p v-else-if="loading" role="status">正在读取回答样例…</p>
      <ul v-else-if="rows.length" class="scenario-list">
        <li v-for="row in rows" :key="row.id">
          <button class="scenario-button" :aria-pressed="selectedId === row.id" @click="selectedId = row.id">
            <span class="scenario-category">{{ row.category }}</span>
            <strong>{{ row.question }}</strong>
            <span class="scenario-summary">{{ row.summary }}</span>
          </button>
        </li>
      </ul>
      <p v-else class="ai-empty">没有找到相关回答样例，请调整关键词或类型。</p>
    </aside>
    <DemoAiConversation v-if="selected" :answer="selected" />
    <section v-else class="panel conversation-empty"><ChatDotRound /><p>{{ loading ? '正在读取对话样例…' : '选择一个场景查看预设对话。' }}</p></section>
    </div>
  </div>
</template>

<style scoped>
.conversation-workspace { display: grid; grid-template-columns: 292px minmax(0, 1fr); gap: 18px; align-items: stretch; min-height: 620px; }
.conversation-scenarios { padding: 16px 12px; min-width: 0; align-self: start; }
.scenario-heading { display: flex; gap: 8px; align-items: center; padding: 2px 8px 14px; }
.scenario-heading svg { width: 18px; height: 18px; color: var(--erp-text-3); }
.scenario-heading h2 { flex: 1; font-size: 14px; margin: 0; }
.scenario-heading > span { color: var(--erp-text-3); font-size: 12px; }
.scenario-search { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; padding: 0 4px 16px; }
.scenario-search > :first-child { grid-column: 1 / -1; }
.scenario-search > .el-select { min-width: 0; }
.scenario-list { list-style: none; padding: 0; margin: 0; }
.scenario-list li + li { margin-top: 6px; }
.scenario-button { display: flex; flex-direction: column; width: 100%; gap: 7px; padding: 12px; text-align: left; border: 1px solid transparent; border-radius: var(--erp-radius); background: transparent; cursor: pointer; color: var(--erp-text-1); line-height: 1.6; }
.scenario-button:hover { background: var(--erp-fill-1); }
.scenario-button[aria-pressed='true'] { background: var(--erp-primary-1); border-color: var(--erp-primary-2); }
.scenario-category { color: var(--erp-primary-7); font-size: 11px; }
.scenario-button strong { font-size: 13px; font-weight: 500; }
.scenario-summary { color: var(--erp-text-3); font-size: 12px; }
.conversation-empty { display: flex; align-items: center; justify-content: center; flex-direction: column; color: var(--erp-text-3); padding: 24px; }
.conversation-empty > svg { width: 32px; height: 32px; }
@media (max-width: 1100px) { .conversation-workspace { grid-template-columns: 240px minmax(0, 1fr); gap: 12px; } }
@media (max-width: 800px) { .conversation-workspace { grid-template-columns: minmax(0, 1fr); } .scenario-list { display: flex; gap: 8px; overflow-x: auto; padding-bottom: 4px; } .scenario-list li { min-width: 220px; flex: 1; } .scenario-list li + li { margin-top: 0; } .scenario-summary { display: none; } .scenario-search { grid-template-columns: minmax(0, 1fr) minmax(100px, 140px) auto; } .scenario-search > :first-child { grid-column: auto; } }
@media (max-width: 480px) { .scenario-search { grid-template-columns: minmax(0, 1fr) auto; } .scenario-search > :first-child { grid-column: 1 / -1; } }
</style>
