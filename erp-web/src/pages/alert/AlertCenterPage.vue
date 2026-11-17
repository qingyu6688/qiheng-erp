<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { alertDetail, handleAlert, listAlerts } from '../../api/alerts'
import type { AlertAction, AlertLevel, AlertRecord, AlertState } from '../../api/alerts'
import { isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import { formatDateTime } from '../../utils/format'
import AlertRuleSettings from './AlertRuleSettings.vue'
import {
  alertActions,
  alertKinds,
  alertLevels,
  alertStates,
  initialAlertStatus,
} from './alert-labels'

const route = useRoute()
const session = useSessionStore()
const tab = ref('events')
const status = ref<AlertState | ''>(initialAlertStatus(route.query.status))
const level = ref<AlertLevel | ''>('')
const rule = ref('')
const rows = ref<AlertRecord[]>([])
const cursor = ref<string>()
const subject = ref<AlertRecord>()
const action = ref<AlertAction>()
const reason = ref('')
const error = ref('')
const detailError = ref('')
const pending = ref(false)
const handling = ref(false)
const handleKey = ref('')
let listController: AbortController | undefined
let detailController: AbortController | undefined

const canConfigure = computed(() => session.can('alert:configure'))
const canHandle = computed(() => session.can('alert:handle'))

async function load(append = false): Promise<void> {
  listController?.abort()
  const controller = new AbortController()
  listController = controller
  const before = append ? cursor.value : undefined
  if (!append) {
    rows.value = []
    cursor.value = undefined
  }
  error.value = ''
  pending.value = true
  try {
    const data = await listAlerts(
      {
        rule: rule.value || undefined,
        level: level.value || undefined,
        status: status.value || undefined,
        cursor: before,
      },
      controller.signal,
    )
    if (controller.signal.aborted) return
    rows.value = append ? [...rows.value, ...data.items] : data.items
    cursor.value = data.nextCursor ?? undefined
  } catch (cause: unknown) {
    if (!controller.signal.aborted && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (!controller.signal.aborted) pending.value = false
  }
}

async function open(id: string): Promise<void> {
  detailController?.abort()
  const controller = new AbortController()
  detailController = controller
  error.value = ''
  detailError.value = ''
  try {
    const record = await alertDetail(id, controller.signal)
    if (!controller.signal.aborted) subject.value = record
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  }
}

function close(): void {
  detailController?.abort()
  subject.value = undefined
  action.value = undefined
  detailError.value = ''
  reason.value = ''
}

function choose(next: AlertAction): void {
  action.value = next
  reason.value = ''
  handleKey.value = newIdempotencyKey()
}

async function finish(): Promise<void> {
  const current = subject.value
  if (!current || !action.value || handling.value) return
  const company = session.companyId
  handling.value = true
  detailError.value = ''
  try {
    const saved = await handleAlert(
      current.id,
      action.value,
      { version: current.version, reason: reason.value.trim() || undefined },
      handleKey.value,
    )
    if (session.companyId !== company || subject.value?.id !== current.id) return
    subject.value = saved
    action.value = undefined
    window.dispatchEvent(new Event('erp:messages-changed'))
    await load()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) detailError.value = messageOf(cause)
  } finally {
    handling.value = false
  }
}

function targetLink(record: AlertRecord): string | undefined {
  return /^\/(?!\/)[a-zA-Z0-9_/?=&.-]+$/.test(record.targetUrl) && !record.targetUrl.includes('..')
    ? record.targetUrl
    : undefined
}

watch(
  () => [session.companyId, route.query.status, route.query.eventId] as const,
  () => {
    close()
    tab.value = 'events'
    status.value = initialAlertStatus(route.query.status)
    void load()
    if (typeof route.query.eventId === 'string' && /^[1-9]\d{0,18}$/.test(route.query.eventId))
      void open(route.query.eventId)
  },
  { immediate: true },
)
onUnmounted(() => {
  listController?.abort()
  detailController?.abort()
})
</script>

<template>
  <div class="page-heading"><h1>预警中心</h1></div>
  <el-tabs v-model="tab">
    <el-tab-pane name="events" label="业务预警">
      <section class="panel alert-panel" aria-label="业务预警列表">
        <div class="alert-toolbar">
          <el-select
            v-model="status"
            clearable
            placeholder="全部状态"
            aria-label="预警状态"
            @change="load()"
          >
            <el-option
              v-for="(label, value) in alertStates"
              :key="value"
              :value="value"
              :label="label"
            />
          </el-select>
          <el-select
            v-model="level"
            clearable
            placeholder="全部级别"
            aria-label="预警级别"
            @change="load()"
          >
            <el-option
              v-for="(label, value) in alertLevels"
              :key="value"
              :value="value"
              :label="label"
            />
          </el-select>
          <el-select
            v-model="rule"
            clearable
            placeholder="全部规则"
            aria-label="预警规则"
            @change="load()"
          >
            <el-option
              v-for="(label, value) in alertKinds"
              :key="value"
              :value="value"
              :label="label"
            />
          </el-select>
          <el-button :loading="pending" @click="load()">刷新</el-button>
        </div>
        <el-alert v-if="error" :title="error" type="error" :closable="false" />
        <el-table v-loading="pending" :data="rows" row-key="id" empty-text="当前筛选范围没有预警">
          <el-table-column prop="title" label="预警" min-width="155" />
          <el-table-column label="级别" width="90">
            <template #default="{ row }"
              ><el-tag
                :type="
                  row.level === 'critical' ? 'danger' : row.level === 'warning' ? 'warning' : 'info'
                "
                >{{ alertLevels[row.level as AlertLevel] }}</el-tag
              ></template
            >
          </el-table-column>
          <el-table-column prop="detail" label="触发内容" min-width="260" show-overflow-tooltip />
          <el-table-column label="状态" width="95"
            ><template #default="{ row }">{{
              alertStates[row.status as AlertState]
            }}</template></el-table-column
          >
          <el-table-column label="最近触发" min-width="170"
            ><template #default="{ row }">{{
              formatDateTime(row.lastSeenAt)
            }}</template></el-table-column
          >
          <el-table-column label="操作" width="90"
            ><template #default="{ row }"
              ><el-button type="primary" link @click="open(row.id)">查看</el-button></template
            ></el-table-column
          >
        </el-table>
        <el-button v-if="cursor" class="more-alerts" :loading="pending" @click="load(true)"
          >加载更多预警</el-button
        >
      </section>
    </el-tab-pane>
    <el-tab-pane v-if="canConfigure" name="rules" label="规则设置" lazy>
      <AlertRuleSettings v-if="tab === 'rules'" :key="session.companyId ?? ''" />
    </el-tab-pane>
  </el-tabs>
  <el-dialog
    v-if="subject"
    :model-value="true"
    :title="subject.title"
    width="min(620px, 94vw)"
    :close-on-click-modal="false"
    @close="close"
  >
    <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
    <el-descriptions :column="1" border>
      <el-descriptions-item label="触发内容">{{ subject.detail }}</el-descriptions-item>
      <el-descriptions-item label="处理状态">{{
        alertStates[subject.status]
      }}</el-descriptions-item>
      <el-descriptions-item label="首次触发">{{
        formatDateTime(subject.firstSeenAt)
      }}</el-descriptions-item>
      <el-descriptions-item label="最近触发">{{
        formatDateTime(subject.lastSeenAt)
      }}</el-descriptions-item>
      <el-descriptions-item v-if="subject.handleReason" label="处理说明">{{
        subject.handleReason
      }}</el-descriptions-item>
    </el-descriptions>
    <p v-if="targetLink(subject)">
      <RouterLink :to="targetLink(subject)!">查看业务来源</RouterLink>
    </p>
    <el-form v-if="action" label-position="top" @submit.prevent="finish">
      <el-form-item :label="action === 'ignore' ? '忽略原因（必填）' : '处理说明'">
        <el-input
          v-model="reason"
          type="textarea"
          aria-label="预警处理原因"
          maxlength="300"
          show-word-limit
        />
      </el-form-item>
      <el-button
        type="primary"
        native-type="submit"
        :loading="handling"
        :disabled="action === 'ignore' && !reason.trim()"
        >提交处理</el-button
      >
      <el-button :disabled="handling" @click="action = undefined">返回</el-button>
    </el-form>
    <template #footer>
      <template v-if="canHandle && !action && ['open', 'acknowledged'].includes(subject.status)">
        <el-button v-if="subject.status === 'open'" type="primary" @click="choose('acknowledge')">{{
          alertActions.acknowledge
        }}</el-button>
        <el-button @click="choose('resolve')">{{ alertActions.resolve }}</el-button>
        <el-button @click="choose('ignore')">{{ alertActions.ignore }}</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.alert-panel {
  padding: 16px;
}
.alert-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.alert-toolbar .el-select {
  width: min(175px, 100%);
}
.alert-toolbar .el-button {
  margin: 0;
}
.more-alerts {
  margin-top: 12px;
}
</style>
