<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import {
  workflowEnd,
  workflowInstance,
  workflowList,
  workflowTaskAction,
  workflowTaskCandidates,
  readWorkflowCopy,
} from '../../api/workflow'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import type {
  Choice,
  TaskDecision,
  WorkflowBusinessType,
  WorkflowInstance,
  WorkflowListRow,
  WorkflowTask,
} from '../../types/workflow'
import { actionLabels, businessLabels, businessRoutes, workflowStatus } from '../../types/workflow'
import { useSessionStore } from '../../stores/session'
import { registerCompanyLeaveGuard } from '../../utils/unsaved'
import { formatDateTime } from '../../utils/format'
const props = defineProps<{ kind: 'todo' | 'done' | 'mine' | 'copies' | 'all' }>()
const session = useSessionStore(),
  route = useRoute(),
  router = useRouter(),
  rows = ref<WorkflowListRow[]>([]),
  detail = ref<WorkflowInstance>(),
  error = ref(''),
  busy = ref(false),
  loading = ref(false),
  detailLoading = ref(false)
const businessType = ref<WorkflowBusinessType | ''>(''),
  status = ref(''),
  next = ref<string | null>(null),
  cursors = ref<(string | null)[]>([null])
const decisionOpen = ref(false),
  selected = ref<WorkflowTask>(),
  action = ref(''),
  comment = ref(''),
  userId = ref(''),
  userIds = ref<string[]>([]),
  taskIds = ref<string[]>([]),
  position = ref<'before' | 'after'>('before'),
  target = ref('starter'),
  choices = ref<Choice[]>([]),
  uncertain = ref(false)
let revision = 0,
  detailRevision = 0,
  requestKey = newIdempotencyKey(),
  pending: TaskDecision | undefined,
  endVersion: number | undefined,
  refreshTimer: ReturnType<typeof setTimeout> | undefined
