<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { ChatDotRound, VideoPlay, CircleCheck, Lock } from '@element-plus/icons-vue'
import AiMarkdown from '../../components/AiMarkdown.vue'
import AiSources from '../../components/AiSources.vue'
import AiToolResults from '../../components/AiToolResults.vue'
import { formatDateTime } from '../../utils/format'
import type { DemoAiAnswer } from '../ai-data'

const props = defineProps<{ answer: DemoAiAnswer }>()
const REPLAY_INTERVAL_MS = 55
const REPLAY_CHARACTERS_PER_TICK = 8
const visibleCharacters = ref(props.answer.content.length)
const playing = ref(false)
const recordOpen = ref(false)
let timer: number | undefined
const content = computed(() => props.answer.content.slice(0, visibleCharacters.value))
const assistantName = computed(() => props.answer.category === '知识问答' ? '知识问答助手' : '柒衡业务助手')

function finish(): void {
  if (timer !== undefined) window.clearInterval(timer)
  timer = undefined
  playing.value = false
  visibleCharacters.value = props.answer.content.length
}

function replay(): void {
  finish()
  // 减少动态效果时直接展示完整内容，仍可阅读全部引用与来源。
  if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return
  visibleCharacters.value = 0
  playing.value = true
  timer = window.setInterval(() => {
    visibleCharacters.value = Math.min(props.answer.content.length, visibleCharacters.value + REPLAY_CHARACTERS_PER_TICK)
    if (visibleCharacters.value === props.answer.content.length) finish()
  }, REPLAY_INTERVAL_MS)
}

watch(() => props.answer.id, () => { finish(); recordOpen.value = false })
onScopeDispose(() => {
  if (timer !== undefined) window.clearInterval(timer)
})
</script>

<template>
  <section class="demo-conversation" aria-label="预设对话">
    <header class="conversation-header">
      <span class="assistant-avatar" aria-hidden="true"><ChatDotRound /></span>
      <div class="assistant-heading"><h2>{{ assistantName }}</h2><span>预设对话 · {{ answer.category }}</span></div>
      <button v-if="playing" class="replay-button" @click="finish"><CircleCheck />显示完整回答</button>
      <button v-else class="replay-button" @click="replay"><VideoPlay />播放对话</button>
    </header>

    <div class="conversation-messages" role="region" aria-label="会话内容">
      <p class="conversation-date">示例数据截至 {{ formatDateTime(answer.asOf) }}</p>
      <article class="conversation-message user-message" aria-label="用户提问">
        <span class="user-avatar" aria-hidden="true">我</span>
        <div class="message-body"><span class="message-author">演示访客</span><div class="user-bubble">{{ answer.question }}</div></div>
      </article>
      <article class="conversation-message assistant-message" aria-label="助手回答" :aria-busy="playing">
        <span class="assistant-avatar" aria-hidden="true"><ChatDotRound /></span>
        <div class="message-body">
          <span class="message-author">{{ assistantName }}<span class="sample-badge">样例</span></span>
          <div class="assistant-bubble">
            <p v-if="playing" role="status" class="playback-status">正在播放预设回答…</p>
            <AiMarkdown :content="content" />
            <template v-if="!playing">
              <details v-if="answer.tools.length" :open="recordOpen" class="conversation-records" @toggle="recordOpen = ($event.target as HTMLDetailsElement).open">
                <summary>查看查询记录<span>{{ answer.tools[0]?.rows.length }} 条示例记录</span></summary>
                <AiToolResults :runs="answer.tools" />
              </details>
              <div class="conversation-sources"><AiSources :sources="answer.sourceIds.map((segmentId, index) => ({ segmentId, label: `查看依据 [${index + 1}]` }))" /></div>
            </template>
          </div>
        </div>
      </article>
    </div>
    <footer class="conversation-footer"><Lock aria-hidden="true" /><span>只读预设对话，可切换场景或播放展示。引用与关联单据在右侧打开。</span></footer>
  </section>
</template>

