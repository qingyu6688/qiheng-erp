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
  OfficeBuilding,
  Setting,
  ShoppingCart,
  SwitchButton,
  Tickets,
  Wallet,
} from '@element-plus/icons-vue'
import { useSessionStore } from '../stores/session'
import { messageOf, isCancelled } from '../api/http'
import { workbenchSummary } from '../api/workbench'
import { openAiDocument } from '../hooks/ai-document'
import type { AiDocumentContext } from '../types/ai'
import BrandMark from './BrandMark.vue'
import NotificationBell from './NotificationBell.vue'
import { reportSpecs, reportPermission } from '../types/reporting'
import { importSpecs } from '../types/file-jobs'
import { documentPrintPermissions } from '../types/documents'
import { crmCoreNavigation, crmCycleNavigation } from '../types/crm-navigation'
import { manufacturingNavigation } from '../types/manufacturing'
import { confirmCompanyLeave } from '../utils/unsaved'
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
const expanded = ref<Record<string, boolean>>({})
const activeGroup = computed(() =>
  groups.value.find((group) => group.items.some((item) => active(item.path))),
)
const activeItem = computed(() => activeGroup.value?.items.find((item) => active(item.path)))
const compactViewport = window.matchMedia('(max-width: 800px)')
const mobile = ref(compactViewport.matches)
const desktopNavigation = ref(true)
const mobileNavigation = ref(false)
const navigationVisible = computed(() =>
  mobile.value ? mobileNavigation.value : desktopNavigation.value,
)
const menuButton = ref<HTMLButtonElement>()
const closeButton = ref<HTMLButtonElement>()
function syncViewport(event: MediaQueryListEvent): void {
  mobile.value = event.matches
  mobileNavigation.value = false
}
compactViewport.addEventListener('change', syncViewport)
onScopeDispose(() => compactViewport.removeEventListener('change', syncViewport))
function closeNavigation(): void {
  mobileNavigation.value = false
  void nextTick(() => menuButton.value?.focus())
}
function toggleNavigation(): void {
  if (!mobile.value) {
    desktopNavigation.value = !desktopNavigation.value
  } else if (mobileNavigation.value) {
    closeNavigation()
  } else {
    mobileNavigation.value = true
    void nextTick(() => closeButton.value?.focus())
  }
}

interface WorkTab {
  key: string
  title: string
  to: string
}
const workTabs = ref<WorkTab[]>([])
const workTabKey = computed(
  () => activeItem.value?.path ?? (route.name === 'password' ? 'password' : ''),
)
const MAX_WORK_TABS = 8
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
    const tab = {
      key: workTabKey.value,
      title: activeItem.value?.title ?? title.value,
      to: route.fullPath,
    }
    const index = workTabs.value.findIndex((item) => item.key === tab.key)
    if (index >= 0) workTabs.value[index] = tab
    else workTabs.value = [...workTabs.value, tab].slice(-MAX_WORK_TABS)
  },
  { immediate: true },
)
async function closeWorkTab(tab: WorkTab): Promise<void> {
  if (tab.key === workTabKey.value) {
    const adjacent = workTabs.value.filter((item) => item.key !== tab.key).at(-1)
    if (await router.push(adjacent?.to ?? '/')) return
  }
  workTabs.value = workTabs.value.filter((item) => item.key !== tab.key)
}

watch(
  [() => route.fullPath, activeGroup],
  ([, group]) => {
    if (group) expanded.value[group.title] = true
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
    :class="{ 'shell-with-sidebar': navigationVisible && !mobile }"
    @keydown.esc="mobileNavigation && closeNavigation()"
  >
    <div
      v-if="mobileNavigation"
      class="sidebar-backdrop"
      aria-hidden="true"
      @click="closeNavigation()"
    />
    <aside
      v-show="navigationVisible"
      id="workspace-sidebar"
      class="sidebar"
      :role="mobile ? 'dialog' : undefined"
      :aria-modal="mobile ? true : undefined"
      :aria-label="mobile ? '业务导航' : undefined"
    >
      <div class="sidebar-head">
        <BrandMark />
        <button
          v-if="mobile"
          ref="closeButton"
          class="shell-button"
          type="button"
          aria-label="关闭导航"
          @click="closeNavigation()"
        >
          <Close class="icon" aria-hidden="true" />
        </button>
      </div>
      <nav v-if="!session.user?.mustChangePassword" aria-label="主导航">
        <router-link
          class="nav-link home-link"
          to="/"
          :class="{ 'is-current': route.path === '/' }"
          @click="mobileNavigation && route.fullPath === '/' && closeNavigation()"
          ><House class="icon" aria-hidden="true" />工作台</router-link
        >
        <div v-if="groups.length" class="nav-caption">业务菜单</div>
        <details
          v-for="group in groups"
          :key="group.title"
          class="nav-group"
          :open="expanded[group.title]"
          @toggle="expanded[group.title] = ($event.target as HTMLDetailsElement).open"
        >
          <summary :class="{ 'has-current': activeGroup?.title === group.title }">
            <component :is="group.icon" class="icon" aria-hidden="true" />{{ group.title }}
            <ArrowRight class="nav-arrow" aria-hidden="true" />
          </summary>
          <router-link
            v-for="item in group.items"
            :key="item.path"
            class="nav-link"
            :class="{ 'is-current': active(item.path) }"
            :to="'/' + item.path"
            @click="mobileNavigation && route.fullPath === '/' + item.path && closeNavigation()"
            >{{ item.title
            }}<span v-if="item.path === 'shipments' && overdue" class="nav-count">{{
              overdue
            }}</span></router-link
          >
        </details>
      </nav>
    </aside>
    <div class="workspace" :inert="mobileNavigation">
      <header class="topbar">
        <div class="topbar-start">
          <button
            ref="menuButton"
            class="shell-button"
            type="button"
            :aria-label="navigationVisible ? '收起导航' : '展开导航'"
            :aria-expanded="navigationVisible"
            aria-controls="workspace-sidebar"
            @click="toggleNavigation"
          >
            <Fold v-if="navigationVisible" class="icon" aria-hidden="true" />
            <Expand v-else class="icon" aria-hidden="true" />
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
            <summary :aria-label="accountName + '账户菜单'">
              <span class="account-avatar" aria-hidden="true">{{
                accountName.charAt(0).toUpperCase()
              }}</span>
              <span class="account-label">{{ accountName }}</span>
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
        >
          <router-link :to="tab.to" :aria-label="'切换到' + tab.title">{{ tab.title }}</router-link>
          <button
            type="button"
            :aria-label="'关闭' + tab.title + '标签'"
            :title="'关闭' + tab.title + '标签'"
            @click="closeWorkTab(tab)"
          >
            <Close aria-hidden="true" />
          </button>
        </div>
      </nav>
      <main class="content">
        <el-alert v-if="error" type="error" :title="error" :closable="false" /><router-view
          :key="route.path + ':' + session.companyId"
        />
      </main>
    </div>
    <AiAssistantDrawer
      v-if="showAssistant && assistantOpened"
      v-model="assistantOpen"
      :document-context="assistantDocument"
    />
  </div>
</template>
