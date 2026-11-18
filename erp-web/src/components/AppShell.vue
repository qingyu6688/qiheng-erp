<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onScopeDispose, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowDown,
  ArrowRight,
  Box,
  ChatDotRound,
  Close,
  DataAnalysis,
  Document,
  Expand,
  Fold,
  House,
  Lock,
  MoreFilled,
  OfficeBuilding,
  Search,
  Setting,
  ShoppingCart,
  SwitchButton,
  Tickets,
  Wallet,
} from '@element-plus/icons-vue'
import { ElDropdown, ElDropdownItem, ElDropdownMenu, type DropdownInstance } from 'element-plus/es/components/dropdown/index.mjs'
import 'element-plus/theme-chalk/el-dropdown.css'
import { useSessionStore } from '../stores/session'
import { messageOf, isCancelled } from '../api/http'
import { workbenchSummary } from '../api/workbench'
import { openAiDocument } from '../hooks/ai-document'
import type { AiDocumentContext } from '../types/ai'
import BrandMark from './BrandMark.vue'
import NotificationBell from './NotificationBell.vue'
import DisplayDensitySelect from './DisplayDensitySelect.vue'
import WorkspaceSearch from './WorkspaceSearch.vue'
import { reportSpecs, reportPermission } from '../types/reporting'
import { importSpecs } from '../types/file-jobs'
import { documentPrintPermissions } from '../types/documents'
import { crmCoreNavigation, crmCycleNavigation } from '../types/crm-navigation'
import { manufacturingNavigation } from '../types/manufacturing'
import { confirmCompanyLeave, hasUnsavedWork } from '../utils/unsaved'
import { recentPaths, recentWorkPages, shortcuts, visitWorkPage, workbenchScope } from '../utils/workbench-preferences'
const route = useRoute(),
  router = useRouter(),
  session = useSessionStore()
function selectCompany(id: string): void {
  if (id !== session.companyId && confirmCompanyLeave()) session.selectCompany(id)
}
const loggingOut = ref(false),
  error = ref(''),
  overdue = ref(0)
