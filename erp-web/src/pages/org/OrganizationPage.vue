<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs'
import { ElTree } from 'element-plus/es/components/tree/index.mjs'
import {
  companyUsers,
  departmentMembers,
  departments,
  departmentStatus,
  moveDepartment,
} from '../../api/org'
import { isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import { confirmCompanyLeave, registerCompanyLeaveGuard } from '../../utils/unsaved'
import { departmentTree } from '../../types/org'
import type { Department, DepartmentNode, OrgUser } from '../../types/org'
import DepartmentEditor from '../../components/org/DepartmentEditor.vue'
import UserOrganizationEditor from '../../components/org/UserOrganizationEditor.vue'
import ResourcePage from '../ResourcePage.vue'

const session = useSessionStore()
const tab = ref(session.can('org_department:view') ? 'departments' : 'posts')
const rows = ref<Department[]>([]),
  selectedId = ref(''),
  loading = ref(false),
  busy = ref(false),
  error = ref(''),
  notice = ref('')
const tree = computed(() => departmentTree(rows.value))
const selected = computed(() => rows.value.find((row) => row.id === selectedId.value))
const editor = ref(false),
  edited = ref<Department>(),
  editorParent = ref('0'),
  editorRevision = ref(0)
const editorDirty = ref(false),
  assignmentDirty = ref(false),
  editorBusy = ref(false),
  assignmentBusy = ref(false)
const members = ref<OrgUser[]>([]),
  memberCursor = ref<string | null>(null),
  membersBusy = ref(false),
  userId = ref(''),
  userOptions = ref<OrgUser[]>([])
const blocked = computed(() => busy.value || editorBusy.value || assignmentBusy.value)
let revision = 0,
  memberRevision = 0,
  searchRevision = 0,
  controller = new AbortController()
function canLeave(): boolean {
  if (blocked.value) return false
  return (
    !(editorDirty.value || assignmentDirty.value) ||
    window.confirm('修改尚未保存，确定放弃这些修改？')
  )
}
const unregister = registerCompanyLeaveGuard(canLeave)
onBeforeRouteLeave(canLeave)
function beforeUnload(event: BeforeUnloadEvent): void {
  if (blocked.value || editorDirty.value || assignmentDirty.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
function selectTab(next: string): void {
  if (next !== tab.value && confirmCompanyLeave()) {
    tab.value = next
    editor.value = false
    userId.value = ''
    editorDirty.value = assignmentDirty.value = false
  }
}
async function load(): Promise<void> {
  const current = ++revision
  controller.abort()
  controller = new AbortController()
  error.value = ''
  rows.value = []
  members.value = []
  memberCursor.value = null
  if (!session.companyId || !session.can('org_department:view')) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const result = await departments(controller.signal)
    if (current !== revision) return
    rows.value = result
    if (!result.some((row) => row.id === selectedId.value)) selectedId.value = result[0]?.id ?? ''
    await loadMembers(false)
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
}
async function select(row: Department): Promise<void> {
  if (!canLeave()) return
  editor.value = false
  editorDirty.value = false
  assignmentDirty.value = false
  userId.value = ''
  selectedId.value = row.id
  await loadMembers(false)
}
async function loadMembers(more: boolean): Promise<void> {
  const current = ++memberRevision,
    departmentId = selectedId.value
  if (!more) {
    members.value = []
    memberCursor.value = null
  }
  if (!departmentId) return
  membersBusy.value = true
  try {
    const result = await departmentMembers(
      departmentId,
      more ? (memberCursor.value ?? undefined) : undefined,
      controller.signal,
    )
    if (current !== memberRevision) return
    members.value = more ? [...members.value, ...result.items] : result.items
    memberCursor.value = result.nextCursor
  } catch (cause: unknown) {
    if (current === memberRevision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === memberRevision) membersBusy.value = false
  }
}
function openEditor(row?: Department): void {
  if (!canLeave()) return
  edited.value = row
  editorParent.value = row?.parentId ?? (selectedId.value || '0')
  editor.value = true
  userId.value = ''
  editorDirty.value = assignmentDirty.value = false
  editorRevision.value++
}
function closeEditor(): void {
  if (canLeave()) {
    editor.value = false
    editorDirty.value = false
  }
}
async function saved(row: Department): Promise<void> {
  editor.value = false
  editorDirty.value = false
  selectedId.value = row.id
  notice.value = '部门已保存'
  await load()
}
function reload(): void {
  if (canLeave()) {
    editor.value = false
    userId.value = ''
    editorDirty.value = assignmentDirty.value = false
    void load()
  }
}
async function assigned(): Promise<void> {
  const current = revision
  try {
    const result = await departments(controller.signal)
    if (current === revision) {
      rows.value = result
      await loadMembers(false)
    }
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  }
}
async function changeStatus(row: Department): Promise<void> {
  if (!canLeave()) return
  try {
    await ElMessageBox.confirm(
      '确定' + (row.status === 'active' ? '停用' : '启用') + '部门“' + row.name + '”？',
      '确认部门状态',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' },
    )
  } catch (cause: unknown) {
    if (cause !== 'cancel' && cause !== 'close') error.value = messageOf(cause)
    return
  }
  busy.value = true
  error.value = ''
  try {
    await departmentStatus(row, newIdempotencyKey())
    notice.value = '部门状态已更新'
    await load()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
function nodeData(node: unknown): DepartmentNode {
  return (node as { data: DepartmentNode }).data
}
async function drop(
  drag: unknown,
  target: unknown,
  position: 'before' | 'after' | 'inner',
): Promise<void> {
  const row = nodeData(drag),
    destination = nodeData(target)
  const parentId = position === 'inner' ? destination.id : destination.parentId
  const siblings = rows.value.filter((item) => item.parentId === parentId && item.id !== row.id)
  const index = siblings.findIndex((item) => item.id === destination.id)
  const beforeId =
    position === 'before'
      ? destination.id
      : position === 'after'
        ? (siblings[index + 1]?.id ?? null)
        : null
  try {
    await ElMessageBox.confirm('移动“' + row.name + '”及其下级部门，并更新排序？', '确认移动部门', {
      confirmButtonText: '确认移动',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch (cause: unknown) {
    await load()
    if (cause !== 'cancel' && cause !== 'close') error.value = messageOf(cause)
    return
  }
  busy.value = true
  error.value = ''
  try {
    await moveDepartment(row.id, parentId, beforeId, row.version, newIdempotencyKey())
    notice.value = '部门已移动'
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
    await load()
  }
}
async function searchUsers(keyword: string): Promise<void> {
  const current = ++searchRevision
  try {
    const result = await companyUsers(keyword, undefined, controller.signal)
    if (current === searchRevision) userOptions.value = result.items
  } catch (cause: unknown) {
    if (current === searchRevision && !isCancelled(cause)) error.value = messageOf(cause)
  }
}
function usersDropdown(visible: boolean): void {
  if (visible) void searchUsers('')
}
async function openAssignment(user?: OrgUser): Promise<void> {
  if (!canLeave()) return
  editor.value = false
  editorDirty.value = assignmentDirty.value = false
  if (user && !userOptions.value.some((item) => item.id === user.id)) userOptions.value.push(user)
  userId.value = user?.id ?? ''
  await searchUsers('')
}
watch(
  () => session.companyId,
  () => {
    ++memberRevision
    ++searchRevision
    editor.value = false
    userId.value = ''
    userOptions.value = []
    selectedId.value = ''
    editorDirty.value = assignmentDirty.value = false
    notice.value = ''
    void load()
  },
  { immediate: true },
)
onScopeDispose(() => {
  ++revision
  ++memberRevision
  ++searchRevision
  controller.abort()
  unregister()
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <section class="organization-page">
    <header class="page-heading">
      <div>
        <h1>部门与岗位</h1>
        <p class="muted">部门与任职按当前公司维护，岗位定义在企业内共享。</p>
      </div>
      <el-button v-if="tab === 'departments'" :disabled="blocked || loading" @click="reload"
        >刷新组织资料</el-button
      >
    </header>
    <div class="relation-tabs" role="tablist" aria-label="组织管理分区">
      <button
        v-if="session.can('org_department:view')"
        type="button"
        role="tab"
        :aria-selected="tab === 'departments'"
        @click="selectTab('departments')"
      >
        部门
      </button>
      <button
        v-if="session.can('org_post:view')"
        type="button"
        role="tab"
        :aria-selected="tab === 'posts'"
        @click="selectTab('posts')"
      >
        岗位
      </button>
    </div>
    <ResourcePage v-if="tab === 'posts'" resource="org/posts" />
    <template v-else>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-alert v-if="notice" :title="notice" type="success" :closable="false" />
      <p v-if="!session.companyId" class="panel empty-state">请先选择可访问的公司。</p>
      <p v-else-if="loading" role="status" class="muted">正在加载部门与成员…</p>
      <div v-else class="organization-layout">
        <aside class="panel department-tree-panel" aria-label="部门树">
          <div class="panel-heading">
            <h2>公司部门</h2>
            <el-button
              v-if="session.can('org_department:create')"
              :disabled="blocked"
              @click="openEditor()"
              >新建部门</el-button
            >
          </div>
          <p v-if="!rows.length" class="muted empty-state">当前公司尚未设置部门。</p>
          <el-tree
            v-else
            :data="tree"
            :props="{ label: 'name' }"
            node-key="id"
            default-expand-all
            highlight-current
            :current-node-key="selectedId"
            :expand-on-click-node="false"
            :draggable="
              session.can('org_department:update') && !blocked && !editorDirty && !assignmentDirty
            "
            @node-click="select"
            @node-drop="drop"
          >
            <template #default="{ data }"
              ><span
                >{{ data.name
                }}<span v-if="data.status === 'disabled'" class="muted">（停用）</span></span
              ></template
            >
          </el-tree>
          <p v-if="session.can('org_department:update') && rows.length" class="muted tree-help">
            拖拽调整上级或同级排序，确认后保存。
          </p>
        </aside>
        <div class="organization-detail">
          <DepartmentEditor
            v-if="editor"
            :key="editorRevision"
            :row="edited"
            :parent-id="editorParent"
            :departments="rows"
            @close="closeEditor"
            @saved="saved"
            @dirty="editorDirty = $event"
            @pending="editorBusy = $event"
          />
          <section v-else-if="selected" class="panel department-detail" aria-label="部门详情">
            <div class="panel-heading">
              <div>
                <h2>{{ selected.name }}</h2>
                <span class="muted"
                  >{{ selected.code }} · {{ selected.status === 'active' ? '启用' : '停用' }}</span
                >
              </div>
              <div v-if="session.can('org_department:update')" class="detail-actions">
                <el-button :disabled="blocked" @click="openEditor(selected)">编辑部门</el-button
                ><el-button :disabled="blocked" @click="changeStatus(selected)">{{
                  selected.status === 'active' ? '停用部门' : '启用部门'
                }}</el-button>
              </div>
            </div>
            <dl class="department-facts">
              <div>
                <dt>部门负责人</dt>
                <dd>{{ selected.leaderName ?? '未设置' }}</dd>
              </div>
              <div>
                <dt>成员人数</dt>
                <dd>{{ selected.memberCount }}</dd>
              </div>
              <div>
                <dt>当前层级</dt>
                <dd>{{ selected.depth }}</dd>
              </div>
            </dl>
            <div class="panel-heading">
              <h3>部门成员</h3>
              <el-button
                v-if="session.can('user:update')"
                :disabled="blocked"
                @click="openAssignment()"
                >配置成员部门与岗位</el-button
              >
            </div>
            <p v-if="membersBusy" role="status">正在加载部门成员…</p>
            <el-table :data="members" :aria-busy="membersBusy" empty-text="当前部门暂无成员"
              ><el-table-column prop="displayName" label="姓名" /><el-table-column
                prop="username"
                label="用户名"
              /><el-table-column label="状态"
                ><template #default="{ row }">{{
                  row.status === 'active' ? '启用' : '停用'
                }}</template></el-table-column
              ><el-table-column v-if="session.can('user:update')" label="操作"
                ><template #default="{ row }"
                  ><el-button text :disabled="blocked" @click="openAssignment(row)"
                    >配置任职</el-button
                  ></template
                ></el-table-column
              ></el-table
            >
            <el-button v-if="memberCursor" :loading="membersBusy" @click="loadMembers(true)"
              >加载更多成员</el-button
            >
          </section>
          <section v-else-if="!editor" class="panel empty-state">
            选择部门查看资料，或新建第一个部门。
          </section>
          <section
            v-if="session.can('user:update') && !editor"
            class="panel member-assignment"
            aria-label="公司用户任职配置"
          >
            <el-form-item label="配置公司用户" for="organization-user"
              ><el-select
                id="organization-user"
                v-model="userId"
                filterable
                remote
                :remote-method="searchUsers"
                :disabled="blocked || assignmentDirty"
                placeholder="输入姓名或用户名搜索"
                @visible-change="usersDropdown"
                ><el-option
                  v-for="user in userOptions"
                  :key="user.id"
                  :value="user.id"
                  :label="user.displayName + '（' + user.username + '）'" /></el-select
            ></el-form-item>
            <UserOrganizationEditor
              v-if="userId"
              :key="session.companyId + ':' + userId"
              :user-id="userId"
              @dirty="assignmentDirty = $event"
              @pending="assignmentBusy = $event"
              @saved="assigned"
            />
          </section>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.page-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 20px;
  margin-bottom: 20px;
}
.page-heading h1 {
  margin: 0 0 8px;
}
.organization-layout {
  display: grid;
  grid-template-columns: minmax(240px, 300px) minmax(0, 1fr);
  gap: 20px;
  margin-top: 20px;
  align-items: start;
}
.department-tree-panel,
.department-detail,
.member-assignment {
  padding: 20px;
}
.organization-detail {
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 20px;
}
.department-detail,
.member-assignment {
  min-width: 0;
}
.panel-heading {
  flex-wrap: wrap;
  gap: 12px;
}
.relation-tabs {
  display: flex;
  gap: 24px;
  border-bottom: 1px solid var(--el-border-color-light);
}
.relation-tabs button {
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  padding: 12px 0;
  font: inherit;
  color: var(--el-text-color-secondary);
  cursor: pointer;
}
.relation-tabs button[aria-selected='true'] {
  color: var(--el-color-primary);
  border-bottom-color: var(--el-color-primary);
}
.department-tree-panel h2,
.department-detail h2 {
  margin: 0;
  font-size: 16px;
}
.department-tree-panel .el-tree {
  margin-top: 16px;
}
.tree-help {
  font-size: 12px;
  margin: 20px 0 0;
}
.detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.department-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 32px;
  margin: 24px 0;
}
.department-facts dt {
  color: var(--erp-text-secondary, #64748b);
  font-size: 12px;
}
.department-facts dd {
  margin: 8px 0 0;
}
.empty-state {
  padding: 32px 20px;
}
.member-assignment .el-select {
  width: 100%;
}
@media (max-width: 860px) {
  .organization-layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .page-heading {
    flex-direction: column;
  }
}
</style>
