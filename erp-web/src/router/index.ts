import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '../stores/session'
import LoginPage from '../pages/LoginPage.vue'
import StatusPage from '../pages/StatusPage.vue'
import { defaultWorkspace, resourceSpecs } from '../pages/resources'
import { salesSpecs } from '../types/sales'
import { procurementSpecs } from '../types/procurement'
import { stockSpecs } from '../types/stock-operations'
import { reportSpecs, reportPermission } from '../types/reporting'
import { documentPermission } from '../types/documents'
import { importSpecs } from '../types/file-jobs'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginPage },
    { path: '/401', name: 'expired', component: StatusPage, props: { status: 401 } },
    { path: '/403', name: 'forbidden', component: StatusPage, props: { status: 403 } },
    {
      path: '/service-unavailable',
      name: 'unavailable',
      component: StatusPage,
      props: { status: 503 },
    },
    {
      path: '/',
      component: () => import('../components/AppShell.vue'),
      children: [
        ...[
          ['dict-types', 'dict:view', '数据字典'],
          ['dict-items', 'dict:view', '字典项'],
          ['notices', 'notice:view', '通知公告'],
          ['notify-templates', 'notify_template:view', '消息模板'],
          ['notify-send-logs', 'notify_log:view', '消息发送日志'],
          ['notify-robot', 'notify_robot:manage', '企业微信机器人'],
          ['configs', 'sys_config:view', '系统参数'],
          ['jobs', 'job:view', '定时任务'],
          ['login-logs', 'login_log:view', '登录日志'],
          ['online-users', 'online_user:view', '在线用户'],
          ['api-access-logs', 'api_log:view', 'API访问日志'],
          ['api-error-logs', 'api_error:view', 'API异常日志'],
        ].map(([kind, permission, title]) => ({
          path: 'sys/' + kind,
          name: 'sys-' + kind,
          component: () => import('../pages/PlatformAdministrationPage.vue'),
          props: { kind },
          meta: {
            permission,
            title,
            company: ['api-access-logs', 'api-error-logs', 'notify-send-logs'].includes(kind!),
          },
        })),

        {
          path: 'messages',
          name: 'messages',
          component: () => import('../pages/MessageCenterPage.vue'),
          meta: { company: true, title: '消息中心' },
        },
        {
          path: '',
          name: 'home',
          component: () => import('../pages/WorkbenchPage.vue'),
          meta: { title: '工作台' },
        },
        {
          path: 'reports',
          name: 'reports',
          redirect: () =>
            '/reports/' +
            (reportSpecs.find((spec) => useSessionStore().can(reportPermission(spec.type)))?.type ??
              'inventory-summary'),
        },
        ...(['sales-orders', 'purchase-orders'] as const).flatMap((kind) =>
          ['new', ':id/edit'].map((action) => ({
            path: kind + '/' + action,
            name: kind + '-' + (action === 'new' ? 'new' : 'edit'),
            component: () => import('../pages/OrderEditPage.vue'),
            props: { kind },
            meta: {
              company: true,
              permission:
                (kind === 'sales-orders' ? 'sales_order:' : 'purchase_order:') +
                (action === 'new' ? 'create' : 'update'),
              title: action === 'new' ? '新建订单' : '编辑订单',
            },
          })),
        ),
        ...salesSpecs.flatMap((spec) => [
          {
            path: spec.path,
            name: spec.path,
            component: () => import('../pages/SalesListPage.vue'),
            props: { kind: spec.path },
            meta: { permission: spec.permission + ':view', company: true, title: spec.title },
          },
          {
            path: spec.path + '/:id',
            name: spec.path + '-detail',
            component: () => import('../pages/SalesDetailPage.vue'),
            props: { kind: spec.path },
            meta: { permission: spec.permission + ':view', company: true, title: spec.title },
          },
        ]),
        ...procurementSpecs.flatMap((spec) => [
          {
            path: spec.path,
            name: spec.path,
            component: () => import('../pages/ProcurementListPage.vue'),
            props: { kind: spec.path },
            meta: { permission: spec.permission + ':view', company: true, title: spec.title },
          },
          {
            path: spec.path + '/:id',
            name: spec.path + '-detail',
            component: () => import('../pages/ProcurementDetailPage.vue'),
            props: { kind: spec.path },
            meta: { permission: spec.permission + ':view', company: true, title: spec.title },
          },
        ]),
        ...stockSpecs.flatMap((spec) => [
          {
            path: spec.path,
            name: spec.path,
            component: () => import('../pages/StockOperationPage.vue'),
            props: { kind: spec.path },
            meta: { permission: spec.permission + ':view', company: true, title: spec.title },
          },
          {
            path: spec.path + '/:id',
            name: spec.path + '-detail',
            component: () => import('../pages/StockOperationPage.vue'),
            props: { kind: spec.path },
            meta: { permission: spec.permission + ':view', company: true, title: spec.title },
          },
        ]),
        {
          path: 'accounting-periods',
          name: 'accounting-periods',
          component: () => import('../pages/PeriodsPage.vue'),
          meta: { permission: 'period:view', company: true, title: '会计期间与关账' },
        },
        {
          path: 'settlements',
          name: 'settlements',
          component: () => import('../pages/SettlementPage.vue'),
          meta: { permission: 'settlement:view', company: true, title: '核销工作台' },
        },
        {
          path: 'inventory-balances',
          name: 'inventory-balances',
          component: () => import('../pages/InventoryPage.vue'),
          meta: { permission: 'inventory:view', company: true, title: '实时库存' },
        },
        ...reportSpecs.map((spec) => ({
          path: 'reports/' + spec.type,
          name: 'report-' + spec.type,
          component: () => import('../pages/ReportPage.vue'),
          props: { type: spec.type },
          meta: { permission: reportPermission(spec.type), company: true, title: spec.title },
        })),
        {
          path: 'file-jobs',
          name: 'file-jobs',
          component: () => import('../pages/FileJobsPage.vue'),
          meta: {
            company: true,
            title: '导入与导出',
            anyPermissions: [
              ...importSpecs.map((spec) => 'import:' + spec.type),
              ...reportSpecs.map((spec) => reportPermission(spec.type, 'export')),
            ],
          },
        },
        {
          path: 'audit-logs',
          name: 'audit-logs',
          component: () => import('../pages/AuditPage.vue'),
          meta: { title: '操作审计', anyPermissions: ['audit:view', 'admin:outbox'] },
        },
        {
          path: 'print/:type/:id',
          name: 'document-print',
          component: () => import('../pages/PrintPage.vue'),
          meta: { title: '打印预览', company: true, documentPrint: true },
        },
        ...Object.values(resourceSpecs)
          .filter((spec) => spec.path !== 'org/posts')
          .filter((spec) => !['audit-logs', 'accounting-periods'].includes(spec.path))
          .map((spec) => ({
            path: spec.path,
            name: spec.path,
            component: () => import('../pages/ResourcePage.vue'),
            props: { resource: spec.path },
            meta: {
              permission: spec.permission + ':view',
              company: Boolean(spec.company),
              title: spec.title,
            },
          })),
        {
          path: 'organization',
          name: 'organization',
          component: () => import('../pages/org/OrganizationPage.vue'),
          meta: { title: '部门与岗位', anyPermissions: ['org_department:view', 'org_post:view'] },
        },
        {
          path: 'warehouses',
          name: 'warehouses',
          component: () => import('../pages/WarehousePage.vue'),
        },
        {
          path: 'login-settings',
          name: 'login-settings',
          component: () => import('../pages/LoginSettingsPage.vue'),
          meta: { title: '登录方式设置', permission: 'tenant:login_settings' },
        },
        {
          path: 'account/password',
          name: 'password',
          component: () => import('../pages/PasswordPage.vue'),
        },
        {
          path: 'account/login-methods',
          name: 'account-login-methods',
          component: () => import('../pages/AccountLoginMethodsPage.vue'),
          meta: { title: '我的登录方式' },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', name: 'missing', component: StatusPage, props: { status: 404 } },
  ],
})