const title = computed(() =>
  String(route.meta.title ?? (route.name === 'password' ? '账户设置' : '工作台')),
)
const accountName = computed(() => session.user?.displayName || session.user?.username || '账户')
const AiAssistantDrawer = defineAsyncComponent(() => import('./AiAssistantDrawer.vue'))
const assistantOpen = ref(false)
const assistantOpened = ref(false)
const assistantDocument = ref<AiDocumentContext>()
const showAssistant = computed(
  () => Boolean(session.companyId) && !session.user?.mustChangePassword && session.can('ai:use'),
)
watch(
  [() => session.companyId, () => session.user, showAssistant],
  () => {
    assistantOpen.value = false
    assistantOpened.value = false
    assistantDocument.value = undefined
  },
  { flush: 'sync' },
)
function openAssistant(): void {
  assistantDocument.value = undefined
  assistantOpened.value = true
  assistantOpen.value = true
}
provide(openAiDocument, (document) => {
  if (!showAssistant.value) return
  assistantDocument.value = document
  assistantOpened.value = true
  assistantOpen.value = true
})
watch(
  () => route.fullPath,
  () => {
    if (!assistantDocument.value) return
    assistantOpen.value = false
    assistantDocument.value = undefined
  },
  { flush: 'sync' },
)
interface NavigationItem {
  path: string
  title: string
  permission?: string
  any?: string[]
  all?: string[]
}
const definitions = [
  {
    title: '客户关系',
    icon: OfficeBuilding,
    items: [
      ...[...crmCoreNavigation, ...crmCycleNavigation].map(({ kind, title, permission }) => ({
        path: 'crm/' + kind,
        title,
        permission,
      })),
      { path: 'crm/price-lists', title: '价格表', permission: 'price_list:view' },
    ],
  },
  {
    title: '客服',
    icon: ChatDotRound,
    items: [
      { path: 'cs/tickets', title: '客服工单', permission: 'cs_ticket:view' },
      { path: 'cs/portal-users', title: '门户账号', permission: 'portal_user:view' },
      { path: 'cs/sensitive-words', title: '敏感词', permission: 'portal_sensitive_word:view' },
    ],
  },
  {
    title: '财务总账',
    icon: Wallet,
    items: [
      { path: 'gl/book', title: '账套与期初', permission: 'gl_config:view' },
      { path: 'gl/config', title: '财务基础配置', permission: 'gl_config:view' },
      { path: 'gl/vouchers', title: '总账凭证', permission: 'gl_voucher:view' },
      { path: 'gl/ledgers', title: '财务账簿', permission: 'gl_ledger:view' },
      { path: 'gl/reports', title: '财务报表', permission: 'gl_report:view' },
      { path: 'gl/closing', title: '财务结账', permission: 'gl_closing:close' },
      { path: 'ar/credit', title: '客户信用', permission: 'credit_limit:view' },
      { path: 'ar/aging', title: '账龄区间', permission: 'credit_limit:view' },
      { path: 'ar/collections', title: '催收计划', permission: 'collection:view' },
      { path: 'ar/bad-debt', title: '坏账处理', permission: 'bad_debt:view' },
    ],
  },
  {
    title: '审批中心',
    icon: Tickets,
    items: [
      { path: 'workflow/todo', title: '待我审批' },
      { path: 'workflow/done', title: '我的已办' },
      { path: 'workflow/mine', title: '我的申请' },
      { path: 'workflow/copies', title: '抄送我的' },
      { path: 'workflow/all', title: '全部流程', permission: 'workflow_instance:view_all' },
      { path: 'workflow/models', title: '流程模型与绑定', permission: 'workflow_model:view' },
      { path: 'workflow/forms', title: '表单设计', permission: 'workflow_form:manage' },
      { path: 'workflow/start', title: '发起通用审批', permission: 'wf_generic_request:create' },
    ],
  },

  {
    title: '销售',
    icon: Tickets,
    items: [
      { path: 'sales-orders', title: '销售订单', permission: 'sales_order:view' },
      { path: 'shipments', title: '发货与签收', permission: 'shipment:view' },
      { path: 'sales-returns', title: '销售退货', permission: 'sales_return:view' },
    ],
  },
  {
    title: '采购',
    icon: ShoppingCart,
    items: [
      { path: 'purchase-requisitions', title: '请购管理', permission: 'purchase_requisition:view' },
      { path: 'purchase-orders', title: '采购订单', permission: 'purchase_order:view' },
      { path: 'goods-receipts', title: '采购收货', permission: 'goods_receipt:view' },
      { path: 'purchase-returns', title: '采购退货', permission: 'purchase_return:view' },
    ],
  },
  {
    title: '生产',
    icon: Box,
    items: manufacturingNavigation.map(({ area, title, permission }) => ({ path: 'mfg/' + area, title, permission,
      all: area === 'cost-periods' ? ['cost:view'] : undefined })),
  },
  {
    title: '库存',
    icon: Box,
    items: [
      { path: 'inventory-balances', title: '实时库存', permission: 'inventory:view' },
      { path: 'wms-tasks', title: '上架任务', permission: 'wms_task:view' },
      { path: 'warehouse-work', title: '仓储作业', any: ['wms_task:view', 'wms_task:configure', 'wms_move:view', 'wms_pack:view', 'label:print'] },
      { path: 'stock-transfers', title: '库存调拨', permission: 'stock_transfer:view' },
      { path: 'stock-counts', title: '库存盘点', permission: 'stock_count:view' },
      { path: 'stock-adjustments', title: '库存调整', permission: 'stock_adjustment:view' },
      {
        path: 'inventory-trace',
        title: '批次与序列号',
        any: ['batch:view', 'batch:trace', 'serial:trace', 'inventory_trace:customer'],
      },
      { path: 'stock-misc-in', title: '其他入库', permission: 'stock_misc_doc:view' },
      { path: 'stock-misc-out', title: '其他出库', permission: 'stock_misc_doc:view' },
      { path: 'quality', title: '来料质检', any: ['qc_inspection:view', 'qc_setting:view'] },
    ],
  },
  {
    title: '资金与往来',
    icon: Wallet,
    items: [
      { path: 'receipts', title: '收款登记', permission: 'receipt:view' },
      { path: 'payments', title: '付款登记', permission: 'payment:view' },
      { path: 'refund-requests', title: '退款申请', permission: 'refund_request:view' },
      { path: 'account-transfers', title: '账户转账', permission: 'account_transfer:view' },
      { path: 'settlements', title: '核销工作台', permission: 'settlement:view' },
      { path: 'invoice-registrations', title: '开票登记', permission: 'invoice_registration:view' },
      { path: 'e-invoices', title: '数电发票', permission: 'einv:view' },
      { path: 'opening-entries', title: '往来与资金期初', permission: 'opening_entry:view' },
    ],
  },
  {
    title: '财务期间',
    icon: Document,
    items: [
      { path: 'document-reversals', title: '单据冲销', permission: 'reversal:view' },
      { path: 'accounting-periods', title: '会计期间与关账', permission: 'period:view' },
      { path: 'supply-reconciliation', title: '供应链对账', permission: 'reconciliation:view' },
    ],
  },
  {
    title: '报表与数据',
    icon: DataAnalysis,
    items: [
      { path: 'dashboard', title: '经营驾驶舱', permission: 'dashboard:view' },
      { path: 'alerts', title: '预警中心', permission: 'alert:view' },
      { path: 'print-templates', title: '打印模板', permission: 'print_template:view' },
      {
        path: 'reports',
        title: '业务报表',
        any: reportSpecs.map((spec) => reportPermission(spec.type)),
      },
      {
        path: 'file-jobs',
        title: '导入与导出',
        any: [
          ...importSpecs.map((spec) => 'import:' + spec.type),
          'gl_export:create',
          ...reportSpecs.map((spec) => reportPermission(spec.type, 'export')),
          ...documentPrintPermissions,
        ],
      },
    ],
  },
  {
    title: '基础资料',
    icon: OfficeBuilding,
    items: [
      { path: 'partners', title: '往来单位', permission: 'partner:view' },
      { path: 'skus', title: '商品与 SKU', any: ['sku:view', 'product:view'] },
      { path: 'product-categories', title: '商品分类', permission: 'product_category:view' },
      { path: 'brands', title: '品牌', permission: 'brand:view' },
      { path: 'units', title: '计量单位', permission: 'unit:view' },
      { path: 'warehouses', title: '仓库管理', permission: 'warehouse:view' },
      { path: 'warehouse-locations', title: '库区库位', permission: 'wms_location:view' },
      { path: 'cash-accounts', title: '结算账户', permission: 'cash_account:view' },
      { path: 'income-expense-items', title: '收支项目', permission: 'income_expense_item:view' },
    ],
  },
  {
    title: 'AI 与知识',
    icon: ChatDotRound,
    items: [
      { path: 'ai/wiki', title: '知识百科', permission: 'ai:use' },
      { path: 'ai/knowledge', title: '知识库管理', any: ['ai_admin:view', 'ai_admin:manage'] },
      { path: 'ai/wiki-review', title: 'Wiki 审核', permission: 'ai_admin:review' },
      { path: 'ai/admin', title: 'AI 管理', any: ['ai_admin:view', 'ai_admin:manage'] },
      { path: 'ai/evaluations', title: 'AI 评测', permission: 'ai_admin:evaluate' },
    ],
  },
  {
    title: '系统管理',
    icon: Setting,
    items: [
      { path: 'companies', title: '公司与参数', permission: 'company:view' },
      { path: 'organization', title: '部门与岗位', any: ['org_department:view', 'org_post:view'] },
      { path: 'users', title: '用户与角色', any: ['user:view', 'role:view'] },
      { path: 'login-settings', title: '登录方式设置', permission: 'tenant:login_settings' },
      { path: 'security-policy', title: '企业安全策略', permission: 'tenant:security_policy' },
      { path: 'custom-fields', title: '自定义字段', permission: 'custom_field:manage' },
      { path: 'code-rules', title: '编码规则', permission: 'code_rule:manage' },
      { path: 'open-platform', title: '开放平台', permission: 'open_platform:view' },

      { path: 'sys/dict-types', title: '数据字典', permission: 'dict:view' },
      { path: 'sys/notices', title: '通知公告', permission: 'notice:view' },
      { path: 'sys/notify-templates', title: '消息模板', permission: 'notify_template:view' },
      { path: 'sys/notify-send-logs', title: '消息发送日志', permission: 'notify_log:view' },
      { path: 'sys/notify-robot', title: '企业微信机器人', permission: 'notify_robot:manage' },
      { path: 'sys/configs', title: '系统参数', permission: 'sys_config:view' },
      { path: 'sys/jobs', title: '定时任务', permission: 'job:view' },
      { path: 'sys/login-logs', title: '登录日志', permission: 'login_log:view' },
      { path: 'sys/online-users', title: '在线用户', permission: 'online_user:view' },
      { path: 'sys/api-access-logs', title: 'API访问日志', permission: 'api_log:view' },
      { path: 'sys/api-error-logs', title: 'API异常日志', permission: 'api_error:view' },
      { path: 'audit-logs', title: '操作审计', any: ['audit:view', 'admin:outbox'] },
    ],
  },
] satisfies { title: string; icon: typeof Box; items: NavigationItem[] }[]
const groups = computed(() =>
  session.user?.mustChangePassword
    ? []
    : definitions
        .map((group) => ({
          ...group,
          items: (group.items as NavigationItem[])
            .filter((item) =>
              (!item.all || item.all.every(session.can)) && (item.permission
                ? session.can(item.permission)
                : item.any
                  ? item.any.some(session.can)
                  : true),
            )
            .map((item) => ({
              ...item,
              path:
                item.path === 'skus' && !session.can('sku:view')
                  ? 'products'
                  : item.path === 'users' && !session.can('user:view')
                    ? 'roles'
                    : item.path,
            })),
        }))
        .filter((group) => group.items.length),
)
const recentScope = computed(() => workbenchScope(session.user?.id, session.companyId))
const visited = ref<string[]>([])
const recentPages = computed(() => {
  const pages = new Map<string, string>()
  for (const group of groups.value) {
    for (const item of group.items) if (item.path) pages.set('/' + item.path, item.title)
  }
  for (const shortcut of shortcuts) {
    if (!shortcut.permission || session.can(shortcut.permission)) pages.set(shortcut.path, shortcut.title)
  }
  return pages
})
provide(recentWorkPages, computed(() => recentScope.value ? visited.value.flatMap((path) => {
  const title = recentPages.value.get(path)
  return title ? [{ path, title }] : []
}) : []))
watch(recentScope, (scope) => { visited.value = recentPaths(scope) }, { immediate: true, flush: 'sync' })
watch(() => route.path, (path) => {
  if (recentScope.value && recentPages.value.has(path)) {
    visited.value = visitWorkPage(recentScope.value, path, visited.value)
  }
}, { immediate: true })
const moduleDefinitions = [
  { title: '工作台', icon: House, sections: ['工作台'] },
  { title: '销售', icon: Tickets, sections: ['销售'] },
  { title: '采购', icon: ShoppingCart, sections: ['采购'] },
  { title: '库存', icon: Box, sections: ['库存'] },
  { title: '仓储', icon: OfficeBuilding, sections: ['仓储'] },
  { title: '生产', icon: Box, sections: ['生产'] },
  { title: '财务', icon: Wallet, sections: ['资金与往来', '财务总账', '财务期间'] },
  { title: '客户', icon: OfficeBuilding, sections: ['客户关系', '客服'] },
  { title: '审批', icon: Tickets, sections: ['审批中心'] },
  { title: '分析', icon: DataAnalysis, sections: ['报表与数据', 'AI 与知识'] },
  { title: '设置', icon: Setting, sections: ['基础资料', '系统管理'] },
]
const modules = computed(() => {
  if (session.user?.mustChangePassword) return []
  const itemsFor = (title: string) => groups.value.find((group) => group.title === title)?.items ?? []
  const matching = (title: string, paths: string[]) => itemsFor(title).filter((item) => paths.includes(item.path))
  const dashboard = matching('报表与数据', ['dashboard'])
  const pricing = matching('客户关系', ['crm/price-lists'])
  const printing = matching('报表与数据', ['print-templates'])
  const warehouse = [...matching('库存', ['wms-tasks', 'warehouse-work']), ...matching('基础资料', ['warehouse-locations'])]
  const moved = new Set([...dashboard, ...pricing, ...printing, ...warehouse])
  const sections = new Map(groups.value.map((group) => [group.title, group.items.filter((item) => !moved.has(item))]))
  sections.set('工作台', [{ path: '', title: '工作台' }, ...dashboard])
  sections.set('销售', [...(sections.get('销售') ?? []), ...pricing])
  sections.set('仓储', warehouse)
  sections.set('系统管理', [...(sections.get('系统管理') ?? []), ...printing])
  return moduleDefinitions.map((module) => ({
    ...module,
    sections: module.sections.map((title) => ({ title, items: sections.get(title) ?? [] })).filter((section) => section.items.length),
  })).filter((module) => module.sections.length)
})
const activeModule = computed(() =>
  modules.value.find((module) => module.sections.some((section) => section.items.some((item) => active(item.path)))),
)
const selectedModuleTitle = ref('')
const selectedModule = computed(() =>
  modules.value.find((module) => module.title === selectedModuleTitle.value) ?? activeModule.value ?? modules.value[0],
)
const activeGroup = computed(() =>
  groups.value.find((group) => group.items.some((item) => active(item.path))),
)
const activeItem = computed(() => activeGroup.value?.items.find((item) => active(item.path)))
const compactViewport = window.matchMedia('(max-width: 1199px)')
const mobileViewport = window.matchMedia('(max-width: 767px)')
const mobile = ref(mobileViewport.matches)
const desktopNavigation = ref(!compactViewport.matches)
const mobileNavigation = ref(false)
const floatingMenu = ref(false)
const navigationVisible = computed(() =>
  mobile.value ? mobileNavigation.value : desktopNavigation.value || floatingMenu.value,
)
const menuButton = ref<HTMLButtonElement>()
const closeButton = ref<HTMLButtonElement>()
const sidebar = ref<HTMLElement>()
const moduleMenu = ref<HTMLElement>()
const searchOpen = ref(false)
const searchButton = ref<HTMLButtonElement>()
const searchableMenus = computed(() => modules.value.flatMap((module) =>
  module.sections.flatMap((section) => section.items.map((item) => ({ ...item, module: module.title }))),
))
function syncViewport(): void {
  mobile.value = mobileViewport.matches
  desktopNavigation.value = !compactViewport.matches
  floatingMenu.value = false
  mobileNavigation.value = false
}
compactViewport.addEventListener('change', syncViewport)
mobileViewport.addEventListener('change', syncViewport)
onScopeDispose(() => {
  compactViewport.removeEventListener('change', syncViewport)
  mobileViewport.removeEventListener('change', syncViewport)
})
function closeNavigation(): void {
  mobileNavigation.value = false
  void nextTick(() => menuButton.value?.focus())
}
function escapeNavigation(): void {
  if (mobileNavigation.value) closeNavigation()
  else if (floatingMenu.value) {
    floatingMenu.value = false
    sidebar.value?.querySelector<HTMLElement>('.module-link.is-current')?.focus()
  }
}
function trapNavigationFocus(event: KeyboardEvent): void {
  if (!mobileNavigation.value) return
  const actions = sidebar.value?.querySelectorAll<HTMLElement>('button:not([disabled]), a[href]')
  const first = actions?.[0]
  const last = actions?.[actions.length - 1]
  if (event.shiftKey ? event.target === first : event.target === last) {
    event.preventDefault()
    const target = event.shiftKey ? last : first
    target?.focus()
  }
}
function toggleNavigation(): void {
  if (!mobile.value) {
    const wasOpen = navigationVisible.value
    floatingMenu.value = false
    desktopNavigation.value = !wasOpen
  } else if (mobileNavigation.value) {
    closeNavigation()
  } else {
    mobileNavigation.value = true
    void nextTick(() => closeButton.value?.focus())
  }
}
function selectModule(module: string, fromKeyboard = false): void {
  selectedModuleTitle.value = module
  if (!mobile.value && !desktopNavigation.value) floatingMenu.value = true
  if (fromKeyboard) void nextTick(() => moduleMenu.value?.querySelector<HTMLElement>('a')?.focus())
}
function closeFloatingMenu(event: FocusEvent | PointerEvent): void {
  const sidebar = event.currentTarget as HTMLElement
  if (!sidebar.contains(event.relatedTarget as Node | null) &&
      (event.type === 'focusout' || !sidebar.contains(document.activeElement))) {
    floatingMenu.value = false
  }
}
function handleShortcuts(event: KeyboardEvent): void {
  if (session.user?.mustChangePassword) return
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k' && !mobileNavigation.value) {
    event.preventDefault()
    searchOpen.value = true
  }
  if (event.key === '[' && !event.ctrlKey && !event.metaKey && !event.altKey && !event.repeat &&
      !searchOpen.value && !(event.target instanceof Element &&
        event.target.closest('input, textarea, select, [contenteditable]'))) {
    event.preventDefault()
    toggleNavigation()
  }
}
window.addEventListener('keydown', handleShortcuts)
onScopeDispose(() => window.removeEventListener('keydown', handleShortcuts))
watch(() => session.companyId, () => {
  searchOpen.value = false
  selectedModuleTitle.value = ''
}, { flush: 'sync' })

