<script setup lang="ts">
import { computed, nextTick, onScopeDispose, ref, watch } from 'vue'
import { aiRead, aiWrite, sendAiMessage } from '../api/ai'
import { isCancelled, messageOf } from '../api/http'
import type { AiAssistantOption, AiChatEvent, AiConversation, AiMessage } from '../types/ai'
import { aiBusinessTools } from '../types/ai'
import AiMarkdown from './AiMarkdown.vue'
import AiSources from './AiSources.vue'
import AiToolResults from './AiToolResults.vue'

const open = defineModel<boolean>({ required: true })
const options = ref<AiAssistantOption[]>([])
const assistant = ref('')
const conversations = ref<AiConversation[]>([])
const conversation = ref('')
const messages = ref<AiMessage[]>([])
const prompt = ref('')
const mode = ref<'knowledge' | 'query'>('knowledge')
const promptList = ref<HTMLElement>()
const loading = ref(false)
const error = ref('')
const tool = ref('')
const controller = ref<AbortController>()
const feedback = ref<Record<string, 'up' | 'down'>>({})
const feedbackId = ref('')
const feedbackReason = ref('')
const feedbackCorrection = ref('')
const sendingFeedback = ref(false)
let scope = 0
const selectedAssistant = computed(() =>
  options.value.find(
    (item) =>
      item.code ===
      (conversation.value
        ? conversations.value.find((entry) => entry.id === conversation.value)?.assistantCode
        : assistant.value),
  ),
)
const availableQueries = computed(() =>
  Object.entries(aiBusinessTools)
    .filter(([code]) => selectedAssistant.value?.toolCodes?.includes(code))
    .map(([, item]) => item),
)
const promptInput = ref<{ focus: () => void }>()

watch(
  () => availableQueries.value.length,
  (count) => {
    if (!count) mode.value = 'knowledge'
  },
)

function showError(cause: unknown, current: number): void {
  if (current === scope && !isCancelled(cause)) error.value = messageOf(cause)
}

function resetMessages(): void {
  controller.value?.abort()
  controller.value = undefined
  messages.value = []
  tool.value = ''
  feedbackId.value = ''
  error.value = ''
}

async function load(): Promise<void> {
  const current = ++scope
  resetMessages()
  conversations.value = []
  options.value = []
  assistant.value = ''
  mode.value = 'knowledge'
  conversation.value = ''
  prompt.value = ''
  feedback.value = {}
  loading.value = open.value
  if (!open.value) return
  try {
    const [available, history] = await Promise.all([
      aiRead<AiAssistantOption[]>('/assistants'),
      aiRead<AiConversation[]>('/conversations'),
    ])
    if (scope !== current) return
    options.value = available
    assistant.value = available[0]?.code ?? ''
    conversations.value = history
  } catch (cause: unknown) {
    showError(cause, current)
  } finally {
    if (scope === current) loading.value = false
  }
}

function scrollToLatest(): void {
  void nextTick(() => {
    if (promptList.value) promptList.value.scrollTop = promptList.value.scrollHeight
  })
}

async function selectConversation(id: string): Promise<void> {
  const current = ++scope
  resetMessages()
  conversation.value = id
  prompt.value = ''
  loading.value = Boolean(id)
  if (!id) return
  try {
    const history = await aiRead<AiMessage[]>(`/conversations/${encodeURIComponent(id)}/messages`)
    if (scope === current) {
      messages.value = history
      scrollToLatest()
    }
  } catch (cause: unknown) {
    showError(cause, current)
  } finally {
    if (scope === current) loading.value = false
  }
}

async function removeConversation(): Promise<void> {
  const id = conversation.value
  if (!id || !window.confirm('确定删除此会话及其中的所有消息？')) return
  const current = scope
  loading.value = true
  try {
    await aiWrite(`/conversations/${encodeURIComponent(id)}`, undefined, 'DELETE')
    if (scope !== current) return
    conversations.value = conversations.value.filter((item) => item.id !== id)
    await selectConversation('')
  } catch (cause: unknown) {
    showError(cause, current)
  } finally {
    if (scope === current) loading.value = false
  }
}

function consume(event: AiChatEvent, answer: AiMessage): void {
  switch (event.type) {
    case 'token':
      answer.content += event.data.text
      break
    case 'citation':
      answer.citations.push(event.data)
      break
    case 'tool_call':
      tool.value = event.data.label ?? '正在查询 ERP 数据…'
      break
    case 'tool_result':
      tool.value = ''
      answer.tools.push(event.data)
      break
    case 'done':
      answer.id = event.data.messageId
      break
    case 'error':
      error.value = event.data.message
      break
  }
  scrollToLatest()
}

