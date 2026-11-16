<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue'
import {
  inbox,
  readMessage,
  receivedNotice,
  subscriptions,
  saveSubscription,
} from '../api/platform'
import type { MessageRow, ReceivedNotice, Subscription } from '../api/platform'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'
import { formatDateTime } from '../utils/format'
const session = useSessionStore(),
  rows = ref<MessageRow[]>([]),
  category = ref(''),
  unread = ref(true),
  busy = ref(false),
  error = ref(''),
  next = ref<string | null>(null),
  cursors = ref<(string | null)[]>([null]),
  notice = ref<ReceivedNotice>(),
  selected = ref<MessageRow>(),
  preferences = ref<Subscription[]>([]),
  preferencesOpen = ref(false)
const labels: Record<string, string> = {
  todo: '业务待办',
  notice: '通知公告',
  alert: '预警提醒',
  system: '系统消息',
  email: '邮件',
  sms: '短信',
}
let revision = 0
async function load(): Promise<void> {
  const current = ++revision
  busy.value = true
  error.value = ''
  try {
    const page = await inbox({
      category: category.value,
      unread: unread.value || undefined,
      cursor: cursors.value.at(-1),
      limit: 20,
    })
    if (current === revision) {
      rows.value = page.items
      next.value = page.nextCursor
    }
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) busy.value = false
  }
}
function search(): void {
  cursors.value = [null]
  void load()
}
function paginate(forward: boolean): void {
  if (forward && next.value) cursors.value.push(next.value)
  else if (!forward && cursors.value.length > 1) cursors.value.pop()
  void load()
}
async function open(row: MessageRow): Promise<void> {
  error.value = ''
  selected.value = row
  try {
    const id = row.link?.match(/^\/messages\?notice=([1-9][0-9]*)$/)?.[1]
    if (id) notice.value = await receivedNotice(id)
    else {
      await readMessage(row.id)
      row.readAt = new Date().toISOString()
      window.dispatchEvent(new Event('erp:messages-changed'))
    }
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  }
}
async function confirmNotice(): Promise<void> {
  if (!selected.value) return
  try {
    await readMessage(selected.value.id)
    notice.value = undefined
    window.dispatchEvent(new Event('erp:messages-changed'))
    await load()
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  }
}
async function settings(): Promise<void> {
  error.value = ''
  try {
    const values = await subscriptions()
    preferences.value = ['todo', 'notice', 'alert', 'system'].flatMap((type) =>
      ['email', 'sms'].map(
        (channel) =>
          values.find((value) => value.category === type && value.channel === channel) ?? {
            category: type,
            channel,
            enabled: false,
            version: 0,
          },
      ),
    )
    preferencesOpen.value = true
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  }
}
async function toggle(item: Subscription, enabled: boolean): Promise<void> {
  busy.value = true
  try {
    const value = await saveSubscription({ ...item, enabled })
    Object.assign(item, value)
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
function refresh(): void {
  void load()
}
window.addEventListener('erp:messages-changed', refresh)
watch(
  () => session.companyId,
  () => {
    notice.value = undefined
    preferencesOpen.value = false
    search()
  },
  { immediate: true },
)
onScopeDispose(() => {
  ++revision
  window.removeEventListener('erp:messages-changed', refresh)
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>消息中心</h1>
      <p>待办按当前公司、仓库和部门范围显示。</p>
    </div>
    <el-button @click="settings">消息订阅</el-button>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <section class="panel table-panel" :aria-busy="busy">
    <form class="filters" @submit.prevent="search">
      <el-select v-model="category" class="message-category" aria-label="消息类型" clearable
        ><el-option
          v-for="type in ['todo', 'notice', 'alert', 'system']"
          :key="type"
          :value="type"
          :label="labels[type]" /></el-select
      ><el-checkbox v-model="unread">仅未读</el-checkbox
      ><el-button native-type="submit" :loading="busy">查询</el-button>
    </form>
    <el-table :data="rows" row-key="id"
      ><el-table-column label="类型" width="110"
        ><template #default="{ row }">{{ labels[row.category] }}</template></el-table-column
      ><el-table-column label="消息" min-width="220"
        ><template #default="{ row }"
          ><button class="text-link" @click="open(row)">{{ row.title }}</button>
          <p class="muted">{{ row.content }}</p></template
        ></el-table-column
      ><el-table-column label="时间" min-width="170"
        ><template #default="{ row }">{{
          formatDateTime(row.createdAt)
        }}</template></el-table-column
      ><el-table-column label="操作" width="150"
        ><template #default="{ row }"
          ><div class="row-actions">
            <router-link
              v-if="row.link && !row.link.startsWith('/messages')"
              class="text-link"
              :to="row.link"
              @click="open(row)"
              >查看业务</router-link
            ><button v-if="!row.readAt" class="text-link" @click="open(row)">标记已读</button
            ><span v-else class="muted">已读</span>
          </div></template
        ></el-table-column
      ></el-table
    >
    <div class="pagination">
      <el-button :disabled="busy || cursors.length === 1" @click="paginate(false)">上一页</el-button
      ><el-button :disabled="busy || !next" @click="paginate(true)">下一页</el-button>
    </div>
  </section>
  <el-dialog
    :model-value="Boolean(notice)"
    :title="notice?.title"
    width="min(680px, 92vw)"
    @close="notice = undefined"
    ><article class="notice-content" v-html="notice?.content" />
    <template #footer
      ><el-button type="primary" @click="confirmNotice">阅读并确认</el-button></template
    ></el-dialog
  >
  <el-drawer v-model="preferencesOpen" title="消息订阅" size="min(560px, 94vw)"
    ><p>站内消息始终保留。外部渠道需要管理员启用对应模板和渠道，并绑定已验证的手机号或邮箱。</p>
    <el-table :data="preferences"
      ><el-table-column label="类型"
        ><template #default="{ row }">{{ labels[row.category] }}</template></el-table-column
      ><el-table-column label="渠道"
        ><template #default="{ row }">{{ labels[row.channel] }}</template></el-table-column
      ><el-table-column label="订阅"
        ><template #default="{ row }"
          ><el-switch
            :model-value="Boolean(row.enabled)"
            :disabled="busy"
            @update:model-value="
              toggle(row, Boolean($event))
            " /></template></el-table-column></el-table
  ></el-drawer>
</template>
