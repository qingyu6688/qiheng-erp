<script setup lang="ts">
import { computed, inject, onScopeDispose, ref, watch } from 'vue'
import {
  ArrowRight,
  Box,
  Calendar,
  DataAnalysis,
  Document,
  Plus,
  Refresh,
  Setting,
  ShoppingCart,
  Van,
  Wallet,
} from '@element-plus/icons-vue'
import { workbenchAlertCount, workbenchSummary } from '../api/workbench'
import type { WorkbenchSummary } from '../api/workbench'
import { confirmAnnouncement, receivedNotice } from '../api/platform'
import type { ReceivedNotice } from '../api/platform'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'
import { formatMoney, formatQuantity, formatRelativeTime, formatDateTime } from '../utils/format'
import { documentLink } from '../types/reporting'
import {
  recentWorkPages,
  saveShortcuts,
  shortcutPaths,
  shortcuts,
  SHORTCUT_LIMIT,
  workbenchScope,
} from '../utils/workbench-preferences'
import WorkbenchTradeSummary from '../components/WorkbenchTradeSummary.vue'
import WorkbenchSetupGuide from '../components/WorkbenchSetupGuide.vue'

const shortcutIcons = new Map([
  ['/sales-orders/new', Plus],
  ['/purchase-orders/new', ShoppingCart],
  ['/receipts', Wallet],
  ['/inventory-balances', Box],
  ['/shipments', Van],
  ['/reports/sales-summary', DataAnalysis],
  ['/settlements', Wallet],
  ['/accounting-periods', Calendar],
])
const session = useSessionStore()
const navigationAllowed = inject<(path: string) => boolean>('erp.navigationAllowed', () => true)
function showTodos(): void {
  document.getElementById('workbench-todos')?.scrollIntoView({ block: 'start' })
}
const recent = inject(recentWorkPages, ref([]))
const value = ref<WorkbenchSummary>()
const error = ref('')
const loading = ref(false)
const refreshKey = ref(0)
const currentTab = ref('')
const alertCount = ref<number>()
const alertError = ref('')
const scope = computed(() => workbenchScope(session.user?.id, session.companyId))
const allowedShortcuts = computed(() =>
  shortcuts.filter((item) => navigationAllowed(item.path) && (!item.permission || session.can(item.permission))),
)
const customPaths = ref<string[]>()
const editingShortcuts = ref(false)
const selectedPaths = ref<string[]>([])
const preferencesError = ref('')
const notice = ref<ReceivedNotice>()
const noticeError = ref('')
const savingNotice = ref(false)
let revision = 0
let noticeRevision = 0
let controller = new AbortController()
const visibleShortcuts = computed(() => {
  if (!session.companyId) return []
  const allowed = allowedShortcuts.value
  return customPaths.value
    ? allowed.filter((item) => customPaths.value?.includes(item.path))
    : allowed.slice(0, SHORTCUT_LIMIT)
})
const tabs = computed(() => value.value?.blocks ?? [])
const activeTab = computed(() => tabs.value.find((item) => item.key === currentTab.value))
const tasks = computed(() => {
  const data = value.value
  const kind = activeTab.value?.key
  if (!data) return []
  if (kind)
    return data.todosByType?.[kind] ?? data.todos?.filter((item) => item.kind === kind) ?? []
  return data.todos ?? []
})
const total = computed(() => tabs.value.reduce((count, item) => count + item.count, 0))
const today = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  dateStyle: 'full',
}).format(new Date())

async function load(): Promise<void> {
  const current = ++revision
  controller.abort()
  controller = new AbortController()
  loading.value = false
  error.value = ''
  value.value = undefined
  alertCount.value = undefined
  alertError.value = ''
  if (!session.companyId) return
  loading.value = true
  const signal = controller.signal
  const alerts = session.can('alert:view') ? loadAlertCount(current, signal) : undefined
  try {
    const data = await workbenchSummary(signal)
    if (current === revision) value.value = data
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    await alerts
    if (current === revision) loading.value = false
  }
}