function editPrompt(text: string): void {
  if (loading.value || controller.value || !availableQueries.value.length) return
  mode.value = 'query'
  prompt.value = text
  void nextTick(() => promptInput.value?.focus())
}
function clarify(text: string, index: number): void {
  const original = messages.value[index - 1]
  if (original?.role === 'user') editPrompt(original.content + '\n请使用候选编码 ' + text + '。')
}

async function send(shortcut?: 'list_warehouses'): Promise<void> {
  const toolCode = shortcut ?? (mode.value === 'query' ? 'auto' : undefined)
  const content = shortcut ? '查看可访问仓库' : prompt.value.trim()
  if (!content || loading.value || controller.value || !selectedAssistant.value) return
  const current = scope
  const stream = new AbortController()
  controller.value = stream
  error.value = ''
  let answer: AiMessage | undefined
  try {
    if (!conversation.value) {
      const item = await aiWrite<AiConversation>('/conversations', {
        assistantCode: assistant.value,
      })
      if (scope !== current || stream.signal.aborted) return
      conversations.value.unshift(item)
      conversation.value = item.id
    }
    const timestamp = new Date().toISOString()
    messages.value.push(
      { id: '', content, role: 'user', citations: [], tools: [], createdAt: timestamp },
      { id: '', content: '', role: 'assistant', citations: [], tools: [], createdAt: timestamp },
    )
    answer = messages.value.at(-1)!
    if (!shortcut) prompt.value = ''
    scrollToLatest()
    await sendAiMessage(
      conversation.value,
      content,
      stream.signal,
      (event) => {
        if (scope === current && answer) consume(event, answer)
      },
      toolCode,
    )
  } catch (cause: unknown) {
    showError(cause, current)
  } finally {
    if (scope === current) {
      if (answer && !answer.id) {
        messages.value.splice(-2)
        if (!shortcut && !prompt.value) prompt.value = content
      }
      controller.value = undefined
      tool.value = ''
    }
  }
}

function stop(): void {
  controller.value?.abort()
}

function startFeedback(id: string): void {
  feedbackReason.value = ''
  feedbackCorrection.value = ''
  feedbackId.value = id
}

async function rate(id: string, rating: 'up' | 'down'): Promise<void> {
  if (!id || sendingFeedback.value) return
  const current = scope
  sendingFeedback.value = true
  try {
    await aiWrite(`/messages/${encodeURIComponent(id)}/feedback`, {
      rating,
      reason: rating === 'down' ? feedbackReason.value : '',
      correction: rating === 'down' ? feedbackCorrection.value : '',
    })
    if (scope === current) {
      feedback.value[id] = rating
      feedbackId.value = ''
    }
  } catch (cause: unknown) {
    showError(cause, current)
  } finally {
    sendingFeedback.value = false
  }
}

watch(open, load, { immediate: true })
onScopeDispose(() => {
  scope++
  stop()
})
</script>

