<script setup lang="ts">
import { computed, defineAsyncComponent, onScopeDispose, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import {
  workflowEnd,
  workflowBatchApprove,
  workflowInstance,
  workflowList,
  workflowRequest,
  workflowTaskAction,
  workflowTaskCandidates,
  readWorkflowCopy,
} from '../../api/workflow'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import type {
  Choice,
  GenericWorkflowRequest,
  TaskDecision,
  WorkflowBatchInput,
  WorkflowBatchResult,
  WorkflowBusinessType,
  WorkflowInstance,
  WorkflowFormValues,
  WorkflowListRow,
  WorkflowTask,
} from '../../types/workflow'
import { actionLabels, businessLabels, businessRoutes, workflowStatus } from '../../types/workflow'
import { useSessionStore } from '../../stores/session'
import { registerCompanyLeaveGuard } from '../../utils/unsaved'
import { formatDateTime } from '../../utils/format'
import DocumentFilesPanel from '../../components/DocumentFilesPanel.vue'
import SplitPane from '../../components/SplitPane.vue'
import WorkflowApprovalPreview from '../../components/workflow/WorkflowApprovalPreview.vue'
import WorkflowTodoList from '../../components/workflow/WorkflowTodoList.vue'
const WorkflowFormFields = defineAsyncComponent(
  () => import('../../components/workflow/WorkflowFormFields.vue'),
)
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
const activeRow = ref<string>()
const previewRegion = ref<HTMLElement>()
const batchIds = ref<string[]>([])
const batchOpen = ref(false)
const batchResults = ref<(WorkflowBatchResult & { title: string })[]>([])
const batchRows = computed(() => rows.value.filter((row) => batchIds.value.includes(row.id)))
const batchGroup = computed(() => batchRows.value[0]?.batchGroup ?? undefined)
let batchPending: WorkflowBatchInput | undefined
let batchKey = ''
let batchTitles = new Map<string, string>()
const application = ref<GenericWorkflowRequest>(),
  taskApplication = ref<GenericWorkflowRequest>()
const editedFields = ref<WorkflowFormValues>({})
const writableFields = computed(
  () => taskApplication.value?.fields.filter((item) => item.access === 'write') ?? [],
)
function fieldChanges(): WorkflowFormValues {
  return Object.fromEntries(
    writableFields.value
      .filter(
        (item) =>
          Object.hasOwn(editedFields.value, item.field.key) &&
          editedFields.value[item.field.key] !== item.value,
      )
      .map((item) => [item.field.key, editedFields.value[item.field.key]]),
  )
}
const businessType = ref<WorkflowBusinessType | ''>(''),
  keyword = ref(''),
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
        task.assigneeId === session.user?.id &&
        task.canHandle !== false &&
        ['pending', 'waiting_sign'].includes(task.status),
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
      (!comment.value &&
        !userId.value &&
        !userIds.value.length &&
        !taskIds.value.length &&
        !Object.keys(fieldChanges()).length) ||
      window.confirm('处理意见尚未提交，确定离开？'))
  )
}
const unregister = registerCompanyLeaveGuard(mayLeave)
onBeforeRouteLeave(mayLeave)
function beforeUnload(event: BeforeUnloadEvent): void {
  if (decisionOpen.value || batchOpen.value || busy.value || uncertain.value) event.preventDefault()
}
window.addEventListener('beforeunload', beforeUnload)
async function load(autoOpen = true): Promise<void> {
  const current = ++revision
  loading.value = true
  error.value = ''
  try {
    const page = await workflowList(props.kind, {
      cursor: cursors.value.at(-1),
      limit: 20,
      businessType: businessType.value || undefined,
      status: props.kind === 'todo' ? undefined : status.value || undefined,
      keyword: props.kind === 'todo' ? keyword.value.trim() || undefined : undefined,
    })
    if (current === revision) {
      rows.value = page.items
      next.value = page.nextCursor
      batchIds.value = batchIds.value.filter((id) =>
        page.items.some((row) => row.id === id && row.batchGroup),
      )
      if (
        autoOpen &&
        props.kind === 'todo' &&
        !route.params.id &&
        !detailLoading.value &&
        !busy.value
      ) {
        const first = page.items[0]
        if (
          !detail.value ||
          (activeRow.value && !page.items.some((row) => row.id === activeRow.value))
        ) {
          if (first) void open(first.instanceId, undefined, first.id)
          else closeDetail()
        }
      }
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
  batchIds.value = []
  void load()
}
function paginate(forward: boolean): void {
  batchIds.value = []
  if (forward && next.value) cursors.value.push(next.value)
  else if (!forward && cursors.value.length > 1) cursors.value.pop()
  void load()
}
async function open(id: string, copy?: string, taskId?: string): Promise<void> {
  if (!mayLeave()) return
  decisionOpen.value = false
  pending = undefined
  await loadDetail(id, taskId, copy)
}
async function openTodo(row: WorkflowListRow): Promise<void> {
  await open(row.instanceId, undefined, row.id)
  if (activeRow.value === row.id && window.matchMedia?.('(max-width: 767px)').matches) {
    previewRegion.value?.focus({ preventScroll: true })
    previewRegion.value?.scrollIntoView({ block: 'start' })
  }
}
async function loadDetail(id: string, taskId?: string, copy?: string): Promise<void> {
  const current = ++detailRevision
  activeRow.value = taskId
  detailLoading.value = true
  error.value = ''
  detail.value = undefined
  application.value = undefined
  try {
    const value = await workflowInstance(id)
    if (current !== detailRevision) return
    detail.value = value
    if (value.businessType === 'wf_generic_request') await loadApplication(value, current, taskId)
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
async function loadApplication(
  value: WorkflowInstance,
  token: number,
  taskId?: string,
): Promise<void> {
  try {
    const request = await workflowRequest(value.businessId, taskId)
    if (token === detailRevision) application.value = request
  } catch (cause: unknown) {
    if (
      token === detailRevision &&
      !(cause instanceof ApiError && [403, 404].includes(cause.status)) &&
      !isCancelled(cause)
    )
      error.value = messageOf(cause)
  }
}
function closeDetail(): void {
  if (!mayLeave()) return
  ++detailRevision
  activeRow.value = undefined
  detailLoading.value = false
  detail.value = undefined
  application.value = undefined
  if (route.params.id) void router.replace('/workflow/' + props.kind)
}
function toggleBatch(row: WorkflowListRow): void {
  if (
    busy.value ||
    !row.batchGroup ||
    row.taskVersion === undefined ||
    (batchGroup.value && row.batchGroup !== batchGroup.value)
  )
    return
  if (batchIds.value.includes(row.id)) batchIds.value = batchIds.value.filter((id) => id !== row.id)
  else batchIds.value = [...batchIds.value, row.id]
}
function chooseBatch(): void {
  if (busy.value || !batchGroup.value || batchRows.value.length < 2) return
  const items = batchRows.value.flatMap((row) =>
    row.taskVersion === undefined ? [] : [{ id: row.id, version: row.taskVersion }],
  )
  if (items.length !== batchRows.value.length) return
  batchPending = { group: batchGroup.value, items }
  batchTitles = new Map(batchRows.value.map((row) => [row.id, row.title]))
  batchKey = newIdempotencyKey()
  batchResults.value = []
  error.value = ''
  batchOpen.value = true
}
function closeBatch(): void {
  if (busy.value || uncertain.value) return
  batchOpen.value = false
  batchPending = undefined
  error.value = ''
}
function unknownResult(cause: unknown): boolean {
  return !(cause instanceof ApiError) || cause.status === 0 || cause.status >= 500
}
async function refreshHandled(
  token: number,
  id: string | undefined,
  preferred: string | undefined,
): Promise<void> {
  const index = rows.value.findIndex((row) => row.id === preferred)
  await load(false)
  if (token !== detailRevision) return
  if (props.kind === 'todo' && preferred && !route.params.id) {
    const row =
      rows.value.find((item) => item.id === preferred) ??
      rows.value[Math.max(0, index)] ??
      rows.value.at(-1)
    if (row) {
      await loadDetail(row.instanceId, row.id)
      return
    }
    detail.value = undefined
    application.value = undefined
    activeRow.value = undefined
    return
  }
  if (id) await loadDetail(id)
}
async function submitBatch(): Promise<void> {
  if (!batchPending || busy.value) return
  busy.value = true
  error.value = ''
  const token = detailRevision
  try {
    const results = await workflowBatchApprove(batchPending, batchKey)
    if (token !== detailRevision) return
    batchResults.value = results.map((result) => ({
      ...result,
      title: batchTitles.get(result.id) ?? result.id,
    }))
    batchIds.value = []
    batchOpen.value = false
    batchPending = undefined
    uncertain.value = false
    window.dispatchEvent(new Event('erp:messages-changed'))
    await refreshHandled(token, detail.value?.id, activeRow.value)
  } catch (cause: unknown) {
    if (token !== detailRevision) return
    error.value = messageOf(cause)
    uncertain.value = unknownResult(cause)
  } finally {
    busy.value = false
  }
}
async function choose(name: string, task?: WorkflowTask): Promise<void> {
  if (busy.value || !detail.value) return
  action.value = name
  selected.value = task
  taskApplication.value = undefined
  editedFields.value = {}
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
  if (task && name === 'approve' && detail.value.businessType === 'wf_generic_request') {
    const token = detailRevision
    busy.value = true
    try {
      const request = await workflowRequest(detail.value.businessId, task.id)
      if (token === detailRevision) {
        taskApplication.value = request
        editedFields.value = Object.fromEntries(
          request.fields
            .filter((item) => item.access === 'write')
            .map((item) => [item.field.key, item.value]),
        )
      }
    } catch (cause: unknown) {
      if (token === detailRevision && !isCancelled(cause)) error.value = messageOf(cause)
      decisionOpen.value = false
    } finally {
      busy.value = false
    }
    return
  }
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
      fields:
        action.value === 'approve' && writableFields.value.length ? fieldChanges() : undefined,
    }
  busy.value = true
  const id = detail.value.id
  const token = detailRevision
  const taskId = activeRow.value ?? selected.value?.id
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
    if (token !== detailRevision) return
    uncertain.value = false
    decisionOpen.value = false
    pending = undefined
    comment.value = ''
    window.dispatchEvent(new Event('erp:messages-changed'))
    await refreshHandled(token, id, taskId)
  } catch (cause: unknown) {
    if (token !== detailRevision) return
    error.value = messageOf(cause)
    uncertain.value = unknownResult(cause)
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
    if (!busy.value && !decisionOpen.value && !batchOpen.value) {
      void load()
      if (detail.value) void open(detail.value.id, undefined, activeRow.value)
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
    activeRow.value = undefined
    batchIds.value = []
    batchResults.value = []
    batchOpen.value = false
    batchPending = undefined
    detail.value = undefined
    detailLoading.value = false
    application.value = undefined
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
    </div>
    <div class="row-actions">
      <el-button :loading="loading" :disabled="busy" @click="search">刷新</el-button>
      <el-button
        v-if="kind === 'todo' && batchIds.length"
        type="primary"
        :disabled="busy || batchIds.length < 2"
        @click="chooseBatch"
      >
        批量同意（{{ batchIds.length }}）
      </el-button>
    </div>
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
  <el-alert
    v-if="error && !decisionOpen && !batchOpen"
    :title="error"
    type="error"
    :closable="false"
  />
  <section
    v-if="batchResults.length"
    class="panel batch-results"
    aria-label="批量审批结果"
    aria-live="polite"
  >
    <div class="row-actions">
      <strong>批量审批结果</strong>
      <el-button size="small" text @click="batchResults = []">关闭结果</el-button>
    </div>
    <ul>
      <li v-for="result in batchResults" :key="result.id">
        {{ result.title }} ·
        {{ result.approved ? '已同意' : `处理失败（${result.message ?? '请刷新后查看'}）` }}
      </li>
    </ul>
  </section>
  <SplitPane
    v-if="kind === 'todo'"
    :initial-width="380"
    :min-width="260"
    :min-secondary-width="380"
    :storage-key="`workflow-todo-split:${session.user?.id}:${session.companyId}`"
    label="调整待办列表宽度"
  >
    <template #primary>
      <WorkflowTodoList
        :rows="rows"
        :active="activeRow"
        :selection="batchIds"
        :selection-group="batchGroup"
        :busy="busy"
        :loading="loading"
        @open="openTodo"
        @toggle="toggleBatch"
      >
        <template #filters>
          <form class="todo-filters" @submit.prevent="search">
            <el-input
              v-model="keyword"
              clearable
              :disabled="busy"
              maxlength="100"
              aria-label="搜索待办"
              placeholder="单号、标题或发起人"
            />
            <el-select
              v-model="businessType"
              clearable
              aria-label="审批业务类型"
              placeholder="全部业务类型"
              :disabled="busy"
            >
              <el-option
                v-for="(label, type) in businessLabels"
                :key="type"
                :label="label"
                :value="type"
              />
            </el-select>
            <el-button native-type="submit" :disabled="busy" :loading="loading">查询</el-button>
          </form>
        </template>
        <template #pagination>
          <div v-if="cursors.length > 1 || next" class="pagination">
            <el-button :disabled="busy || loading || cursors.length === 1" @click="paginate(false)"
              >上一页</el-button
            >
            <el-button :disabled="busy || loading || !next" @click="paginate(true)"
              >下一页</el-button
            >
          </div>
        </template>
      </WorkflowTodoList>
    </template>
    <template #secondary>
      <div ref="previewRegion" tabindex="-1" class="approval-preview-region">
        <WorkflowApprovalPreview
          :detail="detail"
          :application="application"
          :active-task-id="activeRow"
          :user-id="session.user?.id"
          :loading="detailLoading"
          :busy="busy"
          @choose="choose"
        />
      </div>
    </template>
  </SplitPane>
  <section v-else class="panel table-panel" :aria-busy="loading">
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
      /><el-table-column label="流程状态" width="130"
        ><template #default="{ row }">
          <el-tag v-if="row.automaticProcessingFailed" type="warning" size="small">
            自动处理失败
          </el-tag>
          <template v-else>{{ workflowStatus[row.status] ?? row.status }}</template>
        </template></el-table-column
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
            查看进度
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
    v-if="kind !== 'todo'"
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
          v-if="businessRoutes[detail.businessType]"
          :to="'/' + businessRoutes[detail.businessType] + '/' + detail.businessId"
          >查看来源单据</router-link
        >
      </div>
      <el-alert
        v-if="detail.automaticProcessingFailed"
        title="自动处理失败。系统会退避重试，也可按权限处理当前任务或终止流程。"
        type="warning"
        :closable="false"
      />
      <el-alert
        v-if="detail.summary.belowMinPrice"
        title="本单低于最低售价；需要审批时，必须由具备价格审批权限的人员完成人工复核。"
        type="warning"
        :closable="false"
      />
      <section v-if="application" class="flow-task-action workflow-application-detail">
        <h3>申请表单</h3>
        <dl class="workflow-application-fields">
          <template v-for="item in application.fields" :key="item.field.key">
            <dt>{{ item.field.label }}</dt>
            <dd>
              {{
                typeof item.value === 'boolean' ? (item.value ? '是' : '否') : (item.value ?? '—')
              }}
            </dd>
          </template>
        </dl>
        <DocumentFilesPanel
          owner-type="wf_generic_request"
          :owner-id="application.id"
          :readonly="!application.canUpload"
        />
      </section>
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
            }}</el-button><el-button
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
    v-model="batchOpen"
    title="批量同意"
    width="min(500px, 94vw)"
    :before-close="closeBatch"
    :close-on-click-modal="false"
    append-to-body
  >
    <p>确认同意所选的 {{ batchPending?.items.length }} 项待办？系统将逐项校验和处理。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="uncertain" class="muted">
      请求结果尚未确认。请使用原请求重试，已处理任务会返回原结果。
    </p>
    <template #footer>
      <el-button :disabled="busy || uncertain" @click="closeBatch">取消</el-button>
      <el-button type="primary" :loading="busy" @click="submitBatch">{{
        uncertain ? '使用原请求重试' : '确认批量同意'
      }}</el-button>
    </template>
  </el-dialog>
  <el-dialog
    v-model="decisionOpen"
    :title="decisionTitle"
    width="min(660px, 94vw)"
    :before-close="closeDecision"
    :close-on-click-modal="false"
    append-to-body
  >
    <WorkflowFormFields
      v-if="writableFields.length"
      v-model="editedFields"
      :fields="writableFields.map((item) => item.field)"
      :disabled="busy || uncertain"
    />
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

<style scoped>
.todo-filters {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px;
}
.todo-filters .el-select {
  flex: 1;
  min-width: 0;
}
.todo-filters .el-input {
  flex: 1 0 100%;
}
.todo-filters {
  flex-wrap: wrap;
}
.approval-preview-region {
  scroll-margin-top: 140px;
}
.batch-results {
  padding: 12px 16px;
  margin-bottom: 12px;
}
.batch-results .row-actions {
  justify-content: space-between;
}
.batch-results ul {
  margin: 4px 0;
  padding-left: 18px;
}
.workflow-application-detail {
  display: block;
}
.workflow-application-fields {
  display: grid;
  grid-template-columns: minmax(90px, auto) 1fr;
  gap: 10px 18px;
}
.workflow-application-fields dd {
  margin: 0;
  overflow-wrap: anywhere;
}
</style>