router.beforeEach(async (to) => {
  const session = useSessionStore()
  if (['expired', 'forbidden', 'missing', 'unavailable'].includes(String(to.name))) return true
  try {
    await session.restore()
  } catch {
    // 登录允许手动重试，其他页面保留服务不可用的独立状态。
    if (to.name !== 'login') return { name: 'unavailable' }
  }
  if (!session.user) return to.name === 'login' ? true : { name: 'login' }
  if (session.user.mustChangePassword && to.name !== 'password') return { name: 'password' }
  if (to.name === 'login') return defaultWorkspace(session.can, Boolean(session.company))
  if (to.name === 'warehouses' && (!session.company || !session.can('warehouse:view')))
    return { name: 'forbidden' }
  if (to.meta.company && !session.company) return { name: 'forbidden' }
  if (
    Array.isArray(to.meta.anyPermissions) &&
    !to.meta.anyPermissions.some(
      (permission) => typeof permission === 'string' && session.can(permission),
    )
  )
    return { name: 'forbidden' }
  if (to.meta.documentPrint && !session.can(documentPermission(String(to.params.type)) + ':print'))
    return { name: 'forbidden' }
  if (
    typeof to.meta.permission === 'string' &&
    (!session.can(to.meta.permission) || (to.meta.company && !session.company))
  )
    return { name: 'forbidden' }
  return true
})