interface WorkTab {
  key: string
  title: string
  to: string
  pinned: boolean
}
const workTabs = ref<WorkTab[]>([])
const workTabMenus = new Map<string, DropdownInstance>()
function setWorkTabMenu(key: string, menu: DropdownInstance | null): void {
  if (menu) workTabMenus.set(key, menu)
  else workTabMenus.delete(key)
}
const workTabKey = computed(() => activeItem.value || route.name === 'password' ? route.path : '')
const MAX_WORK_TABS = 14
const contentRevision = ref(0)
watch(
  () => session.companyId,
  () => {
    workTabs.value = []
  },
  { flush: 'sync' },
)
watch(
  [() => route.fullPath, workTabKey, () => session.companyId],
  () => {
    if (!workTabKey.value || session.user?.mustChangePassword) return
    const index = workTabs.value.findIndex((item) => item.key === workTabKey.value)
    const item = activeItem.value
    const tab: WorkTab = {
      key: workTabKey.value,
      title: item && route.path === '/' + item.path ? item.title : title.value,
      to: route.fullPath,
      pinned: workTabs.value[index]?.pinned ?? false,
    }
    if (index >= 0) workTabs.value[index] = tab
    else {
      workTabs.value.push(tab)
      if (workTabs.value.length > MAX_WORK_TABS) {
        const oldest = workTabs.value.findIndex((item) => !item.pinned)
        workTabs.value.splice(oldest, 1)
      }
    }
  },
  { immediate: true },
)
async function closeWorkTabs(tabs: WorkTab[], fallback?: string): Promise<void> {
  const removing = new Set(tabs.map((tab) => tab.key))
  if (removing.has(workTabKey.value)) {
    const adjacent = workTabs.value.filter((tab) => !removing.has(tab.key)).at(-1)
    if (await router.push(fallback ?? adjacent?.to ?? '/')) return
  }
  workTabs.value = workTabs.value.filter((tab) => !removing.has(tab.key))
}
function tabAction(command: string, tab: WorkTab): void {
  if (command === 'pin') {
    tab.pinned = !tab.pinned
  } else if (command === 'close') {
    void closeWorkTabs([tab])
  } else if (command === 'others' || command === 'right') {
    const candidates = command === 'right' ? workTabs.value.slice(workTabs.value.indexOf(tab) + 1) : workTabs.value
    void closeWorkTabs(candidates.filter((item) => item.key !== tab.key && !item.pinned), tab.to)
  } else if (command === 'refresh') {
    if (tab.key === workTabKey.value) {
      if (confirmCompanyLeave()) contentRevision.value++
    } else {
      void router.push(tab.to)
    }
  }
}