async function loadAlertCount(current: number, signal: AbortSignal): Promise<void> {
  try {
    const data = await workbenchAlertCount(signal)
    if (current === revision && session.can('alert:view')) alertCount.value = data.total
  } catch (cause: unknown) {
    if (current === revision && session.can('alert:view') && !isCancelled(cause))
      alertError.value = '未处理预警加载失败，可刷新后重试。'
  }
}

function editShortcuts(): void {
  selectedPaths.value = visibleShortcuts.value.map((item) => item.path)
  preferencesError.value = ''
  editingShortcuts.value = true
}

function savePreferences(): void {
  if (!scope.value) return
  const paths = allowedShortcuts.value
    .filter((item) => selectedPaths.value.includes(item.path))
    .map((item) => item.path)
  if (!saveShortcuts(scope.value, paths)) {
    preferencesError.value = '浏览器未允许保存快捷入口。'
    return
  }
  customPaths.value = paths
  editingShortcuts.value = false
}

async function openAnnouncement(id: string): Promise<void> {
  const current = ++noticeRevision
  noticeError.value = ''
  notice.value = undefined
  savingNotice.value = false
  try {
    const data = await receivedNotice(id)
    if (current === noticeRevision) notice.value = data
  } catch (cause: unknown) {
    if (current === noticeRevision && !isCancelled(cause)) noticeError.value = messageOf(cause)
  }
}

async function confirm(): Promise<void> {
  if (!notice.value || savingNotice.value) return
  const current = noticeRevision
  savingNotice.value = true
  noticeError.value = ''
  try {
    await confirmAnnouncement(notice.value.id)
    if (current === noticeRevision) {
      notice.value = undefined
      window.dispatchEvent(new Event('erp:messages-changed'))
    }
  } catch (cause: unknown) {
    if (current === noticeRevision && !isCancelled(cause)) noticeError.value = messageOf(cause)
  } finally {
    if (current === noticeRevision) savingNotice.value = false
  }
}

const setupGuide = ref<InstanceType<typeof WorkbenchSetupGuide>>()
function refresh(): void {
  ++refreshKey.value
  void load()
  void setupGuide.value?.load()
}

function workbenchTime(value?: string): string {
  if (!value) return '—'
  const minutes = Math.floor((Date.now() - Date.parse(value)) / 60_000)
  if (minutes >= 0 && minutes < 1) return '刚刚'
  if (minutes >= 1 && minutes < 60) return minutes + ' 分钟前'
  return formatRelativeTime(value)
}

watch(
  [() => session.companyId, () => session.user, () => session.can('alert:view')],
  () => {
    currentTab.value = ''
    void load()
  },
  { immediate: true },
)
watch(
  scope,
  (next) => {
    ++noticeRevision
    customPaths.value = shortcutPaths(next)
    editingShortcuts.value = false
    notice.value = undefined
    noticeError.value = ''
    savingNotice.value = false
  },
  { immediate: true },
)
function messagesChanged(): void {
  void load()
}
window.addEventListener('erp:messages-changed', messagesChanged)
onScopeDispose(() => {
  window.removeEventListener('erp:messages-changed', messagesChanged)
  ++noticeRevision
  ++revision
  controller.abort()
})
</script>

