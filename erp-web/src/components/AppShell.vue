<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { OfficeBuilding, SwitchButton, Lock, Bell, User } from '@element-plus/icons-vue'
import { useSessionStore } from '../stores/session'
import { messageOf, isCancelled } from '../api/http'
import { workbenchSummary } from '../api/workbench'
import BrandMark from './BrandMark.vue'
import { reportSpecs, reportPermission } from '../types/reporting'
import { importSpecs } from '../types/file-jobs'
import { confirmCompanyLeave } from '../utils/unsaved'
const route = useRoute(),
  router = useRouter(),
  session = useSessionStore()
function selectCompany(id: string): void {
  if (id !== session.companyId && confirmCompanyLeave()) session.selectCompany(id)
}
const loggingOut = ref(false),
  error = ref(''),
  overdue = ref(0),
  taskCount = ref(0)
const title = computed(() =>
  String(route.meta.title ?? (route.name === 'password' ? '账户设置' : '工作台')),
)
interface NavigationItem {
  path: string
  title: string
  permission?: string
  any?: string[]
}
const definitions: { title: string; items: NavigationItem[] }[] = [
  {
    title: '销售',
    items: [
      { path: 'sales-orders', title: '销售订单', permission: 'sales_order:view' },
      { path: 'shipments', title: '发货与签收', permission: 'shipment:view' },
      { path: 'sales-returns', title: '销售退货', permission: 'sales_return:view' },
    ],
  },
  {
    title: '采购',
    items: [
      { path: 'purchase-requisitions', title: '请购管理', permission: 'purchase_requisition:view' },
      { path: 'purchase-orders', title: '采购订单', permission: 'purchase_order:view' },
      { path: 'goods-receipts', title: '采购收货', permission: 'goods_receipt:view' },
      { path: 'purchase-returns', title: '采购退货', permission: 'purchase_return:view' },
    ],
  },
  {
    title: '库存',
    items: [
      { path: 'inventory-balances', title: '实时库存', permission: 'inventory:view' },
      { path: 'stock-transfers', title: '库存调拨', permission: 'stock_transfer:view' },
      { path: 'stock-counts', title: '库存盘点', permission: 'stock_count:view' },
      { path: 'stock-adjustments', title: '库存调整', permission: 'stock_adjustment:view' },
    ],
  },
  {
    title: '资金与往来',
    items: [
      { path: 'receipts', title: '收款登记', permission: 'receipt:view' },
      { path: 'payments', title: '付款登记', permission: 'payment:view' },
      { path: 'refund-requests', title: '退款申请', permission: 'refund_request:view' },
      { path: 'account-transfers', title: '账户转账', permission: 'account_transfer:view' },
      { path: 'settlements', title: '核销工作台', permission: 'settlement:view' },
      { path: 'invoice-registrations', title: '开票登记', permission: 'invoice_registration:view' },
      { path: 'opening-entries', title: '往来与资金期初', permission: 'opening_entry:view' },
    ],
  },
  {
    title: '财务期间',
    items: [
      { path: 'document-reversals', title: '单据冲销', permission: 'reversal:view' },
      { path: 'accounting-periods', title: '会计期间与关账', permission: 'period:view' },
    ],
  },
  {
    title: '报表与数据',
    items: [
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
          ...reportSpecs.map((spec) => reportPermission(spec.type, 'export')),
        ],
      },
    ],
  },
  {
    title: '基础资料',
    items: [
      { path: 'partners', title: '往来单位', permission: 'partner:view' },
      { path: 'skus', title: '商品与 SKU', any: ['sku:view', 'product:view'] },
      { path: 'product-categories', title: '商品分类', permission: 'product_category:view' },
      { path: 'units', title: '计量单位', permission: 'unit:view' },
      { path: 'warehouses', title: '仓库管理', permission: 'warehouse:view' },
      { path: 'cash-accounts', title: '结算账户', permission: 'cash_account:view' },
      { path: 'income-expense-items', title: '收支项目', permission: 'income_expense_item:view' },
    ],
  },
  {
    title: '系统管理',
    items: [
      { path: 'companies', title: '公司与参数', permission: 'company:view' },
      { path: 'users', title: '用户与角色', any: ['user:view', 'role:view'] },
      { path: 'audit-logs', title: '操作审计', any: ['audit:view', 'admin:outbox'] },
    ],
  },
]
const groups = computed(() =>
  session.user?.mustChangePassword
    ? []
    : definitions
        .map((group) => ({
          ...group,
          items: group.items
            .filter((item) =>
              item.permission ? session.can(item.permission) : item.any?.some(session.can),
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
watch(
  () => route.path,
  () => {
    for (const group of groups.value)
      if (group.items.some((item) => active(item.path))) expanded.value[group.title] = true
  },
  { immediate: true },
)
function active(path: string): boolean {
  return path === 'reports'
    ? route.path.startsWith('/reports/')
    : path === 'skus'
      ? ['/skus', '/products'].includes(route.path)
      : path === 'users'
        ? ['/users', '/roles'].includes(route.path)
        : route.path === '/' + path || route.path.startsWith('/' + path + '/')
}
watch(
  () => session.companyId,
  async () => {
    overdue.value = 0
    taskCount.value = 0
    if (!session.companyId || session.user?.mustChangePassword) return
    const company = session.companyId
    try {
      const data = await workbenchSummary()
      if (company === session.companyId) {
        overdue.value = data.blocks.find((block) => block.key === 'shipments')?.count ?? 0
        taskCount.value = data.todos.length
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
  <div class="shell">
    <aside class="sidebar">
      <BrandMark />
      <nav aria-label="主导航">
        <router-link
          v-if="!session.user?.mustChangePassword"
          class="nav-link home-link"
          to="/"
          :class="{ 'is-current': route.path === '/' }"
          >工作台</router-link
        >
        <details
          v-for="group in groups"
          :key="group.title"
          class="nav-group"
          :open="expanded[group.title]"
          @toggle="expanded[group.title] = ($event.target as HTMLDetailsElement).open"
        >
          <summary>{{ group.title }}</summary>
          <router-link
            v-for="item in group.items"
            :key="item.path"
            class="nav-link"
            :class="{ 'is-current': active(item.path) }"
            :to="'/' + item.path"
            >{{ item.title
            }}<span v-if="item.path === 'shipments' && overdue" class="nav-count">{{
              overdue
            }}</span></router-link
          >
        </details>
      </nav>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <span class="breadcrumb">{{ title }}</span>
        <div class="topbar-actions">
          <OfficeBuilding class="icon company-icon" aria-hidden="true" /><label
            for="company-select"
            class="sr-only"
            >当前公司</label
          ><el-select
            id="company-select"
            class="company-select"
            :model-value="session.companyId"
            placeholder="请选择公司"
            @change="selectCompany"
            ><el-option
              v-for="company in session.user?.companies"
              :key="company.id"
              :value="company.id"
              :label="company.name"
          /></el-select>
          <router-link
            v-if="!session.user?.mustChangePassword"
            class="workbench-bell"
            to="/"
            :aria-label="'查看待办，' + taskCount + '条'"
            ><Bell class="icon" /><span v-if="taskCount" class="nav-count">{{
              taskCount
            }}</span></router-link
          >
          <details class="account-menu">
            <summary>
              <User class="icon" /><span>{{
                session.user?.displayName || session.user?.username
              }}</span>
            </summary>
            <div>
              <router-link to="/account/password"><Lock class="icon" />修改密码</router-link
              ><el-button text :loading="loggingOut" @click="logout">退出登录</el-button>
            </div>
          </details>
          <el-button
            text
            :loading="loggingOut"
            aria-label="退出登录"
            title="退出登录"
            @click="logout"
            ><SwitchButton class="icon"
          /></el-button>
        </div>
      </header>
      <main class="content">
        <el-alert v-if="error" type="error" :title="error" :closable="false" /><router-view
          :key="route.path + ':' + session.companyId"
        />
      </main>
    </div>
  </div>
</template>
