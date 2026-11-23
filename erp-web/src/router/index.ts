import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '../stores/session'
import LoginPage from '../pages/LoginPage.vue'
import StatusPage from '../pages/StatusPage.vue'
import { defaultWorkspace, resourceSpecs } from '../pages/resources'
import { salesSpecs } from '../types/sales'
import { procurementSpecs } from '../types/procurement'
import { stockSpecs } from '../types/stock-operations'
import { reportSpecs, reportPermission } from '../types/reporting'
import { documentPermission, documentPrintPermissions } from '../types/documents'
import { importSpecs } from '../types/file-jobs'
import { crmCoreNavigation, crmCycleNavigation } from '../types/crm-navigation'
import { manufacturingNavigation } from '../types/manufacturing'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginPage },
    {
      path: '/gl/print',
      name: 'gl-print',
      component: () => import('../pages/gl/GlPrintPage.vue'),
      meta: { company: true, permission: 'gl_print:view', title: '财务打印' },
    },
    { path: '/401', name: 'expired', component: StatusPage, props: { status: 401 } },
    {
      path: '/warehouse-locations/:warehouseId/labels/:id',
      name: 'warehouse-location-label',
      component: () => import('../pages/LocationLabelPage.vue'),
      meta: {
        company: true,
        permission: 'wms_location:view',
        allPermissions: ['warehouse:view'],
        title: '库位标签',
      },
    },
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
        {
          path: 'purchase-fee-documents/:id?',
          name: 'purchase-fee-documents',
          component: () => import('../pages/PurchaseFeePage.vue'),
          meta: { company: true, title: '采购费用', permission: 'purchase_fee_doc:view', allPermissions: ['cost:view', 'goods_receipt:view'] },
        },
        {
          path: 'purchase-price-adjustments/:id?',
          name: 'purchase-price-adjustments',
          component: () => import('../pages/PurchasePriceAdjustmentPage.vue'),
          meta: {
            company: true,
            title: '采购调价',
            permission: 'price_adjustment:view',
            allPermissions: ['cost:view', 'goods_receipt:view'],
          },
        },
        ...manufacturingNavigation.flatMap(({ area, title, permission }) =>
          [
            area,
            ...(['work-orders', 'issues', 'receipts'].includes(area) ? [area + '/:id'] : []),
          ].map((path) => ({
            path: 'mfg/' + path,
            name: 'mfg-' + area + (path.endsWith('/:id') ? '-detail' : ''),
            component: () => import('../pages/mfg/ManufacturingPage.vue'),
            props: { area },
            meta: {
              company: true,
              title,
              permission,
              allPermissions: area === 'cost-periods' ? ['cost:view'] : [],
            },
          })),
        ),
        ...crmCoreNavigation.map(({ kind, title, permission }) => ({
          path: 'crm/' + kind,
          name: 'crm-' + kind,
          component: () => import('../pages/crm/CrmCorePage.vue'),
          props: { kind },
          meta: { company: true, title, permission },
        })),
        ...crmCycleNavigation.flatMap(({ kind, title, permission }) => {
          const definitions: { route: string; name: string }[] = [{ route: kind, name: kind }]
          if (kind === 'businesses' || kind === 'contracts')
            definitions.push({ route: kind + '/:id', name: kind + '-detail' })
          return definitions.map((definition) => ({
            path: 'crm/' + definition.route,
            name: 'crm-' + definition.name,
            component: () => import('../pages/crm/CrmCyclePage.vue'),
            props: { kind },
            meta: { company: true, title, permission },
          }))
        }),
        {
          path: 'crm/price-lists',
          name: 'crm-price-lists',
          component: () => import('../pages/crm/PriceListsPage.vue'),
          meta: { company: true, title: '价格表', permission: 'price_list:view' },
        },
        {
          path: 'cs/tickets',
          name: 'cs-tickets',
          component: () => import('../pages/cs/CustomerServicePage.vue'),
          props: { kind: 'tickets' },
          meta: { company: true, title: '客服工单', permission: 'cs_ticket:view' },
        },
        {
          path: 'cs/portal-users',
          name: 'cs-portal-users',
          component: () => import('../pages/cs/CustomerServicePage.vue'),
          props: { kind: 'accounts' },
          meta: { company: true, title: '门户账号', permission: 'portal_user:view' },
        },
        {
          path: 'cs/sensitive-words',
          name: 'cs-sensitive-words',
          component: () => import('../pages/cs/SensitiveWordsPage.vue'),
          meta: { company: true, title: '敏感词', permission: 'portal_sensitive_word:view' },
        },
        {
          path: 'ai/wiki',
          name: 'ai-wiki',
          component: () => import('../pages/ai/AiWikiPage.vue'),
          meta: { title: '知识百科', company: true, permission: 'ai:use' },
        },
        {
          path: 'ai/wiki/:slug',
          name: 'ai-wiki-article',
          component: () => import('../pages/ai/AiWikiPage.vue'),
          meta: { title: '知识百科', company: true, permission: 'ai:use' },
        },
        {
          path: 'ai/knowledge',
          component: () => import('../pages/ai/AiKnowledgePage.vue'),
          meta: {
            title: '知识库管理',
            company: true,
            anyPermissions: ['ai_admin:view', 'ai_admin:manage'],
          },
        },
        {
          path: 'ai/wiki-review',
          component: () => import('../pages/ai/AiWikiReviewPage.vue'),
          meta: { title: 'Wiki 审核', company: true, permission: 'ai_admin:review' },
        },
        {
          path: 'ai/admin',
          component: () => import('../pages/ai/AiAdminPage.vue'),
          meta: {
            title: 'AI 管理',
            company: true,
            anyPermissions: ['ai_admin:view', 'ai_admin:manage'],
          },
        },
        {
          path: 'ai/evaluations',
          component: () => import('../pages/ai/AiEvaluationsPage.vue'),
          meta: { title: 'AI 评测', company: true, permission: 'ai_admin:evaluate' },
        },
        ...[
          ['credit', 'credit', 'credit_limit:view', '客户信用'],
          ['aging', 'aging', 'credit_limit:view', '账龄配置'],
          ['collections', 'collections', 'collection:view', '催收计划'],
          ['bad-debt', 'bad-debt', 'bad_debt:view', '坏账处理'],
        ].map(([path, kind, permission, title]) => ({
          path: 'ar/' + path,
          name: 'ar-' + path,
          component: () => import('../pages/gl/ArPage.vue'),
          props: { kind },
          meta: { company: true, permission, title },
        })),
        ...[
          ['book', 'GlBookPage', 'gl_config:view', '账套与期初'],
          ['config', 'GlConfigPage', 'gl_config:view', '财务基础配置'],
          ['vouchers', 'GlVouchersPage', 'gl_voucher:view', '总账凭证'],
          ['ledgers', 'GlLedgersPage', 'gl_ledger:view', '财务账簿'],
          ['reports', 'GlReportsPage', 'gl_report:view', '财务报表'],
          ['closing', 'GlClosingPage', 'gl_closing:close', '财务结账'],
        ].map(([path, page, permission, title]) => ({
          path: 'gl/' + path,
          name: 'gl-' + path,
          component: () => import(`../pages/gl/${page}.vue`),
          meta: { company: true, permission, title },
        })),
        {
          path: 'ar/credit/:id',
          name: 'ar-credit-detail',
          component: () => import('../pages/gl/ArPage.vue'),
          props: { kind: 'credit' },
          meta: { company: true, permission: 'credit_limit:view', title: '临时信用额度调增' },
        },
        {
          path: 'gl/vouchers/new',
          name: 'gl-voucher-new',
          component: () => import('../pages/gl/GlVoucherEditPage.vue'),
          meta: { company: true, permission: 'gl_voucher:edit', title: '新增手工凭证' },
        },
        {
          path: 'gl/vouchers/:id',
          name: 'gl-voucher-detail',
          component: () => import('../pages/gl/GlVoucherEditPage.vue'),
          meta: { company: true, permission: 'gl_voucher:view', title: '凭证详情' },
        },

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
        {
          path: 'wms-tasks',
          name: 'wms-tasks',
          component: () => import('../pages/PutawayTasksPage.vue'),
          meta: { permission: 'wms_task:view', company: true, title: '上架任务' },
        },
        {
          path: 'warehouse-work',
          name: 'warehouse-work',
          component: () => import('../pages/WmsWorkPage.vue'),
          meta: {
            anyPermissions: [
              'wms_task:view',
              'wms_task:configure',
              'wms_move:view',
              'wms_pack:view',
              'label:print',
            ],
            company: true,
            title: '仓储作业',
          },
        },
        {
          path: 'inventory-trace',
          name: 'inventory-trace',
          component: () => import('../pages/InventoryTracePage.vue'),
          meta: {
            company: true,
            anyPermissions: [
              'batch:view',
              'batch:trace',
              'serial:trace',
              'inventory_trace:customer',
            ],
            title: '批次与序列号',
          },
        },
        {
          path: 'quality',
          name: 'quality',
          component: () => import('../pages/QualityPage.vue'),
          meta: {
            company: true,
            anyPermissions: ['qc_inspection:view', 'qc_setting:view'],
            title: '来料质检',
          },
        },
        ...(['in', 'out'] as const).flatMap((direction) =>
          ['', '/:id'].map((suffix) => ({
            path: 'stock-misc-' + direction + suffix,
            name: 'stock-misc-' + direction + (suffix ? '-detail' : ''),
            props: { direction },
            component: () => import('../pages/StockMiscPage.vue'),
            meta: {
              company: true,
              permission: 'stock_misc_doc:view',
              title: direction === 'in' ? '其他入库' : '其他出库',
            },
          })),
        ),
        {
          path: 'e-invoices',
          name: 'e-invoices',
          component: () => import('../pages/EInvoicePage.vue'),
          meta: { company: true, permission: 'einv:view', title: '数电发票' },
        },
        {
          path: 'supply-reconciliation',
          name: 'supply-reconciliation',
          component: () => import('../pages/SupplyReconciliationPage.vue'),
          meta: { company: true, permission: 'reconciliation:view', title: '供应链对账' },
        },
        {
          path: 'brands',
          name: 'brands',
          component: () => import('../pages/BrandsPage.vue'),
          meta: { permission: 'brand:view', title: '品牌' },
        },
        {
          path: 'skus/:id/tracking',
          name: 'sku-tracking',
          component: () => import('../pages/SkuTrackingPage.vue'),
          meta: { permission: 'sku:view', title: 'SKU 供应链设置' },
        },
        {
          path: 'warehouse-locations',
          name: 'warehouse-locations',
          component: () => import('../pages/WarehouseLocationsPage.vue'),
          meta: {
            company: true,
            permission: 'wms_location:view',
            allPermissions: ['warehouse:view'],
            title: '库区库位',
          },
        },
        {
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('../pages/dashboard/DashboardPage.vue'),
          meta: { company: true, permission: 'dashboard:view', title: '经营驾驶舱' },
        },
        {
          path: 'alerts',
          name: 'alerts',
          component: () => import('../pages/alert/AlertCenterPage.vue'),
          meta: { company: true, permission: 'alert:view', title: '预警中心' },
        },
        {
          path: 'print-templates',
          name: 'print-templates',
          component: () => import('../pages/print/TemplateDesignPage.vue'),
          meta: { company: true, permission: 'print_template:view', title: '打印模板' },
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
              'gl_export:create',
              ...reportSpecs.map((spec) => reportPermission(spec.type, 'export')),
              ...documentPrintPermissions,
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
            component:
              spec.path === 'products'
                ? () => import('../pages/ProductCatalogPage.vue')
                : () => import('../pages/ResourcePage.vue'),
            props: spec.path === 'products' ? undefined : { resource: spec.path },
            meta: {
              permission: spec.permission + ':view',
              company: Boolean(spec.company),
              title: spec.title,
            },
          })),
        ...(['todo', 'done', 'mine', 'copies', 'all'] as const).map((kind) => ({
          path: 'workflow/' + kind,
          component: () => import('../pages/workflow/WorkflowCenterPage.vue'),
          props: { kind },
          meta: {
            title: {
              todo: '待我审批',
              done: '我的已办',
              mine: '我的申请',
              copies: '抄送我的',
              all: '全部流程',
            }[kind],
            company: true,
            ...(kind === 'all' ? { permission: 'workflow_instance:view_all' } : {}),
          },
        })),
        {
          path: 'workflow/instances/:id',
          component: () => import('../pages/workflow/WorkflowCenterPage.vue'),
          props: { kind: 'todo' },
          meta: { title: '审批详情', company: true },
        },
        {
          path: 'workflow/models',
          component: () => import('../pages/workflow/WorkflowModelsPage.vue'),
          meta: { title: '流程模型与绑定', company: true, permission: 'workflow_model:view' },
        },
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
          path: 'login-settings/sso',
          name: 'login-settings-sso',
          component: () => import('../pages/AuthPlatformPage.vue'),
          meta: { title: '企业单点登录', permission: 'tenant:login_settings' },
        },
        {
          path: 'security-policy',
          name: 'security-policy',
          component: () => import('../pages/AuthPlatformPage.vue'),
          meta: { title: '企业安全策略', permission: 'tenant:security_policy' },
        },
        {
          path: 'account/security',
          name: 'account-security',
          component: () => import('../pages/AccountSecurityPage.vue'),
          meta: { title: '账户安全' },
        },
        {
          path: 'custom-fields',
          name: 'custom-fields',
          component: () => import('../pages/CustomFieldsPage.vue'),
          meta: { title: '自定义字段', company: true, permission: 'custom_field:manage' },
        },
        {
          path: 'code-rules',
          name: 'code-rules',
          component: () => import('../pages/CodeRulesPage.vue'),
          meta: { title: '编码规则', company: true, permission: 'code_rule:manage' },
        },
        {
          path: 'open-platform',
          name: 'open-platform',
          component: () => import('../pages/OpenPlatformPage.vue'),
          meta: { title: '开放平台', permission: 'open_platform:view' },
        },
        {
          path: 'workflow/forms',
          name: 'workflow-forms',
          component: () => import('../pages/workflow/WorkflowFormsPage.vue'),
          meta: { title: '表单设计', company: true, permission: 'workflow_form:manage' },
        },
        {
          path: 'workflow/start',
          name: 'workflow-start',
          component: () => import('../pages/workflow/WorkflowFormsPage.vue'),
          meta: { title: '发起通用审批', company: true, permission: 'wf_generic_request:create' },
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
  if (
    Array.isArray(to.meta.allPermissions) &&
    !to.meta.allPermissions.every(
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