watch(
  [() => route.fullPath, activeModule],
  ([, module]) => {
    selectedModuleTitle.value = module?.title ?? ''
    floatingMenu.value = false
    if (mobileNavigation.value) closeNavigation()
  },
  { immediate: true },
)
function active(path: string): boolean {
  return path === 'reports'
    ? route.path.startsWith('/reports/')
    : path === 'skus' || path === 'products'
      ? ['/skus', '/products'].includes(route.path)
      : path === 'users' || path === 'roles'
        ? ['/users', '/roles'].includes(route.path)
        : route.path === '/' + path || route.path.startsWith('/' + path + '/')
}
watch(
  () => session.companyId,
  async () => {
    overdue.value = 0
    if (!session.companyId || session.user?.mustChangePassword) return
    const company = session.companyId
    try {
      const data = await workbenchSummary()
      if (company === session.companyId) {
        overdue.value = data.blocks.find((block) => block.key === 'shipments')?.count ?? 0
      }
    } catch (cause: unknown) {
      if (!isCancelled(cause)) error.value = messageOf(cause)
    }
  },
  { immediate: true },
)
async function logout(): Promise<void> {
  loggingOut.value = true
  error.value = ''
  try {
    await session.signOut()
    await router.replace('/login')
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    loggingOut.value = false
  }
}
</script>
<template>
  <div
    class="shell"
    :class="{ 'shell-with-sidebar': !mobile && modules.length, 'shell-with-menu': !mobile && desktopNavigation && modules.length }"
    @keydown.esc="escapeNavigation"
  >
    <div
      v-if="mobileNavigation"
      class="sidebar-backdrop"
      aria-hidden="true"
      @click="closeNavigation()"
    />
    <div
      v-show="modules.length && (!mobile || mobileNavigation)"
      id="workspace-sidebar"
      ref="sidebar"
      class="sidebar"
      :role="mobile ? 'dialog' : undefined"
      :aria-modal="mobile ? true : undefined"
      :aria-label="mobile ? '业务导航' : undefined"
      @pointerleave="closeFloatingMenu"
      @focusout="closeFloatingMenu"
      @keydown.tab="trapNavigationFocus"
    >
      <div v-if="mobile" class="sidebar-head">
        <BrandMark />
        <button
          ref="closeButton"
          class="shell-button"
          type="button"
          aria-label="关闭导航"
          @click="closeNavigation()"
        >
          <Close class="icon" aria-hidden="true" />
        </button>
      </div>
      <nav v-if="modules.length" class="module-rail" aria-label="业务模块">
        <BrandMark v-if="!mobile" compact />
        <button
          v-for="module in modules"
          :key="module.title"
          class="module-link"
          :class="{ 'is-current': selectedModule?.title === module.title }"
          type="button"
          :aria-current="activeModule?.title === module.title ? 'true' : undefined"
          :aria-expanded="navigationVisible && selectedModule?.title === module.title"
          aria-controls="workspace-module-menu"
          @click="selectModule(module.title, $event.detail === 0)"
          @pointerenter="$event.pointerType === 'mouse' && !mobile && !desktopNavigation && selectModule(module.title)"
        >
          <component :is="module.icon" class="icon" aria-hidden="true" />{{ module.title }}
        </button>
      </nav>
      <nav
        v-show="navigationVisible && selectedModule"
        id="workspace-module-menu"
        ref="moduleMenu"
        class="module-menu"
        :class="{ 'is-floating': floatingMenu }"
        :aria-label="selectedModule?.title + '菜单'"
      >
        <div class="module-title"><BrandMark v-if="!mobile" /> <strong v-else>{{ selectedModule?.title }}</strong></div>
        <section v-for="section in selectedModule?.sections" :key="section.title" class="nav-group">
          <h2 v-if="selectedModule && selectedModule.sections.length > 1" class="nav-caption">{{ section.title }}</h2>
          <router-link
            v-for="item in section.items"
            :key="item.path"
            class="nav-link"
            :class="{ 'is-current': active(item.path) }"
            :to="'/' + item.path"
            @click="mobileNavigation && active(item.path) && closeNavigation()"
          >{{ item.title }}<span v-if="item.path === 'shipments' && overdue" class="nav-count">{{ overdue }}</span></router-link>
        </section>
      </nav>
    </div>
    <div class="workspace" :inert="mobileNavigation">
      <header class="topbar">
        <div class="topbar-start">
          <button
            v-if="modules.length"
            ref="menuButton"
            class="shell-button"
            type="button"
            :aria-label="navigationVisible ? '收起导航' : '展开导航'"
            :aria-expanded="navigationVisible"
            :aria-controls="mobile ? 'workspace-sidebar' : 'workspace-module-menu'"
            @click="toggleNavigation"
          >
            <Fold v-if="navigationVisible" class="icon" aria-hidden="true" />
            <Expand v-else class="icon" aria-hidden="true" />
          </button>
          <button
            v-if="modules.length"
            ref="searchButton"
            class="shell-search"
            type="button"
            aria-label="搜索菜单、单号、客户、商品 Ctrl K"
            aria-keyshortcuts="Control+K"
            @click="searchOpen = true"
          >
            <Search class="icon" aria-hidden="true" /><span>搜索菜单、单号、客户、商品</span><kbd>Ctrl K</kbd>
          </button>
          <nav class="breadcrumb" aria-label="当前位置">
            <router-link
              v-if="route.path !== '/' && !session.user?.mustChangePassword"
              to="/"
              aria-label="返回工作台"
              title="返回工作台"
            >
              <House class="icon" aria-hidden="true" />
            </router-link>
            <ArrowRight v-if="activeGroup" class="breadcrumb-arrow" aria-hidden="true" />
            <span v-if="activeGroup">{{ activeGroup.title }}</span>
            <ArrowRight
              v-if="route.path !== '/' && !session.user?.mustChangePassword"
              class="breadcrumb-arrow"
              aria-hidden="true"
            />
            <strong aria-current="page">{{ title }}</strong>
          </nav>
        </div>
        <div class="topbar-actions">
          <label for="company-select" class="sr-only">当前公司</label>
          <el-select
            id="company-select"
            class="company-select"
            :model-value="session.companyId"
            placeholder="请选择公司"
            @change="selectCompany"
          >
            <template #prefix
              ><OfficeBuilding class="icon company-icon" aria-hidden="true"
            /></template>
            <el-option
              v-for="company in session.user?.companies"
              :key="company.id"
              :value="company.id"
              :label="company.name"
            />
          </el-select>
          <button
            v-if="showAssistant"
            class="shell-button"
            type="button"
            aria-label="打开 AI 助手"
            title="AI 助手"
            @click="openAssistant"
          >
            <ChatDotRound class="icon" aria-hidden="true" />
          </button>
          <NotificationBell v-if="!session.user?.mustChangePassword" />
          <details class="account-menu">
            <summary>
              <span class="account-avatar" aria-hidden="true">{{
                accountName.charAt(0).toUpperCase()
              }}</span>
              <span class="account-label">{{ accountName }}</span>
              <span class="account-menu-name">账户菜单</span>
              <ArrowDown class="account-arrow" aria-hidden="true" />
            </summary>
            <div class="account-options">
              <router-link
                to="/account/password"
                @click="
                  ($event.currentTarget as HTMLElement).closest('details')?.removeAttribute('open')
                "
              >
                <Lock class="icon" aria-hidden="true" />修改密码
              </router-link>
              <router-link
                v-if="!session.user?.mustChangePassword"
                to="/account/login-methods"
                @click="
                  ($event.currentTarget as HTMLElement).closest('details')?.removeAttribute('open')
                "
              >
                <Lock class="icon" aria-hidden="true" />我的登录方式
              </router-link>
              <router-link
                v-if="!session.user?.mustChangePassword"
                to="/account/security"
                @click="
                  ($event.currentTarget as HTMLElement).closest('details')?.removeAttribute('open')
                "
              >
                <Lock class="icon" aria-hidden="true" />账户安全
              </router-link>
              <DisplayDensitySelect
                v-if="!session.user?.mustChangePassword"
                class="account-density"
                label="界面密度"
              />
            </div>
          </details>
          <button
            class="shell-button"
            type="button"
            :disabled="loggingOut"
            aria-label="退出登录"
            title="退出登录"
            @click="logout"
          >
            <SwitchButton class="icon" aria-hidden="true" />
          </button>
        </div>
      </header>
      <nav v-if="!session.user?.mustChangePassword" class="work-tabs" aria-label="工作标签">
        <router-link
          class="work-tab-home"
          to="/"
          :class="{ 'is-current': route.path === '/' }"
          aria-label="切换到工作台"
        >
          <House class="icon" aria-hidden="true" />工作台
        </router-link>
        <div
          v-for="tab in workTabs"
          :key="tab.key"
          class="work-tab"
          :class="{ 'is-current': tab.key === workTabKey }"
          @contextmenu.prevent="workTabMenus.get(tab.key)?.handleOpen()"
        >
          <router-link :to="tab.to" :aria-label="'切换到' + tab.title">
            <Lock v-if="tab.pinned" class="icon" aria-label="已固定" />
            <span v-if="tab.key === workTabKey && hasUnsavedWork" class="tab-dirty" aria-label="未保存" />
            {{ tab.title }}
          </router-link>
          <button
            type="button"
            :aria-label="'关闭' + tab.title + '标签'"
            :title="'关闭' + tab.title + '标签'"
            @click="closeWorkTabs([tab])"
          >
            <Close aria-hidden="true" />
          </button>
          <el-dropdown
            :ref="(menu) => setWorkTabMenu(tab.key, menu as DropdownInstance | null)"
            trigger="click"
            placement="bottom-start"
            @command="tabAction($event, tab)"
          >
            <button type="button" class="tab-menu-button" :aria-label="tab.title + '标签操作'">
              <MoreFilled aria-hidden="true" />
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="refresh">刷新</el-dropdown-item>
                <el-dropdown-item command="close">关闭</el-dropdown-item>
                <el-dropdown-item command="others">关闭其他</el-dropdown-item>
                <el-dropdown-item command="right">关闭右侧</el-dropdown-item>
                <el-dropdown-item command="pin" :disabled="!tab.pinned && workTabs.filter((item) => item.pinned).length >= MAX_WORK_TABS - 1">
                  {{ tab.pinned ? '取消固定' : '固定' }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </nav>
      <main class="content">
        <el-alert v-if="error" type="error" :title="error" :closable="false" /><router-view
          :key="route.path + ':' + session.companyId + ':' + contentRevision"
        />
      </main>
    </div>
    <WorkspaceSearch
      v-model="searchOpen"
      :menu="searchableMenus"
      @closed="searchButton?.focus()"
    />
    <AiAssistantDrawer
      v-if="showAssistant && assistantOpened"
      v-model="assistantOpen"
      :document-context="assistantDocument"
    />
  </div>
</template>