<template>
  <div class="page-heading workbench-heading">
    <div>
      <h1>工作台</h1>
      <p>
        {{ session.user?.displayName || session.user?.username || '您好' }}<span>·</span>{{ today }}
        <template v-if="value"
          ><span>·</span><a href="#workbench-todos" @click.prevent="showTodos">待办 {{ total }} 项</a></template
        >
      </p>
    </div>
    <el-button v-if="session.companyId" :icon="Refresh" :loading="loading" @click="refresh"
      >刷新</el-button
    >
  </div>
  <p v-if="!session.companyId" class="panel workbench-message">
    当前账户没有可访问的公司，请联系管理员配置数据范围。
  </p>
  <template v-else>
    <WorkbenchSetupGuide ref="setupGuide" :scope="scope" />
    <section class="panel workbench-shortcuts" aria-label="快捷入口">
      <div class="panel-heading">
        <h2>快捷入口</h2>
        <el-button v-if="scope" text :icon="Setting" @click="editShortcuts">配置</el-button>
      </div>
      <div v-if="visibleShortcuts.length" class="shortcut-grid">
        <router-link v-for="item in visibleShortcuts" :key="item.path" :to="item.path">
          <span class="shortcut-icon"
            ><component
              :is="shortcutIcons.get(item.path) ?? Document"
              class="icon"
              aria-hidden="true"
          /></span>
          {{ item.title }}
        </router-link>
      </div>
      <p v-else class="muted">暂无快捷入口</p>
    </section>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="alertError" :title="alertError" type="warning" :closable="false" />
    <WorkbenchTradeSummary :refresh-key="refreshKey">
      <div class="workbench-columns">
        <section
          id="workbench-todos"
          class="panel table-panel"
          aria-labelledby="workbench-todo-title"
        >
          <div class="panel-heading">
            <h2 id="workbench-todo-title">待办</h2>
            <router-link v-if="navigationAllowed('/workflow/todo')" class="text-link" to="/workflow/todo">进入审批中心</router-link>
          </div>
          <div class="todo-types" role="group" aria-label="待办类型">
            <button type="button" :aria-pressed="!activeTab" @click="currentTab = ''">
              全部 <b>{{ total }}</b>
            </button>
            <button
              v-for="tab in tabs"
              :key="tab.key"
              type="button"
              :aria-pressed="activeTab?.key === tab.key"
              :class="{ 'is-empty': tab.count === 0 }"
              @click="currentTab = tab.key"
            >
              {{ tab.label }} <b>{{ tab.count }}</b>
            </button>
          </div>
          <p v-if="loading && !value" role="status" class="workbench-message">正在加载待办…</p>
          <el-table
            v-else-if="activeTab?.key === 'inventory-shortage'"
            :data="value?.inventoryAlerts ?? []"
            table-layout="auto"
          >
            <el-table-column label="商品" min-width="160">
              <template #default="{ row }"
                >{{ row.skuName }}<small class="cell-secondary">{{ row.skuCode }}</small></template
              >
            </el-table-column>
            <el-table-column prop="warehouseName" label="仓库" min-width="100" />
            <el-table-column label="可用" align="right"
              ><template #default="{ row }">{{
                formatQuantity(row.availableQty)
              }}</template></el-table-column
            >
            <el-table-column label="待审核需求" min-width="100" align="right">
              <template #default="{ row }">{{ formatQuantity(row.pendingDemandQty) }}</template>
            </el-table-column>
            <template #empty>当前没有需求超过可用库存的商品</template>
          </el-table>
          <el-table v-else :data="tasks" table-layout="auto">
            <el-table-column prop="label" label="事项" min-width="135" />
            <el-table-column label="单据" min-width="170">
              <template #default="{ row }"
                ><router-link class="text-link" :to="row.url">{{
                  row.documentNo
                }}</router-link></template
              >
            </el-table-column>
            <el-table-column label="对象" min-width="110"
              ><template #default="{ row }">{{ row.partnerName || '—' }}</template></el-table-column
            >
            <el-table-column label="金额" align="right" min-width="90"
              ><template #default="{ row }">{{
                formatMoney(row.amount)
              }}</template></el-table-column
            >
            <el-table-column label="进入待办" min-width="115">
              <template #default="{ row }">{{ workbenchTime(row.createdAt) }}</template>
            </el-table-column>
            <template #empty>暂无需要处理的事项</template>
          </el-table>
          <div v-if="activeTab" class="todo-footer">
            <router-link class="text-link" :to="activeTab.url"
              >查看全部{{ activeTab.label }} <ArrowRight class="icon" aria-hidden="true"
            /></router-link>
          </div>
        </section>
        <aside aria-label="公告与最近访问" class="workbench-side">
          <section class="panel">
            <div class="panel-heading">
              <h2>公告</h2>
              <router-link v-if="navigationAllowed('/messages')" class="text-link" to="/messages?category=notice">全部</router-link>
            </div>
            <el-alert
              v-if="noticeError && !notice"
              :title="noticeError"
              type="error"
              :closable="false"
            />
            <ul v-if="value?.announcements?.length" class="side-list">
              <li v-for="item in value.announcements" :key="item.id">
                <button type="button" class="side-announcement" @click="openAnnouncement(item.id)">
                  <el-tag v-if="item.level === 'important'" type="danger" size="small">重要</el-tag>
                  <span
                    >{{ item.title }}<small>{{ workbenchTime(item.publishAt) }}</small></span
                  >
                </button>
              </li>
            </ul>
            <p v-else class="muted side-empty">{{ loading ? '正在加载公告…' : '暂无公告' }}</p>
          </section>
          <section class="panel">
            <div class="panel-heading"><h2>最近访问</h2></div>
            <ul v-if="recent.length" class="side-list">
              <li v-for="item in recent" :key="item.path">
                <router-link class="text-link" :to="item.path">{{ item.title }}</router-link>
              </li>
            </ul>
            <p v-else class="muted side-empty">暂无访问记录</p>
          </section>
          <section v-if="session.can('alert:view') && alertCount !== undefined" class="panel">
            <div class="panel-heading">
              <h2>未处理预警</h2>
              <router-link class="text-link" to="/alerts?status=open"
                >{{ alertCount }}条预警</router-link
              >
            </div>
          </section>
          <details v-if="value?.periods" class="panel side-details">
            <summary>本月期间</summary>
            <div v-for="period in value.periods" :key="period.id" class="period-summary">
              <el-tag :type="period.status === 'open' ? 'primary' : 'info'">{{
                period.status === 'open' ? '开放' : '已关闭'
              }}</el-tag>
              {{ period.period }}<small>{{ period.startDate }} 至 {{ period.endDate }}</small>
            </div>
            <p v-if="!value.periods.length">当天尚未开启会计期间</p>
            <router-link v-if="navigationAllowed('/accounting-periods')" class="text-link" to="/accounting-periods">查看会计期间与关账</router-link>
          </details>
          <details v-if="value?.recentActions?.length" class="panel side-details">
            <summary>我最近的操作</summary>
            <ul class="side-list audit-actions">
              <li v-for="(item, index) in value.recentActions" :key="index">
                <router-link
                  v-if="documentLink(item.objectType, item.objectId)"
                  :to="documentLink(item.objectType, item.objectId)!"
                  class="text-link"
                  >查看相关单据</router-link
                >
                <span v-else>资料操作</span><small>{{ formatRelativeTime(item.createdAt) }}</small>
              </li>
            </ul>
          </details>
        </aside>
      </div>
    </WorkbenchTradeSummary>
    <p v-if="value" class="workbench-updated muted">待办更新于 {{ formatDateTime(value.asOf) }}</p>
    <el-dialog v-model="editingShortcuts" title="配置快捷入口" width="min(460px, 92vw)">
      <p class="muted">已选 {{ selectedPaths.length }} / {{ SHORTCUT_LIMIT }}</p>
      <el-checkbox-group v-model="selectedPaths" :max="SHORTCUT_LIMIT" class="shortcut-settings">
        <el-checkbox v-for="item in allowedShortcuts" :key="item.path" :value="item.path">{{
          item.title
        }}</el-checkbox>
      </el-checkbox-group>
      <el-alert v-if="preferencesError" :title="preferencesError" type="error" :closable="false" />
      <template #footer
        ><el-button @click="editingShortcuts = false">取消</el-button
        ><el-button type="primary" @click="savePreferences">保存</el-button></template
      >
    </el-dialog>
    <el-dialog
      v-if="notice"
      :model-value="true"
      :title="notice.title"
      width="min(680px, 92vw)"
      @close="notice = undefined"
    >
      <el-alert v-if="noticeError" :title="noticeError" type="error" :closable="false" />
      <article class="notice-content" v-html="notice.content" />
      <template #footer
        ><el-button v-if="!notice.confirmed" type="primary" :loading="savingNotice" @click="confirm"
          >阅读并确认</el-button
        ></template
      >
    </el-dialog>
  </template>
