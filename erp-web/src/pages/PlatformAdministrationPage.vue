<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import { platformPage, platformList, platformGet, platformWrite } from '../api/platform'
import { allRecords } from '../api/admin'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import { useSessionStore } from '../stores/session'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { registerCompanyLeaveGuard } from '../utils/unsaved'
import type { DataRow, FormField, SelectOption } from '../types/admin'
import { fieldValues, optionsFrom } from '../utils/admin'
import DataFields from '../components/DataFields.vue'
import RecordFrame from '../components/RecordFrame.vue'
import { formatDateTime } from '../utils/format'

const props = defineProps<{ kind: string }>(),
  session = useSessionStore()
interface Spec {
  title: string
  permission: string
  columns: string[]
  list?: boolean
  fields?: FormField[]
  create?: boolean
  company?: boolean
}
const choices = (pairs: string[][]): SelectOption[] =>
  pairs.map((pair) => ({ value: pair[0]!, label: pair[1]! }))
const state: FormField = {
  key: 'status',
  label: '状态',
  kind: 'select',
  initial: 'active',
  options: choices([
    ['active', '启用'],
    ['disabled', '停用'],
  ]),
}
const specs: Record<string, Spec> = {
  'dict-types': {
    title: '数据字典',
    permission: 'dict',
    columns: ['code', 'name', 'status'],
    create: true,
    fields: [
      { key: 'code', label: '字典编码', required: true, readonlyOnEdit: true, max: 64 },
      { key: 'name', label: '字典名称', required: true, max: 100 },
      state,
    ],
  },
  'dict-items': {
    title: '字典项',
    permission: 'dict',
    columns: ['typeCode', 'value', 'label', 'effectiveStatus'],
    create: true,
    fields: [
      {
        key: 'typeCode',
        label: '所属字典',
        required: true,
        readonlyOnEdit: true,
        kind: 'select',
        source: 'sys/dict-types',
      },
      { key: 'value', label: '字典值', required: true, readonlyOnEdit: true, max: 64 },
      { key: 'label', label: '显示名称', required: true, max: 100 },
      {
        key: 'color',
        label: '显示颜色',
        kind: 'select',
        initial: 'neutral',
        options: choices([
          ['neutral', '中性'],
          ['primary', '主色'],
          ['success', '成功'],
          ['warning', '提醒'],
          ['danger', '警示'],
        ]),
      },
      { key: 'sortNo', label: '排序', kind: 'number', initial: 0, max: 1000000 },
      state,
    ],
  },
  notices: {
    title: '通知公告',
    permission: 'notice',
    columns: ['title', 'level', 'status', 'publishAt', 'expireAt'],
    create: true,
    fields: [
      { key: 'title', label: '公告标题', required: true, max: 200 },
      { key: 'content', label: '公告正文', required: true, kind: 'textarea', max: 50000 },
      {
        key: 'level',
        label: '级别',
        kind: 'select',
        initial: 'normal',
        options: choices([
          ['normal', '普通公告'],
          ['important', '重要公告，登录后确认'],
        ]),
      },
    ],
  },
  'notify-templates': {
    title: '消息模板',
    permission: 'notify_template',
    columns: ['code', 'channel', 'title', 'status'],
    create: true,
    fields: [
      { key: 'code', label: '模板编码', required: true, readonlyOnEdit: true, max: 64 },
      {
        key: 'channel',
        label: '渠道',
        kind: 'select',
        required: true,
        readonlyOnEdit: true,
        initial: 'email',
        options: choices([
          ['site', '站内信'],
          ['email', '邮件'],
          ['sms', '短信'],
        ]),
      },
      { key: 'title', label: '标题', required: true, max: 200 },
      { key: 'content', label: '正文', required: true, kind: 'textarea', max: 4000 },
      { key: 'params', label: '允许的变量（输入后按回车添加）', kind: 'tags', initial: [] },
      { key: 'smsTemplateId', label: '短信审核模板编号', max: 64 },
      state,
    ],
  },
  configs: {
    title: '系统参数',
    permission: 'sys_config',
    columns: ['configKey', 'description', 'valueType', 'value', 'sensitive'],
    list: true,
    create: true,
    fields: [
      { key: 'configKey', label: '参数编码', required: true, readonlyOnEdit: true, max: 64 },
      { key: 'description', label: '说明', max: 200 },
      {
        key: 'valueType',
        label: '类型',
        kind: 'select',
        initial: 'string',
        options: choices([
          ['string', '文本'],
          ['number', '数字'],
          ['boolean', '布尔值'],
          ['json', '结构化配置'],
        ]),
      },
      { key: 'sensitive', label: '敏感参数', kind: 'boolean', readonlyOnEdit: true },
      {
        key: 'value',
        label: '参数值（敏感参数需重新填写）',
        kind: 'textarea',
        max: 16384,
        required: true,
      },
    ],
  },
  jobs: {
    title: '定时任务',
    permission: 'job',
    columns: ['code', 'name', 'schedule', 'enabled', 'lastResult', 'lastRunAt', 'nextRunAt'],
    list: true,
    fields: [
      { key: 'schedule', label: '执行计划', required: true, max: 128 },
      { key: 'enabled', label: '启用自动执行', kind: 'boolean' },
    ],
  },
  'login-logs': {
    title: '登录日志',
    permission: 'login_log',
    columns: ['username', 'method', 'result', 'ip', 'userAgent', 'createdAt', 'requestId'],
  },
  'online-users': {
    title: '在线用户',
    permission: 'online_user',
    columns: ['id', 'username', 'displayName', 'ip', 'userAgent', 'loginAt', 'lastSeenAt'],
  },
  'api-access-logs': {
    title: 'API 访问日志',
    permission: 'api_log',
    company: true,
    columns: [
      'requestMethod',
      'routePattern',
      'statusCode',
      'durationMs',
      'slowRequest',
      'requestId',
      'createdAt',
    ],
  },
  'api-error-logs': {
    title: 'API 异常日志',
    permission: 'api_error',
    company: true,
    columns: ['errorClass', 'errorSummary', 'handled', 'requestId', 'traceId', 'createdAt'],
  },
  'notify-send-logs': {
    title: '消息发送日志',
    permission: 'notify_log',
    company: true,
    columns: ['channel', 'templateCode', 'receiver', 'status', 'attempts', 'error', 'createdAt'],
  },
  'notify-robot': {
    title: '企业微信机器人',
    permission: 'notify_robot',
    list: true,
    columns: ['enabled', 'secretConfigured', 'version'],
    fields: [
      { key: 'enabled', label: '启用通用业务提醒', kind: 'boolean' },
      { key: 'key', label: '机器人密钥（留空保留已有密钥）', kind: 'password', max: 128 },
    ],
  },
}
const spec = computed(() => specs[props.kind]!),
  rows = ref<DataRow[]>([]),
  next = ref<string | null>(null),
  cursors = ref<(string | null)[]>([null]),
  error = ref(''),
  notice = ref(''),
  busy = ref(false),
  keyword = ref(''),
  typeCode = ref('customer_source'),
  selected = ref<DataRow>(),
  editor = ref(false),
  values = reactive<Record<string, unknown>>({}),
  options = reactive<Record<string, SelectOption[]>>({}),
  runLogs = ref<DataRow[]>(),
  detail = ref<DataRow>()