const titles = {
  todo: '我的审批待办',
  done: '我的已办',
  mine: '我的申请',
  copies: '抄送我的',
  all: '全部授权流程',
}
const ownTasks = computed(
  () =>
    detail.value?.tasks.filter(
      (task) =>
        task.assigneeId === session.user?.id && ['pending', 'waiting_sign'].includes(task.status),
    ) ?? [],
)
const pastNodes = computed(() => [
  ...new Map(
    (detail.value?.tasks ?? [])
      .filter((task) => task.status === 'approved' && !task.parentTaskId)
      .map((task) => [task.nodeKey, { value: task.nodeKey, label: task.nodeName }]),
  ).values(),
])
const removable = computed(
  () =>
    detail.value?.tasks.filter(
      (task) =>
        task.parentTaskId === selected.value?.id &&
        task.addedBy === session.user?.id &&
        task.status === 'pending',
    ) ?? [],
)
const decisionTitle = computed(() =>
  action.value === 'add-sign'
    ? '加签'
    : action.value === 'remove-sign'
      ? '减签'
      : action.value === 'approve' && selected.value?.ownerId
        ? '反馈委派意见'
        : action.value === 'approve' && selected.value?.nodeKind === 'handler'
          ? '完成办理'
          : (actionLabels[action.value] ?? '审批处理'),
)
function mayLeave(): boolean {
  return (
    !busy.value &&
    !uncertain.value &&
    (!decisionOpen.value ||
      (!comment.value && !userId.value && !userIds.value.length && !taskIds.value.length) ||
      window.confirm('处理意见尚未提交，确定离开？'))
  )
}
const unregister = registerCompanyLeaveGuard(mayLeave)
onBeforeRouteLeave(mayLeave)
function beforeUnload(event: BeforeUnloadEvent): void {
  if (decisionOpen.value || busy.value || uncertain.value) event.preventDefault()
}
window.addEventListener('beforeunload', beforeUnload)
async function load(): Promise<void> {
  const current = ++revision
  loading.value = true
  error.value = ''
  try {
    const page = await workflowList(props.kind, {
      cursor: cursors.value.at(-1),
      limit: 20,
      businessType: businessType.value || undefined,
      status: status.value || undefined,
    })
    if (current === revision) {
      rows.value = page.items
      next.value = page.nextCursor
    }
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) {
      error.value = messageOf(cause)
      rows.value = []
    }
  } finally {
    if (current === revision) loading.value = false
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
async function open(id: string, copy?: string): Promise<void> {
  if (!mayLeave()) return
  const current = ++detailRevision
  detailLoading.value = true
  error.value = ''
  detail.value = undefined
  try {
    const value = await workflowInstance(id)
    if (current !== detailRevision) return
    detail.value = value
    if (copy) {
      await readWorkflowCopy(copy)
      window.dispatchEvent(new Event('erp:messages-changed'))
      void load()
    }
  } catch (cause: unknown) {
    if (current === detailRevision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === detailRevision) detailLoading.value = false
  }
}
function closeDetail(): void {
  if (!mayLeave()) return
  ++detailRevision
  detail.value = undefined
  if (route.params.id) void router.replace('/workflow/' + props.kind)
}
async function choose(name: string, task?: WorkflowTask): Promise<void> {
  if (busy.value || !detail.value) return
  action.value = name
  selected.value = task
  comment.value = ''
  userId.value = ''
  userIds.value = []
  taskIds.value = []
  target.value = 'starter'
  position.value = 'before'
  pending = undefined
  endVersion = detail.value.version
  requestKey = newIdempotencyKey()
  uncertain.value = false
  choices.value = []
  error.value = ''
  decisionOpen.value = true
  if (task && ['transfer', 'delegate', 'add-sign'].includes(name)) {
    busy.value = true
    try {
      choices.value = (await workflowTaskCandidates(task.id)).filter(
        (user) => user.id !== session.user?.id,
      )
    } catch (cause: unknown) {
      error.value = messageOf(cause)
      decisionOpen.value = false
    } finally {
      busy.value = false
    }
  }
}
function closeDecision(): void {
  if (mayLeave()) {
    decisionOpen.value = false
    comment.value = ''
    pending = undefined
  }
}
async function submit(): Promise<void> {
  if (!detail.value || busy.value) return
  error.value = ''
  if (!pending && !['approve'].includes(action.value) && !comment.value.trim()) {
    error.value = '请填写处理意见'
    return
  }
  if (!pending)
    pending = {
      version: selected.value?.version ?? detail.value.version,
      comment: comment.value || undefined,
      userId: userId.value || undefined,
      userIds: userIds.value.length ? [...userIds.value] : undefined,
      taskIds: taskIds.value.length ? [...taskIds.value] : undefined,
      position: action.value === 'add-sign' ? position.value : undefined,
      targetNodeKey: action.value === 'reject' ? target.value : undefined,
    }
  busy.value = true
  const id = detail.value.id
  try {
    if (action.value === 'cancel' || action.value === 'terminate')
      await workflowEnd(
        id,
        action.value,
        endVersion ?? detail.value.version,
        pending.comment ?? '',
        requestKey,
      )
    else if (selected.value)
      await workflowTaskAction(selected.value.id, action.value, pending, requestKey)
    uncertain.value = false
    decisionOpen.value = false
    pending = undefined
    comment.value = ''
    window.dispatchEvent(new Event('erp:messages-changed'))
    await load()
    const value = await workflowInstance(id)
    detail.value = value
  } catch (cause: unknown) {
    error.value = messageOf(cause)
    uncertain.value = !(cause instanceof ApiError) || cause.status === 0 || cause.status >= 500
    if (!uncertain.value) {
      pending = undefined
      requestKey = newIdempotencyKey()
      if (cause instanceof ApiError && [403, 404].includes(cause.status)) {
        detail.value = undefined
        decisionOpen.value = false
      }
    }
  } finally {
    busy.value = false
  }
}
function changed(): void {
  clearTimeout(refreshTimer)
  refreshTimer = setTimeout(() => {
    if (!busy.value && !decisionOpen.value) {
      void load()
      if (detail.value) void open(detail.value.id)
    }
  }, 350)
}
window.addEventListener('erp:messages-changed', changed)
watch(
  () => [props.kind, session.companyId],
  () => {
    ++revision
    ++detailRevision
    rows.value = []
    detail.value = undefined
    decisionOpen.value = false
    uncertain.value = false
    cursors.value = [null]
    void load()
    if (typeof route.params.id === 'string') void open(route.params.id)
  },
  { immediate: true },
)
watch(
  () => route.params.id,
  (id) => {
    if (typeof id === 'string') void open(id)
  },
)
onScopeDispose(() => {
  ++revision
  ++detailRevision
  clearTimeout(refreshTimer)
  unregister()
  window.removeEventListener('erp:messages-changed', changed)
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>{{ titles[kind] }}</h1>
      <p>处理前会重新校验来源单据权限、审批人资格和业务规则。</p>
    </div>
    <el-button :loading="loading" @click="search">刷新</el-button>
  </div>
  <nav class="flow-tabs" aria-label="审批列表">
    <router-link
      v-for="[path, label] in [
        ['todo', '待办'],
        ['done', '已办'],
        ['mine', '我的申请'],
        ['copies', '抄送我的'],
        ...(session.can('workflow_instance:view_all') ? [['all', '全部授权流程']] : []),
      ]"
      :key="path"
      :to="'/workflow/' + path"
      :class="{ active: path === kind }"
      >{{ label }}</router-link
    >
  </nav>
  <el-alert v-if="error && !decisionOpen" :title="error" type="error" :closable="false" />
  <section class="panel table-panel" :aria-busy="loading">
    <form class="filters" @submit.prevent="search">
      <el-select
        v-model="businessType"
        clearable
        aria-label="审批业务类型"
        placeholder="全部业务类型"
        class="message-category"
        ><el-option
          v-for="(label, type) in businessLabels"
          :key="type"
          :label="label"
          :value="type" /></el-select
      ><el-select
        v-model="status"
        clearable
        aria-label="流程状态"
        placeholder="全部流程状态"
        class="message-category"
        ><el-option
          v-for="value in ['running', 'approved', 'rejected', 'cancelled', 'terminated']"
          :key="value"
          :value="value"
          :label="workflowStatus[value]" /></el-select
      ><el-button native-type="submit" :loading="loading">查询</el-button>
    </form>
    <el-table :data="rows" row-key="id"
      ><el-table-column
        prop="title"
        label="申请"
        min-width="220"
        show-overflow-tooltip
      /><el-table-column label="业务类型" min-width="130"
        ><template #default="{ row }">{{
          businessLabels[row.businessType as WorkflowBusinessType]
        }}</template></el-table-column
      ><el-table-column
        prop="nodeName"
        label="环节"
        min-width="160"
        show-overflow-tooltip
      /><el-table-column label="流程状态" width="110"
        ><template #default="{ row }">{{
          workflowStatus[row.status] ?? row.status
        }}</template></el-table-column
      ><el-table-column v-if="kind === 'done'" label="我的操作" min-width="140"
        ><template #default="{ row }">{{
          actionLabels[row.action] ?? row.action
        }}</template></el-table-column
      ><el-table-column label="时间" min-width="180"
        ><template #default="{ row }">{{
          formatDateTime(row.itemCreatedAt)
        }}</template></el-table-column
      ><el-table-column label="操作" width="150" fixed="right"
        ><template #default="{ row }"
          ><button
            class="text-link"
            @click="open(row.instanceId, kind === 'copies' ? row.id : undefined)"
          >
            {{ kind === 'todo' ? '查看并处理' : '查看进度' }}
          </button></template
        ></el-table-column
      ></el-table
    >
    <div class="pagination">
      <el-button :disabled="loading || cursors.length === 1" @click="paginate(false)"
        >上一页</el-button
      ><el-button :disabled="loading || !next" @click="paginate(true)">下一页</el-button>
    </div>
  </section>
  <el-drawer
    :model-value="Boolean(detail) || detailLoading"
    title="审批详情"
    size="min(1100px, 96vw)"
    :before-close="closeDetail"
    @close="closeDetail"
    :close-on-click-modal="false"
  >
    <p v-if="detailLoading" role="status">正在读取审批进度…</p>
    <template v-if="detail"
      ><div class="page-heading">
        <div>
          <h2>{{ detail.title }}</h2>
          <p>{{ workflowStatus[detail.status] }} · 发布版本 {{ detail.modelVersion }}</p>
        </div>
        <router-link
          class="text-link"
          :to="'/' + businessRoutes[detail.businessType] + '/' + detail.businessId"
          >查看来源单据</router-link
        >
      </div>
      <el-alert
        v-if="detail.summary.belowMinPrice"
        title="本单低于最低售价；需要审批时，必须由具备价格审批权限的人员完成人工复核。"
        type="warning"
        :closable="false"
      />
      <section v-for="task in ownTasks" :key="task.id" class="flow-task-action">
        <div>
          <strong>{{ task.nodeName }}</strong>
          <p class="muted">
            {{ workflowStatus[task.status] }}{{ task.ownerId ? ' · 委派反馈后回到原处理人' : ''
            }}{{ task.priceApproval ? ' · 人工价格审批' : '' }}
          </p>
        </div>
        <div class="row-actions">
          <el-button
            v-if="task.status === 'pending'"
            type="primary"
            :disabled="busy"
            @click="choose('approve', task)"
            >{{
              task.ownerId ? '反馈委派意见' : task.nodeKind === 'handler' ? '完成办理' : '同意'
            }}</el-button
          ><el-button
            v-if="task.status === 'pending' && task.nodeKind === 'approver' && !task.ownerId"
            :disabled="busy"
            @click="choose('reject', task)"
            >驳回</el-button
          ><el-button
            v-if="task.status === 'pending' && !task.ownerId"
            :disabled="busy"
            @click="choose('transfer', task)"
            >转办</el-button
          ><el-button
            v-if="task.status === 'pending' && !task.ownerId"
            :disabled="busy"
            @click="choose('delegate', task)"
            >委派</el-button
          ><el-button
            v-if="task.status === 'pending' && !task.ownerId && !task.parentTaskId"
            :disabled="busy"
            @click="choose('add-sign', task)"
            >加签</el-button
          ><el-button
            v-if="
              !task.ownerId &&
              !task.parentTaskId &&
              detail.tasks.some(
                (item) =>
                  item.parentTaskId === task.id &&
                  item.addedBy === session.user?.id &&
                  item.status === 'pending',
              )
            "
            :disabled="busy"
            @click="choose('remove-sign', task)"
            >减签</el-button
          >
        </div>
      </section>
      <div class="row-actions">
        <el-button v-if="detail.canCancel" :disabled="busy" @click="choose('cancel')"
          >撤销申请</el-button
        ><el-button
          v-if="detail.canTerminate"
          type="danger"
          plain
          :disabled="busy"
          @click="choose('terminate')"
          >终止流程</el-button
        >
      </div>
      <h3>环节与处理人</h3>
      <el-table :data="detail.tasks" row-key="id"
        ><el-table-column
          prop="nodeName"
          label="环节"
          min-width="170"
          show-overflow-tooltip
        /><el-table-column prop="assigneeName" label="处理人" min-width="120" /><el-table-column
          label="状态"
          min-width="120"
          ><template #default="{ row }">{{
            workflowStatus[row.status] ?? row.status
          }}</template></el-table-column
        ><el-table-column
          prop="comment"
          label="意见"
          min-width="220"
          show-overflow-tooltip
        /><el-table-column label="截止时间" min-width="180"
          ><template #default="{ row }">{{
            row.dueAt ? formatDateTime(row.dueAt) : '—'
          }}</template></el-table-column
        ></el-table
      >
      <h3>审批轨迹</h3>
      <ol class="flow-history">
        <li v-for="item in detail.actions" :key="item.id">
          <div>
            <strong>{{ item.actorName }} · {{ actionLabels[item.action] ?? item.action }}</strong
            ><time>{{ formatDateTime(item.createdAt) }}</time>
          </div>
          <p v-if="item.comment">{{ item.comment }}</p>
        </li>
      </ol>
    </template>
  </el-drawer>
  <el-dialog
    v-model="decisionOpen"
    :title="decisionTitle"
    width="min(660px, 94vw)"
    :before-close="closeDecision"
    :close-on-click-modal="false"
    append-to-body
  >
    <el-form label-position="top" :disabled="busy || uncertain"
      ><el-form-item v-if="['transfer', 'delegate'].includes(action)" label="目标处理人"
        ><el-select v-model="userId" filterable
          ><el-option
            v-for="person in choices"
            :key="person.id"
            :value="person.id"
            :label="person.name" /></el-select></el-form-item
      ><template v-if="action === 'add-sign'"
        ><el-form-item label="加签人员"
          ><el-select v-model="userIds" multiple filterable
            ><el-option
              v-for="person in choices"
              :key="person.id"
              :value="person.id"
              :label="person.name" /></el-select></el-form-item
        ><el-form-item label="加签顺序"
          ><el-select v-model="position"
            ><el-option label="前加签：加签完成后原处理人才可同意" value="before" /><el-option
              label="后加签：原处理人同意后仍等待加签完成"
              value="after" /></el-select></el-form-item></template
      ><el-form-item v-if="action === 'remove-sign'" label="减少自己添加且未处理的加签"
        ><el-select v-model="taskIds" multiple
          ><el-option
            v-for="task in removable"
            :key="task.id"
            :value="task.id"
            :label="task.assigneeName ?? task.assigneeId" /></el-select></el-form-item
      ><el-form-item v-if="action === 'reject'" label="驳回位置"
        ><el-select v-model="target"
          ><el-option label="发起人，修改后重新提交" value="starter" /><el-option
            v-for="item in pastNodes"
            :key="item.value"
            :value="item.value"
            :label="item.label + '（重新审批）'" /></el-select></el-form-item
      ><el-form-item label="处理意见"
        ><el-input
          v-model="comment"
          type="textarea"
          :rows="4"
          maxlength="1000"
          show-word-limit /></el-form-item></el-form
    ><el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="uncertain" class="muted">
      请求结果尚未确认，内容已冻结。请保留当前窗口并使用原请求重试。
    </p>
    <template #footer
      ><el-button :disabled="busy || uncertain" @click="closeDecision">取消</el-button
      ><el-button type="primary" :loading="busy" @click="submit">{{
        uncertain ? '使用原请求重试' : '确认' + decisionTitle
      }}</el-button></template
    >
  </el-dialog>
</template>
