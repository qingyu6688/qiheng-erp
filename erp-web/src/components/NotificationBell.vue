<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue'
import { Bell } from '@element-plus/icons-vue'
import {
  unreadCounts,
  importantNotices,
  receivedNotice,
  confirmAnnouncement,
} from '../api/platform'
import type { ReceivedNotice } from '../api/platform'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'
import { useRealtime } from '../hooks/useRealtime'
const session = useSessionStore(),
  count = ref(0),
  notice = ref<ReceivedNotice>(),
  error = ref(''),
  busy = ref(false)
let revision = 0,
  debounce: ReturnType<typeof setTimeout> | undefined
async function refresh(): Promise<void> {
  const current = ++revision
  if (!session.companyId || session.user?.mustChangePassword) {
    count.value = 0
    notice.value = undefined
    return
  }
  try {
    const [unread, important] = await Promise.all([unreadCounts(), importantNotices()])
    if (current !== revision) return
    count.value = unread.total ?? 0
    if (important[0]) {
      const selected = await receivedNotice(important[0].id)
      if (current === revision) notice.value = selected
    } else notice.value = undefined
    error.value = ''
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  }
}
function changed(): void {
  clearTimeout(debounce)
  debounce = setTimeout(() => {
    void refresh()
    window.dispatchEvent(new Event('erp:messages-changed'))
  }, 250)
}
useRealtime(changed)
watch(
  () => session.companyId,
  () => {
    count.value = 0
    notice.value = undefined
    void refresh()
  },
  { immediate: true },
)
async function confirm(): Promise<void> {
  if (!notice.value || busy.value) return
  busy.value = true
  try {
    await confirmAnnouncement(notice.value.id)
    window.dispatchEvent(new Event('erp:messages-changed'))
    await refresh()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
onScopeDispose(() => {
  ++revision
  clearTimeout(debounce)
})
</script>
<template>
  <router-link
    class="shell-button workbench-bell"
    to="/messages"
    :aria-label="'消息中心，' + count + '条未读'"
    :title="error || '消息中心'"
    ><Bell class="icon" aria-hidden="true" /><span v-if="count" class="nav-count">{{
      count > 99 ? '99+' : count
    }}</span></router-link
  >
  <el-dialog
    :model-value="Boolean(notice)"
    :title="notice?.title || '重要公告'"
    :show-close="false"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    width="min(680px, 92vw)"
  >
    <article class="notice-content" v-html="notice?.content" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template #footer
      ><el-button type="primary" :loading="busy" @click="confirm"
        >我已阅读并确认</el-button
      ></template
    >
  </el-dialog>
</template>