const audience = ref('all'),
  targetIds = ref<string[]>([]),
  audienceOptions = ref<SelectOption[]>([]),
  publishAt = ref(''),
  expireAt = ref('')
let revision = 0,
  editRevision = 0,
  saveKey = '',
  active = true
const actionKeys = new Map<string, string>()
const uncertain = ref(false),
  mutating = ref(false)
let baseline = ''
function editorState(): string {
  return JSON.stringify({
    values,
    audience: audience.value,
    targetIds: targetIds.value,
    publishAt: publishAt.value,
    expireAt: expireAt.value,
  })
}
const dirty = computed(() => editor.value && editorState() !== baseline)
function mayLeave(): boolean {
  return !mutating.value && (!dirty.value || window.confirm('修改尚未保存，确定放弃这些修改？'))
}
function closeEditor(): void {
  if (mayLeave()) {
    ++editRevision
    editor.value = false
  }
}
function beforeUnload(event: BeforeUnloadEvent): void {
  if (dirty.value || mutating.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
const unregisterCompanyGuard = registerCompanyLeaveGuard(mayLeave)
onBeforeRouteLeave(mayLeave)
window.addEventListener('beforeunload', beforeUnload)
const readonlyPreset = computed(() => Boolean(selected.value?.systemItem))
const fields = computed(() =>
  (spec.value.fields ?? []).map((field) => ({
    ...field,
    readonlyOnEdit: field.readonlyOnEdit || (readonlyPreset.value && field.key !== 'status'),
  })),
)
const canCreate = computed(
  () =>
    spec.value.create &&
    session.can(spec.value.permission + (props.kind === 'configs' ? ':update' : ':create')),
)
function editable(row: DataRow): boolean {
  return (
    Boolean(spec.value.fields) &&
    session.can(spec.value.permission + (props.kind === 'notify-robot' ? ':manage' : ':update')) &&
    !(props.kind === 'dict-types' && row.systemItem) &&
    !(props.kind === 'notices' && row.status !== 'draft') &&
    !(props.kind === 'notify-templates' && Number(row.id) < 100) &&
    !(props.kind === 'configs' && String(row.configKey).startsWith('notify.wecom'))
  )
}
const labels: Record<string, string> = {
  id: '会话标识',
  code: '编码',
  name: '名称',
  status: '状态',
  typeCode: '字典编码',
  value: '值',
  label: '显示名称',
  effectiveStatus: '状态',
  title: '标题',
  level: '级别',
  channel: '渠道',
  configKey: '参数编码',
  description: '说明',
  valueType: '类型',
  sensitive: '敏感',
  schedule: '计划',
  enabled: '启用',
  lastResult: '执行结果',
  lastRunAt: '上次执行',
  nextRunAt: '下次执行',
  username: '用户名',
  displayName: '姓名',
  method: '登录方式',
  result: '结果',
  ip: 'IP',
  userAgent: '浏览器',
  loginAt: '登录时间',
  lastSeenAt: '最后访问',
  requestMethod: '方法',
  routePattern: '路由模板',
  statusCode: '状态码',
  durationMs: '耗时（毫秒）',
  slowRequest: '慢请求',
  requestId: '请求号',
  traceId: '链路号',
  errorClass: '异常类型',
  errorSummary: '异常摘要',
  handled: '已处理',
  createdAt: '时间',
  receiver: '收件目标',
  templateCode: '模板',
  attempts: '尝试次数',
  error: '错误说明',
  secretConfigured: '密钥已配置',
  version: '版本',
  publishAt: '发布时间',
  expireAt: '到期时间',
  normal: '普通',
  important: '重要',
  active: '启用',
  disabled: '停用',
  draft: '草稿',
  published: '已发布',
  withdrawn: '已撤回',
  pending: '等待处理',
  running: '执行中',
  success: '成功',
  failed: '失败',
  partial_failed: '部分失败',
  skipped: '已跳过',
  sent: '已发送',
  sending: '发送中',
  locked: '已锁定',
  sms: '短信',
  email: '邮件',
  site: '站内信',
  wecom: '企业微信',
  password: '密码',
  wechat: '微信',
}
function columnWidth(key: string): number {
  if (key === 'id' || key.includes('Id') || key.includes('Pattern') || key === 'userAgent')
    return 230
  return key.endsWith('At') ? 180 : 140
}
function cell(row: DataRow, key: string): string {
  const value = row[key]
  if (key === 'value' && row.sensitive) return '••••••'
  if (value == null || value === '') return '—'
  if (
    typeof value === 'boolean' ||
    ['enabled', 'sensitive', 'handled', 'slowRequest', 'secretConfigured'].includes(key)
  )
    return value ? '是' : '否'
  if (key.endsWith('At')) return formatDateTime(String(value))
  return labels[String(value)] ?? String(value)
}
async function load(): Promise<void> {
  const current = ++revision
  busy.value = true
  error.value = ''
  try {
    let items: DataRow[],
      cursor: string | null = null
    if (props.kind === 'notify-robot')
      items = [{ id: 'wecom', ...(await platformGet<Record<string, unknown>>('notify-robot')) }]
    else if (spec.value.list)
      items = (await platformList(props.kind)).map((row) => ({
        ...row,
        id: String(row.id ?? row.code ?? row.configKey),
      }))
    else {
      const page = await platformPage(
        props.kind,
        {
          cursor: cursors.value.at(-1) ?? undefined,
          limit: 20,
          keyword: keyword.value || undefined,
          typeCode: props.kind === 'dict-items' ? typeCode.value : undefined,
          requestId: props.kind.startsWith('api-') ? keyword.value : undefined,
        },
        spec.value.company,
      )
      items = page.items
      cursor = page.nextCursor
    }
    if (current === revision) {
      rows.value = items
      next.value = cursor
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
async function audienceChanged(): Promise<void> {
  targetIds.value = []
  audienceOptions.value = []
  const source = (
    { user: 'users', company: 'companies', department: 'org/departments', role: 'roles' } as Record<
      string,
      string
    >
  )[audience.value]
  if (!source) return
  try {
    audienceOptions.value = optionsFrom(await allRecords(source, audience.value === 'department'))
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  }
}
function dateInput(value: unknown): string {
  if (!value) return ''
  const date = new Date(String(value))
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
}
async function edit(row?: DataRow): Promise<void> {
  const currentEdit = ++editRevision,
    context = props.kind + ':' + session.companyId
  const isCurrent = (): boolean =>
    active && currentEdit === editRevision && context === props.kind + ':' + session.companyId
  error.value = ''
  uncertain.value = false
  saveKey = newIdempotencyKey()
  try {
    let current = row ? { ...row } : undefined
    if (row && props.kind === 'notices') current = await platformGet<DataRow>('notices/' + row.id)
    if (!isCurrent()) return
    if (current && props.kind === 'dict-items')
      current = {
        ...current,
        status: String(current.effectiveStatus ?? current.status),
        version: Number(current.editVersion),
      }
    if (current && props.kind === 'notify-templates' && typeof current.params === 'string')
      current.params = JSON.parse(current.params) as unknown
    selected.value = current
    Object.keys(values).forEach((key) => delete values[key])
    Object.assign(values, fieldValues(fields.value, current))
    if (!row && props.kind === 'dict-items') values.typeCode = typeCode.value
    audience.value = 'all'
    targetIds.value = []
    publishAt.value = dateInput(current?.publishAt)
    expireAt.value = dateInput(current?.expireAt)
    if (props.kind === 'notices' && Array.isArray(current?.targets)) {
      const target = current.targets[0] as { type?: unknown }
      audience.value = String(target?.type ?? 'all')
      await audienceChanged()
      targetIds.value = current.targets
        .filter((item: { type?: unknown }) => item.type === audience.value)
        .map((item: { id?: unknown }) => String(item.id))
    }
    for (const field of fields.value.filter((item) => item.source)) {
      const rows = await allRecords(field.source!)
      if (!isCurrent()) return
      options[field.key] = optionsFrom(rows)
    }
    if (isCurrent()) {
      editor.value = true
      baseline = editorState()
    }
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  }
}
async function save(): Promise<void> {
  const missing = fields.value.find(
    (field) => field.required && (values[field.key] == null || values[field.key] === ''),
  )
  if (missing) {
    error.value = '请填写' + missing.label
    return
  }
  busy.value = true
  mutating.value = true
  error.value = ''
  try {
    const payload: Record<string, unknown> = { ...values, version: selected.value?.version ?? 0 }
    let path = props.kind,
      method = selected.value ? 'PUT' : 'POST'
    if (selected.value) path += '/' + selected.value.id
    if (props.kind === 'configs') {
      path = 'configs/' + String(payload.configKey)
      method = 'PUT'
      delete payload.configKey
    }
    if (props.kind === 'notify-robot') {
      path = 'notify-robot'
      method = 'PUT'
    }
    if (props.kind === 'notices') {
      payload.targets =
        audience.value === 'all'
          ? [{ type: 'all', id: 0 }]
          : targetIds.value.map((id) => ({ type: audience.value, id }))
      payload.publishAt = publishAt.value ? new Date(publishAt.value).toISOString() : null
      payload.expireAt = expireAt.value ? new Date(expireAt.value).toISOString() : null
    }
    await platformWrite(path, payload, method, saveKey)
    editor.value = false
    notice.value = '已保存'
    await load()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) {
      error.value = messageOf(cause)
      uncertain.value =
        cause instanceof ApiError && (cause.status === 0 || cause.code === 'REQUEST_IN_PROGRESS')
    }
  } finally {
    busy.value = false
    mutating.value = false
  }
}
async function action(row: DataRow, operation: string): Promise<void> {
  if (operation === 'items') {
    await routerPush('/sys/dict-items?type=' + row.code)
    return
  }
  if (operation === 'view') {
    detail.value = props.kind === 'notices' ? await platformGet<DataRow>('notices/' + row.id) : row
    return
  }
  if (operation === 'logs') {
    const page = await platformPage('jobs/' + row.id + '/logs')
    runLogs.value = page.items
    return
  }
  if (
    !window.confirm(
      (
        {
          publish: '发布这条公告？',
          withdraw: '撤回后收件人将无法继续查看公告，确定撤回？',
          kick: '确定强制下线此会话？',
          run: '手动执行一次此任务？暂停自动执行时仍可手动执行。',
          delete: '确定删除未引用的字典资料？',
          handled: '确定更新异常处理状态？',
        } as Record<string, string>
      )[operation] ?? '确定执行此操作？',
    )
  )
    return
  busy.value = true
  error.value = ''
  try {
    const path = props.kind + '/' + row.id + (operation === 'delete' ? '' : '/' + operation)
    const actionId = props.kind + ':' + row.id + ':' + operation + ':' + row.version
    const key = actionKeys.get(actionId) ?? newIdempotencyKey()
    actionKeys.set(actionId, key)
    await platformWrite(
      path,
      operation === 'handled'
        ? { version: row.version, handled: !row.handled }
        : { version: row.editVersion ?? row.version },
      operation === 'delete' ? 'DELETE' : operation === 'handled' ? 'PUT' : 'POST',
      key,
      spec.value.company,
    )
    actionKeys.delete(actionId)
    notice.value = operation === 'run' ? '任务已排队，可查看执行记录' : '操作完成'
    await load()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
async function safeAction(row: DataRow, operation: string): Promise<void> {
  try {
    await action(row, operation)
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  }
}
const route = useRoute(),
  router = useRouter()
async function routerPush(path: string): Promise<void> {
  await router.push(path)
}
watch(
  () => [props.kind, session.companyId, route.query.type],
  () => {
    ++editRevision
    actionKeys.clear()
    editor.value = false
    detail.value = undefined
    runLogs.value = undefined
    typeCode.value = typeof route.query.type === 'string' ? route.query.type : 'customer_source'
    notice.value = ''
    search()
  },
  { immediate: true },
)
onScopeDispose(() => {
  active = false
  ++revision
  ++editRevision
  unregisterCompanyGuard()
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>{{ spec.title }}</h1>
      <p v-if="kind === 'dict-types' || kind === 'dict-items'">
        审批、过账、签收等业务状态由系统维护，不进入数据字典。
      </p>
      <p v-if="kind === 'notify-robot'">
        群内只发送通用提醒。密钥保存后不再显示，留空保留已有密钥。
      </p>
    </div>
    <el-button v-if="canCreate" type="primary" @click="edit()">新增</el-button>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" /><el-alert
    v-if="notice"
    :title="notice"
    type="success"
    @close="notice = ''"
  />
  <section class="panel table-panel" :aria-busy="busy">
    <form class="filters" @submit.prevent="search">
      <div v-if="kind === 'dict-items'" class="filter-field">
        <label>字典编码</label><el-input v-model="typeCode" maxlength="64" />
      </div>
      <div v-else-if="kind === 'dict-types' || kind.startsWith('api-')" class="filter-field">
        <label>{{ kind.startsWith('api-') ? '请求号' : '名称或编码' }}</label><el-input
          v-model="keyword"
          maxlength="100"
          clearable
        />
      </div>
      <el-button native-type="submit" :loading="busy">刷新查询</el-button
      ><router-link v-if="kind === 'dict-items'" class="text-link" to="/sys/dict-types"
        >返回字典类型</router-link
      >
    </form>
    <el-table :data="rows" row-key="id"
      ><el-table-column
        v-for="column in spec.columns"
        :key="column"
        :label="labels[column] ?? column"
        :min-width="columnWidth(column)"
        show-overflow-tooltip
        ><template #default="{ row }">{{ cell(row, column) }}</template></el-table-column
      ><el-table-column label="操作" min-width="220" fixed="right"
        ><template #default="{ row }"
          ><div class="row-actions">
            <a
              v-if="typeof row.requestLogUrl === 'string'"
              class="text-link"
              :href="row.requestLogUrl"
              target="_blank"
              rel="noopener noreferrer"
              >查询日志与链路</a
            ><button v-if="editable(row)" class="text-link" :disabled="busy" @click="edit(row)">
              编辑</button
            ><button
              v-if="kind === 'dict-types'"
              class="text-link"
              @click="safeAction(row, 'items')"
            >
              字典项</button
            ><button
              v-if="
                ['dict-types', 'dict-items'].includes(kind) &&
                !row.systemItem &&
                session.can('dict:delete')
              "
              class="text-link"
              :disabled="busy"
              @click="safeAction(row, 'delete')"
            >
              删除</button
            ><template v-if="kind === 'notices'"
              ><button class="text-link" @click="safeAction(row, 'view')">查看</button
              ><button
                v-if="session.can('notice:publish') && ['draft', 'published'].includes(row.status)"
                class="text-link"
                :disabled="busy"
                @click="safeAction(row, row.status === 'draft' ? 'publish' : 'withdraw')"
              >
                {{ row.status === 'draft' ? '发布' : '撤回' }}
              </button></template
            ><template v-if="kind === 'jobs'"
              ><button
                v-if="session.can('job:run')"
                class="text-link"
                :disabled="busy"
                @click="safeAction(row, 'run')"
              >
                执行一次</button
              ><button class="text-link" @click="safeAction(row, 'logs')">
                执行记录
              </button></template
            ><button
              v-if="kind === 'online-users' && session.can('online_user:kick')"
              class="text-link"
              :disabled="busy"
              @click="safeAction(row, 'kick')"
            >
              强制下线</button
            ><template v-if="kind === 'api-error-logs'"
              ><button class="text-link" @click="safeAction(row, 'view')">定位详情</button
              ><button
                v-if="session.can('api_error:update')"
                class="text-link"
                :disabled="busy"
                @click="safeAction(row, 'handled')"
              >
                {{ row.handled ? '标记未处理' : '标记已处理' }}
              </button></template
            >
          </div></template
        ></el-table-column
      ></el-table
    >
    <div v-if="!spec.list" class="pagination">
      <el-button :disabled="busy || cursors.length === 1" @click="paginate(false)">上一页</el-button
      ><el-button :disabled="busy || !next" @click="paginate(true)">下一页</el-button>
    </div>
  </section>
  <RecordFrame
    v-if="editor"
    :title="(selected ? '编辑' : '新增') + spec.title"
    :busy="busy"
    width="min(720px, 94vw)"
    @close="closeEditor"
    ><DataFields
      :fields="fields"
      :values="values"
      :options="options"
      :editing="Boolean(selected)"
      :disabled="busy || uncertain"
      @change="(key, value) => (values[key] = value)"
    /><template v-if="kind === 'notices'"
      ><el-form label-position="top" :disabled="busy || uncertain"
        ><el-form-item label="发布对象"
          ><el-select v-model="audience" @change="audienceChanged"
            ><el-option
              v-for="item in choices([
                ['all', '企业全部用户'],
                ['company', '指定公司'],
                ['department', '指定部门'],
                ['role', '指定角色'],
                ['user', '指定用户'],
              ])"
              :key="item.value"
              :value="item.value"
              :label="item.label" /></el-select></el-form-item
        ><el-form-item v-if="audience !== 'all'" label="选择收件范围"
          ><el-select v-model="targetIds" multiple filterable
            ><el-option
              v-for="item in audienceOptions"
              :key="item.value"
              :value="item.value"
              :label="item.label" /></el-select></el-form-item
        ><el-form-item label="定时发布（留空立即发布）"
          ><el-input v-model="publishAt" type="datetime-local" /></el-form-item
        ><el-form-item label="到期时间（可选）"
          ><el-input v-model="expireAt" type="datetime-local" /></el-form-item></el-form
    ></template>
    <p v-if="kind === 'jobs'" class="muted">
      计划示例：每分钟 interval:60000；每天03:00使用 0 0 3 * * *，按北京时间执行。
    </p>
    <p v-if="uncertain" class="muted">
      请求结果尚未确认。表单已冻结，请保留当前窗口，使用原请求重试。
    </p>
    <p v-if="readonlyPreset" class="muted">系统预置字典项只允许启用或停用。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" /><template #footer
      ><el-button :disabled="busy" @click="closeEditor">取消</el-button
      ><el-button type="primary" :loading="busy" @click="save">保存</el-button></template
    ></RecordFrame
  >
  <el-dialog
    :model-value="Boolean(runLogs)"
    title="任务执行记录"
    width="min(1000px, 96vw)"
    @close="runLogs = undefined"
    ><el-table :data="runLogs"
      ><el-table-column prop="id" label="执行编号" min-width="200" /><el-table-column label="结果"
        ><template #default="{ row }">{{ cell(row, 'status') }}</template></el-table-column
      ><el-table-column prop="message" label="说明" min-width="200" /><el-table-column
        label="时间"
        min-width="180"
        ><template #default="{ row }">{{ cell(row, 'createdAt') }}</template></el-table-column
      ></el-table
    ></el-dialog
  >
  <el-dialog
    :model-value="Boolean(detail)"
    :title="String(detail?.title ?? '异常定位')"
    width="min(760px, 94vw)"
    @close="detail = undefined"
    ><article v-if="kind === 'notices'" class="notice-content" v-html="detail?.content" />
    <template v-else
      ><p>请求号：{{ detail?.requestId }}</p>
      <p>{{ detail?.errorClass }}</p>
      <pre class="safe-stack">{{ detail?.stackFrames }}</pre>
    </template></el-dialog
  >
</template>