<style scoped>
.demo-conversation { min-width: 0; display: flex; flex-direction: column; border: 1px solid var(--erp-border-1); border-radius: var(--erp-radius-card); background: var(--erp-bg); overflow: hidden; }
.conversation-header { display: flex; align-items: center; gap: 12px; padding: 18px 22px; border-bottom: 1px solid var(--erp-border-1); }
.assistant-heading { min-width: 0; flex: 1; }
.assistant-heading h2 { margin: 0; font-size: 15px; line-height: 24px; }
.assistant-heading > span { color: var(--erp-text-3); font-size: 12px; }
.assistant-avatar, .user-avatar { flex-shrink: 0; display: grid; place-items: center; width: 32px; height: 32px; border-radius: var(--erp-radius); }
.assistant-avatar { color: var(--erp-primary-7); background: var(--erp-primary-1); }
.assistant-avatar svg { width: 19px; height: 19px; }
.user-avatar { color: var(--erp-text-2); background: var(--erp-fill-2); font-size: 13px; }
.replay-button { display: inline-flex; align-items: center; gap: 6px; border: 1px solid var(--erp-border-1); padding: 7px 10px; border-radius: var(--erp-radius); background: var(--erp-bg); color: var(--erp-text-2); font-size: 12px; cursor: pointer; }
.replay-button:hover { color: var(--erp-primary-7); border-color: var(--erp-primary-2); }
.replay-button svg { width: 15px; height: 15px; }
.conversation-messages { padding: 16px 24px 32px; flex: 1; min-width: 0; }
.conversation-date { text-align: center; color: var(--erp-text-3); font-size: 11px; margin: 0 0 28px; }
.conversation-message { display: flex; align-items: flex-start; gap: 10px; margin-top: 24px; }
.message-body { min-width: 0; max-width: calc(100% - 42px); }
.message-author { display: flex; align-items: center; gap: 6px; color: var(--erp-text-3); font-size: 12px; margin: 0 0 7px; }
.sample-badge { padding: 0 5px; border: 1px solid var(--erp-border-1); border-radius: var(--erp-radius-sm); font-size: 10px; line-height: 16px; }
.user-message { flex-direction: row-reverse; margin-bottom: 30px; }
.user-message .message-author { justify-content: flex-end; }
.user-bubble { padding: 12px 16px; border-radius: var(--erp-radius-card) 0 var(--erp-radius-card) var(--erp-radius-card); background: var(--erp-primary-1); color: var(--erp-text-1); overflow-wrap: anywhere; }
.assistant-message .message-body { flex: 1; }
.assistant-bubble { border: 1px solid var(--erp-border-1); padding: 18px; border-radius: 0 var(--erp-radius-card) var(--erp-radius-card) var(--erp-radius-card); background: var(--erp-bg); }
.playback-status { color: var(--erp-primary-7); font-size: 12px; margin: 0 0 12px; }
.conversation-records { margin-top: 18px; border-top: 1px solid var(--erp-border-1); padding-top: 12px; }
.conversation-records summary { color: var(--erp-text-2); font-size: 12px; cursor: pointer; }
.conversation-records summary > span { margin-left: 10px; color: var(--erp-text-3); }
.conversation-sources { padding-top: 12px; margin-top: 18px; border-top: 1px solid var(--erp-border-1); }
.conversation-footer { display: flex; align-items: flex-start; gap: 8px; padding: 14px 22px; border-top: 1px solid var(--erp-border-1); background: var(--erp-fill-1); color: var(--erp-text-3); font-size: 12px; }
.conversation-footer svg { width: 15px; height: 15px; flex-shrink: 0; margin-top: 3px; }
@media (max-width: 640px) { .conversation-header { padding: 14px 12px; gap: 8px; flex-wrap: wrap; } .conversation-messages { padding: 14px 12px 24px; } .assistant-bubble { padding: 12px; } .conversation-footer { padding: 12px; } .message-body { max-width: calc(100% - 34px); } .conversation-message { gap: 6px; } .assistant-avatar, .user-avatar { width: 28px; height: 28px; } .replay-button { margin-left: auto; } }
</style>