</template>

<style scoped>
.workbench-heading h1 {
  font-size: 24px;
  line-height: 1.3;
}
.workbench-heading p {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px 8px;
  margin-top: 4px;
}
.workbench-heading a,
.side-announcement:hover {
  color: var(--el-color-primary);
}
.workbench-message {
  padding: 14px 16px;
}
.workbench-shortcuts {
  margin-bottom: 16px;
  padding: 12px 16px 16px;
}
.workbench-shortcuts h2,
.workbench-columns h2 {
  margin: 0;
  font-size: 15px;
}
.workbench-shortcuts .panel-heading {
  padding: 0 0 8px;
}
.shortcut-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}
.shortcut-grid a {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 42px;
  padding: 6px 8px;
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--erp-radius);
  color: var(--erp-text-1);
  text-decoration: none;
}
.shortcut-grid a:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}
.shortcut-icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 28px;
  height: 28px;
  border-radius: var(--erp-radius);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.shortcut-icon .icon,
.todo-footer .icon {
  width: 15px;
  height: 15px;
}
.shortcut-settings {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.workbench-columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(212px, 25%);
  align-items: start;
  gap: 14px;
}
.workbench-columns > * {
  min-width: 0;
}
.workbench-columns .panel-heading {
  flex-wrap: wrap;
  gap: 6px 12px;
}
.todo-types {
  display: flex;
  gap: 4px 8px;
  flex-wrap: wrap;
  padding: 0 14px 8px;
}
.todo-types button {
  min-height: 32px;
  padding: 4px 8px;
  border: 1px solid transparent;
  border-radius: var(--erp-radius-sm);
  background: transparent;
  color: var(--erp-text-1);
  font: inherit;
  cursor: pointer;
}
.todo-types button b {
  margin-left: 2px;
  font-variant-numeric: tabular-nums;
}
.todo-types button.is-empty {
  color: var(--erp-text-2);
}
.todo-types button:hover {
  border-color: var(--el-border-color);
}
.todo-types button[aria-pressed='true'] {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.todo-footer {
  padding: 10px 14px;
}
.todo-footer a {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.workbench-side {
  display: grid;
  gap: 12px;
}
.workbench-side .panel-heading {
  padding: 12px 14px;
}
.side-list {
  margin: 0;
  padding: 0 14px;
  list-style: none;
}
.side-list li {
  padding: 8px 0;
  border-top: 1px solid var(--el-border-color-light);
}
.side-list small,
.period-summary small {
  display: block;
  margin-top: 3px;
  color: var(--erp-text-2);
}
.side-announcement {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  width: 100%;
  padding: 3px 0;
  border: 0;
  line-height: 1.45;
  font: inherit;
  color: var(--erp-text-1);
  text-align: left;
  background: transparent;
  cursor: pointer;
  overflow-wrap: anywhere;
}
.side-empty {
  margin: 0;
  padding: 0 14px 12px;
}
.side-details {
  padding: 10px 14px;
}
.side-details summary {
  cursor: pointer;
  font-weight: 600;
}
.period-summary {
  margin: 8px 0;
}
.audit-actions {
  padding: 8px 0 0;
}
.workbench-updated {
  text-align: right;
  margin: 8px 0 0;
  font-size: 12px;
}
.notice-content {
  overflow-wrap: anywhere;
}
@media (max-width: 960px) {
  .workbench-columns {
    grid-template-columns: minmax(0, 1fr);
  }
  .workbench-side {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 680px) {
  .shortcut-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .workbench-side {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