<template>
  <el-drawer v-model="open" title="AI 助手" size="min(640px, 100vw)" append-to-body>
    <div class="assistant-layout">
      <div class="assistant-toolbar">
        <el-select
          :model-value="conversation"
          aria-label="历史会话"
          :disabled="loading || Boolean(controller)"
          @change="selectConversation"
        >
          <el-option label="新会话" value="" />
          <el-option
            v-for="item in conversations"
            :key="item.id"
            :label="item.title || '未命名会话'"
            :value="item.id"
          />
        </el-select>
        <el-button
          v-if="conversation"
          :disabled="loading || Boolean(controller)"
          @click="removeConversation"
        >
          删除会话
        </el-button>
        <el-select
          v-else-if="options.length"
          v-model="assistant"
          aria-label="选择助手"
          :disabled="Boolean(controller)"
        >
          <el-option
            v-for="item in options"
            :key="item.code"
            :label="item.name"
            :value="item.code"
          />
        </el-select>
      </div>

      <el-alert v-if="error" type="error" :title="error" :closable="false" />
      <div ref="promptList" class="assistant-messages" role="region" aria-label="会话内容">
        <p v-if="loading" role="status">正在读取会话…</p>
        <div v-else-if="!messages.length && !error" class="assistant-empty">
          <p v-if="mode === 'query'">输入要查询的对象、时间范围或汇总维度。</p>
          <p v-else-if="options.length">可以询问有关 ERP 操作或业务规则的问题。</p>
          <p v-else>当前账号没有可用的助手，请联系管理员。</p>
        </div>
        <article
          v-for="(item, index) in messages"
          :key="index"
          class="assistant-message"
          :class="item.role"
        >
          <h3>{{ item.role === 'user' ? '我' : item.role === 'tool' ? '查询结果' : 'AI 助手' }}</h3>
          <div v-if="item.role === 'user'" class="assistant-question">{{ item.content }}</div>
          <AiMarkdown v-else-if="item.content" :content="item.content" />
          <AiSources
            v-if="item.citations?.length"
            :sources="
              item.citations.map((citation) => ({
                segmentId: citation.segmentId,
                label: `[${citation.number}] ${citation.title}${citation.heading ? ' · ' + citation.heading : ''}${citation.page ? ' · 第 ' + citation.page + ' 页' : ''}`,
              }))
            "
          />
          <AiToolResults
            v-if="item.tools?.length"
            :runs="item.tools"
            @clarify="clarify($event, index)"
          />
          <p v-if="item.role === 'assistant' && !item.id && !item.content" role="status">
            {{ tool || '正在生成回答…' }}
          </p>
          <div
            v-if="item.role === 'assistant' && item.id && item.content"
            class="assistant-feedback"
          >
            <span v-if="feedback[item.id]" role="status">已记录反馈</span>
            <template v-else>
              <el-button link size="small" :disabled="sendingFeedback" @click="rate(item.id, 'up')"
                >有帮助</el-button
              >
              <el-button
                link
                size="small"
                :disabled="sendingFeedback"
                @click="startFeedback(item.id)"
                >需要改进</el-button
              >
            </template>
            <el-form
              v-if="feedbackId === item.id"
              label-position="top"
              @submit.prevent="rate(item.id, 'down')"
            >
              <el-form-item label="原因"
                ><el-input v-model="feedbackReason" maxlength="500"
              /></el-form-item>
              <el-form-item label="更正建议"
                ><el-input v-model="feedbackCorrection" type="textarea" maxlength="1500"
              /></el-form-item>
              <el-button type="primary" native-type="submit" :loading="sendingFeedback"
                >提交反馈</el-button
              >
              <el-button :disabled="sendingFeedback" @click="feedbackId = ''">取消</el-button>
            </el-form>
          </div>
        </article>
      </div>

      <form class="assistant-composer" @submit.prevent="send()">
        <div v-if="availableQueries.length" class="assistant-modes" aria-label="助手模式">
          <el-radio-group v-model="mode" size="small" :disabled="loading || Boolean(controller)">
            <el-radio-button value="knowledge">知识问答</el-radio-button>
            <el-radio-button value="query">智能查数</el-radio-button>
          </el-radio-group>
        </div>
        <div
          v-if="mode === 'query' && !messages.length"
          class="assistant-examples"
          aria-label="查数示例"
        >
          <el-button
            v-for="item in availableQueries"
            :key="item.label"
            plain
            size="small"
            :disabled="loading || Boolean(controller)"
            @click="editPrompt(item.example)"
          >
            {{ item.label }}
          </el-button>
        </div>
        <el-input
          ref="promptInput"
          v-model="prompt"
          type="textarea"
          aria-label="输入问题"
          :placeholder="mode === 'query' ? '输入业务问题' : '输入问题'"
          :rows="3"
          maxlength="2000"
          :disabled="loading || !selectedAssistant || Boolean(controller)"
          @keydown.ctrl.enter.prevent="send()"
        />
        <div class="assistant-toolbar">
          <el-button v-if="controller" @click="stop">停止</el-button>
          <template v-else>
            <el-button
              v-if="selectedAssistant?.toolCodes?.includes('list_warehouses')"
              :disabled="loading"
              @click="send('list_warehouses')"
              >查看可访问仓库</el-button
            >
            <el-button
              type="primary"
              native-type="submit"
              :disabled="loading || !selectedAssistant || !prompt.trim()"
              >发送</el-button
            >
          </template>
        </div>
      </form>
    </div>
  </el-drawer>
</template>

<style scoped>
.assistant-layout {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
}
.assistant-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
}
.assistant-toolbar > .el-select {
  min-width: 0;
  flex: 1;
}
.assistant-messages {
  flex: 1;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.assistant-message {
  padding: 12px 14px;
  border-radius: 8px;
  margin-bottom: 10px;
  border: 1px solid var(--el-border-color-lighter);
}
.assistant-message.user {
  margin-left: 16px;
  background: var(--el-color-primary-light-9);
}
.assistant-message h3 {
  font-size: 12px;
  margin: 0 0 6px;
  color: var(--el-text-color-secondary);
}
.assistant-question {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.assistant-empty {
  padding: 20px 4px;
  color: var(--el-text-color-secondary);
}
.assistant-composer .assistant-toolbar {
  justify-content: flex-end;
  margin-top: 8px;
}
.assistant-feedback {
  margin-top: 8px;
}
.assistant-modes,
.assistant-examples {
  margin-bottom: 8px;
}
.assistant-examples {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.assistant-examples :deep(.el-button + .el-button) {
  margin-left: 0;
}
</style>
