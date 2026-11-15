<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { allRecords } from '../api/admin'
import { auditLogs, outboxEvents, replayEvent } from '../api/audit'
import type { AuditRow, OutboxRow } from '../api/audit'
import type { DataRow } from '../types/admin'
import { isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import { useSessionStore } from '../stores/session'
import { formatDateTimeSeconds } from '../utils/format'
import { auditChanges } from '../utils/audit'
const session = useSessionStore(),
  tab = ref<'audit' | 'events'>(session.can('audit:view') ? 'audit' : 'events')
const audit = ref<AuditRow[]>([]),
  events = ref<OutboxRow[]>([]),
  users = ref<DataRow[]>([]),
  selected = ref<AuditRow>()
const error = ref(''),
  loading = ref(false),
  busy = ref(false),
  action = ref(''),
  entityType = ref(''),
  entityId = ref(''),
  operatorId = ref(''),
  companyId = ref(''),
  requestId = ref(''),
  dateFrom = ref(''),
  dateTo = ref(''),
  status = ref('dead')
const cursors = ref<(string | null)[]>([null]),
  next = ref<string | null>(null),
  keys = new Map<string, string>()
const objects: Record<string, string> = {
  sys_user: '用户',
  sys_role: '角色',
  partner_contact: '联系人',
  cash_account: '资金账户',
  unit: '单位',
  product_category: '商品分类',
  product: '商品目录',
  income_expense_item: '收支项目',
  accounting_period: '会计期间',
  company: '公司',
  company_policy: '公司政策',
  warehouse: '仓库',
  sku: '商品 SKU',
  partner: '往来单位',
  sales_order: '销售订单',
  shipment: '发货单',
  sales_return: '销售退货',
  receipt: '收款单',
  refund_request: '退款申请',
  invoice_registration: '开票登记',
  purchase_order: '采购订单',
  purchase_requisition: '请购单',
  goods_receipt: '采购收货',
  purchase_return: '采购退货',
  payment: '付款单',
  account_transfer: '账户互转',
  stock_adjustment: '库存调整',
  opening_entry: '期初',
  stock_count: '盘点单',
  stock_transfer: '调拨单',
  document_reversal: '冲销单',
  attachment: '附件',
  document: '单据',
  export_job: '导出任务',
  import_job: '导入任务',
  period: '期间',
  outbox_event: '后台事件',
}
const verbs: Record<string, string> = {
  create: '新增',
  update: '修改',
  delete: '删除',
  enable: '启用',
  disable: '停用',
  submit: '提交',
  withdraw: '撤回',
  approve: '审核',
  reject: '驳回',
  post: '过账',
  sign: '签收',
  resolve_diff: '处理差异',
  confirm: '确认',
  print: '打印',
  upload: '上传',
  download: '下载',
  close: '关账',
  reopen: '反关账',
  replay: '重放',
  ship: '调出',
  receive: '调入',
  void: '作废',
  record_counts: '录入实盘',
  start: '开始盘点',
  assign_role: '分配角色',
  assign_scope: '分配范围',
  assign_permission: '分配权限',
  reset_password: '重置密码',
}
function operation(value: string): string {
  const [object, verb] = value.split('.')
  return (
    (objects[
      (
        {
          user: 'sys_user',
          role: 'sys_role',
          reversal: 'document_reversal',
          outbox: 'outbox_event',
        } as Record<string, string>
      )[object ?? ''] ??
        object ??
        ''
    ] ??
      object ??
      '') +
    ' · ' +
    (verbs[verb ?? ''] ?? verb ?? '')
  )
}
const changes = computed(() =>
  auditChanges(selected.value?.before, selected.value?.after, selected.value?.entityType),
)
let revision = 0
async function load(): Promise<void> {
  const current = ++revision
  loading.value = true
  error.value = ''
  const params = { cursor: cursors.value.at(-1) ?? undefined, limit: 20 }
  try {
    if (tab.value === 'audit') {
      const page = await auditLogs({
        ...params,
        action: action.value || undefined,
        entityType: entityType.value || undefined,
        entityId: entityId.value || undefined,
        operatorId: operatorId.value || undefined,
        companyId: companyId.value || undefined,
        requestId: requestId.value || undefined,
        dateFrom: dateFrom.value || undefined,
        dateTo: dateTo.value || undefined,
      })
      if (current === revision) {
        audit.value = page.items
        next.value = page.nextCursor
      }
    } else {
      const page = await outboxEvents({ ...params, status: status.value })
      if (current === revision) {
        events.value = page.items
        next.value = page.nextCursor
      }
    }
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
}
function search(): void {
  cursors.value = [null]
  next.value = null
  audit.value = []
  events.value = []
  selected.value = undefined
  void load()
}
function page(forward: boolean): void {
  if (forward && next.value) cursors.value.push(next.value)
  else if (!forward) cursors.value.pop()
  void load()
}
async function replay(row: OutboxRow): Promise<void> {
  if (busy.value) return
  const key = keys.get(row.id) ?? newIdempotencyKey()
  keys.set(row.id, key)
  busy.value = true
  error.value = ''
  try {
    await replayEvent(row.id, key)
    keys.delete(row.id)
    await load()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
async function loadUsers(): Promise<void> {
  if (!session.can('user:view')) return
  try {
    users.value = await allRecords('users')
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = '操作人列表未加载，可刷新后重试'
  }
}
watch(() => [tab.value, session.companyId], search, { immediate: true })
void loadUsers()
onBeforeUnmount(() => {
  ++revision
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>{{ tab === 'audit' ? '操作审计' : '异常事件处理' }}</h1>
      <p>公司记录按完整公司数据范围显示，快照按当前字段权限过滤。</p>
    </div>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <section class="panel table-panel">
    <el-radio-group v-model="tab" aria-label="审计与事件"
      ><el-radio-button v-if="session.can('audit:view')" value="audit">操作审计</el-radio-button
      ><el-radio-button v-if="session.can('admin:outbox') && session.company" value="events"
        >后台事件</el-radio-button
      ></el-radio-group
    >
    <form v-if="tab === 'audit'" class="filters audit-filters" @submit.prevent="search">
      <div class="filter-field">
        <label for="audit-object">业务对象</label
        ><el-select id="audit-object" v-model="entityType" filterable clearable
          ><el-option v-for="(name, value) in objects" :key="value" :value="value" :label="name"
        /></el-select>
      </div>
      <div v-if="users.length" class="filter-field">
        <label for="audit-operator">操作人</label
        ><el-select id="audit-operator" v-model="operatorId" clearable filterable
          ><el-option
            v-for="user in users"
            :key="user.id"
            :value="user.id"
            :label="String(user.displayName ?? user.username)"
        /></el-select>
      </div>
      <div class="filter-field">
        <label for="audit-company">公司</label
        ><el-select id="audit-company" v-model="companyId" clearable
          ><el-option
            v-for="company in session.user?.companies"
            :key="company.id"
            :value="company.id"
            :label="company.name"
        /></el-select>
      </div>
      <div class="filter-field">
        <label for="audit-from">开始日期</label
        ><input id="audit-from" v-model="dateFrom" type="date" />
      </div>
      <div class="filter-field">
        <label for="audit-to">结束日期</label><input id="audit-to" v-model="dateTo" type="date" />
      </div>
      <div class="filter-field">
        <label for="audit-entity-id">对象编号</label
        ><el-input id="audit-entity-id" v-model="entityId" />
      </div>
      <div class="filter-field">
        <label for="audit-request">请求编号</label
        ><el-input id="audit-request" v-model="requestId" maxlength="64" />
      </div>
      <div class="filter-field">
        <label for="audit-action">操作代码</label
        ><el-input
          id="audit-action"
          v-model="action"
          placeholder="例如 shipment.sign"
          maxlength="64"
        />
      </div>
      <el-button native-type="submit" :loading="loading">查询</el-button>
    </form>
    <form v-else class="filters audit-filters" @submit.prevent="search">
      <div class="filter-field">
        <label for="event-status">事件状态</label
        ><el-select id="event-status" v-model="status"
          ><el-option value="dead" label="失败待处理" /><el-option
            value="pending"
            label="等待执行" /><el-option value="processing" label="正在执行" /><el-option
            value="done"
            label="已完成"
        /></el-select>
      </div>
      <el-button native-type="submit" :loading="loading">查询</el-button>
      <p>仅允许重放当前公司的失败事件，业务消费通过事件去重防止重复记账。</p>
    </form>
    <el-table v-if="tab === 'audit'" :data="audit" row-key="id"
      ><el-table-column label="操作" min-width="180"
        ><template #default="{ row }">{{ operation(row.action) }}</template></el-table-column
      ><el-table-column prop="companyName" label="公司" min-width="140" /><el-table-column
        label="操作人"
        min-width="120"
        ><template #default="{ row }">{{
          row.operatorName ?? (row.operatorId ? '已删除用户' : '系统任务')
        }}</template></el-table-column
      ><el-table-column label="时间" min-width="170"
        ><template #default="{ row }">{{
          formatDateTimeSeconds(row.createdAt)
        }}</template></el-table-column
      ><el-table-column prop="requestId" label="请求编号" min-width="200" /><el-table-column
        label="详情"
        ><template #default="{ row }"
          ><el-button text @click="selected = row">查看变更</el-button></template
        ></el-table-column
      ><template #empty>{{
        loading ? '正在查询…' : error ? '查询失败，请重试' : '暂无可查看的审计记录'
      }}</template></el-table
    >
    <el-table v-else :data="events" row-key="id"
      ><el-table-column prop="id" label="事件编号" min-width="200" /><el-table-column
        label="事件类型"
        min-width="180"
        ><template #default="{ row }">{{
          row.eventType === 'journal.posted'
            ? '凭证过账投影'
            : row.eventType === 'reconciliation.requested'
              ? '对账任务'
              : row.eventType
        }}</template></el-table-column
      ><el-table-column prop="attempts" label="尝试次数" /><el-table-column
        prop="lastError"
        label="处理提示"
        min-width="220"
      /><el-table-column label="处理"
        ><template #default="{ row }"
          ><el-popconfirm
            v-if="row.status === 'dead'"
            title="确认重新执行该失败事件？"
            confirm-button-text="重放"
            cancel-button-text="取消"
            @confirm="replay(row)"
            ><template #reference
              ><el-button text :disabled="busy">重放事件</el-button></template
            ></el-popconfirm
          ></template
        ></el-table-column
      ><template #empty>{{ loading ? '正在查询…' : '当前状态暂无后台事件' }}</template></el-table
    >
    <footer class="table-footer">
      <span>第 {{ cursors.length }} 页</span>
      <div>
        <el-button :disabled="loading || cursors.length === 1" @click="page(false)"
          >上一页</el-button
        ><el-button :disabled="loading || !next" @click="page(true)">下一页</el-button>
      </div>
    </footer>
  </section>
  <section v-if="selected" class="panel audit-comparison" aria-label="审计变更详情">
    <div class="page-heading">
      <h2>变更详情</h2>
      <el-button text @click="selected = undefined">关闭详情</el-button>
    </div>
    <p>{{ operation(selected.action) }} · 对象编号 {{ selected.entityId ?? '—' }}</p>
    <p v-if="selected.reason">原因：{{ selected.reason }}</p>
    <p>
      时间：{{ formatDateTimeSeconds(selected.createdAt) }} · 请求编号：{{
        selected.requestId ?? '—'
      }}
    </p>
    <table class="audit-diff">
      <thead>
        <tr>
          <th>字段</th>
          <th>修改前</th>
          <th>修改后</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in changes" :key="row.field" :class="{ changed: row.changed }">
          <th>{{ row.label }}</th>
          <td>{{ row.before }}</td>
          <td>{{ row.after }}</td>
        </tr>
      </tbody>
    </table>
    <p v-if="!changes.length">本次操作没有可展示的字段变更。</p>
  </section>
</template>
<style scoped>
.el-radio-group {
  margin: 20px 24px 0;
}
.audit-filters {
  margin-top: 0;
}
.audit-comparison {
  padding: 20px 24px;
  margin-top: 20px;
}
.audit-comparison h2 {
  font-size: 18px;
  margin: 0;
}
.audit-diff {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}
.audit-diff th,
.audit-diff td {
  text-align: left;
  padding: 10px 14px;
  border-bottom: 1px solid var(--el-border-color-light);
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.audit-diff th {
  font-weight: 500;
}
.audit-diff tr.changed td:last-child {
  background: #edf5ff;
  color: #164fbd;
}
</style>
