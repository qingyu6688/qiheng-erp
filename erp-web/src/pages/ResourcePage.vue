<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { records, record, writeRecord } from '../api/admin'
import { isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import { useSessionStore } from '../stores/session'
import { flattenRows } from '../utils/admin'
import type { DataRow } from '../types/admin'
import { resourceSpecs } from './resources'
import RecordEditor from '../components/RecordEditor.vue'
import RecordRelations from '../components/RecordRelations.vue'
import PeriodActions from '../components/PeriodActions.vue'

const props = defineProps<{ resource: string }>()
const spec = computed(() => resourceSpecs[props.resource]!)
const session = useSessionStore()
const rows = ref<DataRow[]>([])
const keyword = ref('')
const cursors = ref([''])
const nextCursor = ref<string | null>(null)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const editing = ref(false)
const selected = ref<DataRow>()
const relations = ref<DataRow>()
const statusRow = ref<DataRow>()
const statusKey = ref('')
const statusBusy = ref(false)
const createPermission = computed(() =>
  spec.value.permission === 'period' ? 'period:open' : spec.value.permission + ':create',
)
const columns = computed(() =>
  spec.value.columns.filter((c) => c.key !== 'accountNo' || session.can('cash_account_no:view')),
)
const showStatus = computed(() => !['audit-logs', 'accounting-periods'].includes(props.resource))

const labels: Record<string, string> = {
  active: '启用',
  disabled: '停用',
  locked: '锁定',
  open: '开放',
  closed: '已关闭',
  income: '收入',
  expense: '支出',
  customer: '客户',
  supplier: '供应商',
}
function display(row: DataRow, key: string): string {
  const value = row[key]
  if (value == null) return '—'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (Array.isArray(value))
    return value.map((item) => labels[String(item)] ?? String(item)).join('、')
  return labels[String(value)] ?? String(value)
}
function canEdit(row: DataRow): boolean {
  return (
    props.resource !== 'accounting-periods' &&
    props.resource !== 'audit-logs' &&
    session.can(spec.value.permission + ':update') &&
    (!row.allowedActions || row.allowedActions.includes('update'))
  )
}
async function load(): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    const data = await records(spec.value.path, spec.value.company, {
      keyword: keyword.value || undefined,
      limit: 20,
      cursor: cursors.value.at(-1) || undefined,
    })
    rows.value = props.resource === 'product-categories' ? flattenRows(data.items) : data.items
    nextCursor.value = data.nextCursor
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
function search(): void {
  cursors.value = ['']
  void load()
}
function paginate(next: boolean): void {
  if (next && nextCursor.value) cursors.value.push(nextCursor.value)
  else if (!next && cursors.value.length > 1) cursors.value.pop()
  void load()
}
async function edit(row?: DataRow): Promise<void> {
  try {
    selected.value =
      row && spec.value.detail
        ? await record(spec.value.path + '/' + row.id, spec.value.company)
        : row
    editing.value = true
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  }
}
async function saved(): Promise<void> {
  editing.value = false
  notice.value = '资料已保存'
  try {
    await session.refresh()
    await load()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  }
}
async function relationsSaved(): Promise<void> {
  try {
    await session.refresh()
    await load()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  }
}
function statusChange(row: DataRow): void {
  statusRow.value = row
  statusKey.value = newIdempotencyKey()
}
async function confirmStatus(): Promise<void> {
  if (!statusRow.value) return
  statusBusy.value = true
  try {
    await writeRecord(
      spec.value.path +
        '/' +
        statusRow.value.id +
        '/' +
        (statusRow.value.status === 'active' ? 'disable' : 'enable'),
      { version: statusRow.value.version },
      'POST',
      spec.value.company,
      statusKey.value,
    )
    statusRow.value = undefined
    await session.refresh()
    await load()
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    statusBusy.value = false
  }
}
function hasRelations(row: DataRow): boolean {
  return (
    (props.resource === 'companies' && session.can('company_policy:view')) ||
    (props.resource === 'users' &&
      ['user:assign_role', 'user:assign_scope', 'user:reset_password'].some(session.can)) ||
    (props.resource === 'roles' && session.can('role:assign_permission')) ||
    (props.resource === 'partners' && session.can('partner:view')) ||
    (props.resource === 'skus' && session.can('sku:update') && row.status === 'active')
  )
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <h1>{{ spec.title }}</h1>
    <el-button v-if="session.can(createPermission)" type="primary" @click="edit()"
      >新建{{ spec.singular }}</el-button
    >
  </div>
  <section class="panel table-panel" :aria-busy="busy">
    <form class="filters" @submit.prevent="search">
      <div class="filter-field">
        <label for="resource-search">名称或编码</label
        ><el-input id="resource-search" v-model="keyword" maxlength="100" clearable />
      </div>
      <el-button native-type="submit" :loading="busy">查询</el-button>
    </form>
    <el-alert
      v-if="error"
      class="list-notice"
      type="error"
      :title="error"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="notice"
      class="list-notice"
      type="success"
      :title="notice"
      show-icon
      @close="notice = ''"
    />
    <el-table :data="rows" row-key="id">
      <el-table-column
        v-for="column in columns"
        :key="column.key"
        :label="column.label"
        :min-width="column.key.endsWith('Id') ? 180 : 130"
      >
        <template #default="{ row }">{{ display(row, column.key) }}</template>
      </el-table-column>
      <el-table-column
        v-if="showStatus || resource === 'accounting-periods'"
        label="状态"
        width="90"
      >
        <template #default="{ row }"
          ><el-tag :type="row.status === 'active' || row.status === 'open' ? 'success' : 'info'">{{
            display(row, 'status')
          }}</el-tag></template
        >
      </el-table-column>
      <el-table-column
        v-if="resource !== 'audit-logs'"
        label="操作"
        min-width="180"
      >
        <template #default="{ row }"
          ><PeriodActions v-if="resource === 'accounting-periods'" :period="row" @changed="load"/><div v-else class="row-actions">
            <button v-if="canEdit(row)" class="text-link" @click="edit(row)">编辑</button>
            <button
              v-if="
                spec.enable &&
                session.can(spec.permission + ':update') &&
                (!row.allowedActions ||
                  row.allowedActions.includes(row.status === 'active' ? 'disable' : 'enable'))
              "
              class="text-link"
              @click="statusChange(row)"
            >
              {{ row.status === 'active' ? '停用' : '启用' }}
            </button>
            <button v-if="hasRelations(row)" class="text-link" @click="relations = row">
              {{
                resource === 'companies'
                  ? '业务参数'
                  : resource === 'partners'
                    ? '联系人'
                    : resource === 'skus'
                      ? '单位与条码'
                      : '权限设置'
              }}
            </button>
          </div></template
        >
      </el-table-column>
      <template #empty
        ><div class="empty-state" role="status">
          {{ busy ? '正在加载…' : error ? '加载失败，请重新查询' : '暂无资料' }}
        </div></template
      >
    </el-table>
    <footer class="table-footer">
      <span>第 {{ cursors.length }} 页</span>
      <div>
        <el-button :disabled="busy || cursors.length === 1" @click="paginate(false)"
          >上一页</el-button
        >
        <el-button :disabled="busy || !nextCursor" @click="paginate(true)">下一页</el-button>
      </div>
    </footer>
  </section>
  <RecordEditor
    v-if="editing"
    :spec="spec"
    :row="selected"
    @close="editing = false"
    @saved="saved"
  />
  <RecordRelations
    v-if="relations"
    :resource="resource"
    :row="relations"
    @close="relations = undefined"
    @saved="relationsSaved"
  />
  <el-dialog
    :model-value="Boolean(statusRow)"
    title="确认变更状态"
    width="420px"
    :close-on-click-modal="false"
    @close="statusRow = undefined"
  >
    <p>
      确认{{ statusRow?.status === 'active' ? '停用' : '启用' }}「{{
        statusRow?.name ?? statusRow?.displayName
      }}」？
    </p>
    <template #footer
      ><el-button :disabled="statusBusy" @click="statusRow = undefined">取消</el-button
      ><el-button type="primary" :loading="statusBusy" @click="confirmStatus"
        >确认</el-button
      ></template
    >
  </el-dialog>
</template>
