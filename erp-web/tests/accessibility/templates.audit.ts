import { expect, test } from '@playwright/test'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { webTestStorage } from '../../scripts/test-storage'
import { verifyTableHeaders } from './table-headers'

interface AxeRule {
  id: string
  impact: string | null
  nodes: unknown[]
}
interface AxeResults {
  violations: AxeRule[]
  incomplete: AxeRule[]
  [key: string]: unknown
}

const storage = webTestStorage()
const root = storage.output(
  'a11y/evidence',
  fileURLToPath(new URL('../../../.local/ui2-a11y/', import.meta.url)),
  'ERP_A11Y_OUTPUT_DIR',
)
const phase = process.env.ERP_A11Y_PHASE === 'baseline' ? 'baseline' : 'verified'
const output = join(root, phase)
mkdirSync(output, { recursive: true })
const axeScriptPath = process.env.ERP_AXE_SCRIPT_PATH
if (!axeScriptPath || !existsSync(axeScriptPath)) {
  throw new Error('请先隔离安装 axe-core，并通过 ERP_AXE_SCRIPT_PATH 指定 axe.min.js 路径')
}
const axe = resolve(axeScriptPath)
const department = {
  id: '10',
  parentId: '0',
  code: 'SALES',
  name: '销售部',
  path: '/10/',
  depth: 1,
  sortNo: 0,
  status: 'active',
  version: 1,
  memberCount: 2,
  leaderUserId: '900',
  leaderName: '张宁',
}
const line = {
  id: '80',
  lineNo: 1,
  skuId: '41',
  unitId: '51',
  warehouseId: '61',
  sku: { id: '41', name: '标准装配件', code: 'SKU-01' },
  unit: { id: '51', name: '件' },
  warehouse: { id: '61', name: '成品仓' },
  qty: '10.000000',
  unitPrice: '120.00000000',
  taxRate: '0.13',
  lineDiscountAmount: '0',
  receivableAmount: '1200.00',
  taxAmount: '138.05',
  baseQty: '10.000000',
}
const order = {
  id: '70',
  documentNo: 'SO-C01-202611-000070',
  status: 'draft',
  version: 3,
  customerId: '31',
  customer: { id: '31', code: 'C01', name: '华东商贸' },
  salesUserId: '900',
  salesUserName: '张宁',
  businessDate: '2026-11-18',
  deliveryDate: '2026-11-25',
  priceTaxInclusive: true,
  orderDiscountAmount: '0',
  goodsAmount: '1200.00',
  taxAmount: '138.05',
  totalAmount: '1200.00',
  untaxedAmount: '1061.95',
  remark: '合同确认后安排交付',
  fulfillmentStatus: 'none',
  settlementStatus: 'none',
  fees: [],
  lines: [line],
  allowedActions: ['update', 'submit', 'delete'],
}
const todo = {
  id: '91',
  instanceId: '90',
  title: '销售订单审批',
  businessType: 'sales_order',
  businessNo: order.documentNo,
  status: 'running',
  nodeName: '销售经理复核',
  taskStatus: 'pending',
  taskVersion: 1,
  modelVersion: 1,
  starterName: '李娜',
  totalAmount: '1200.00',
  itemCreatedAt: '2026-11-18T01:00:00Z',
}
const workflow = {
  id: '90',
  businessType: 'sales_order',
  businessId: '70',
  businessNo: order.documentNo,
  title: '销售订单审批',
  status: 'running',
  starterId: '901',
  modelVersion: 1,
  version: 1,
  startedAt: '2026-11-18T01:00:00Z',
  summary: { totalAmount: '1200.00', customerName: '华东商贸' },
  tasks: [
    {
      id: '91',
      instanceId: '90',
      nodeKey: 'manager',
      nodeName: '销售经理复核',
      nodeKind: 'approver',
      assigneeId: '900',
      assigneeName: '张宁',
      status: 'pending',
      version: 1,
      canHandle: true,
      createdAt: '2026-11-18T01:00:00Z',
    },
  ],
  actions: [{ id: '92', actorName: '李娜', action: 'start', createdAt: '2026-11-18T01:00:00Z' }],
  canCancel: false,
  canTerminate: false,
}
const permissions = [
  'sales_order:view',
  'sales_order:create',
  'sales_order:update',
  'sales_order:submit',
  'sales_order:approve',
  'sales_order:delete',
  'sales_order:print',
  'shipment:view',
  'receipt:view',
  'settlement:view',
  'purchase_order:view',
  'purchase_order:create',
  'sku:view',
  'partner:view',
  'warehouse:view',
  'org_department:view',
  'org_department:create',
  'org_department:update',
  'user:view',
  'user:update',
  'report:sales_summary',
  'report:purchase_summary',
  'report:receivable_aging',
  'report:inventory_summary',
  'cost:view',
  'period:view',
  'dashboard:view',
  'alert:view',
]
const templates = [
  { key: 'workbench', label: '工作台', path: '/', ready: '.metric-card' },
  { key: 'list', label: '单据列表', path: '/sales-orders', ready: '.document-list-head' },
  { key: 'detail', label: '单据详情', path: '/sales-orders/70', ready: '.order-overview' },
  { key: 'editor', label: '单据编辑', path: '/sales-orders/70/edit', ready: '.editable-table' },
  {
    key: 'organization',
    label: '部门主从资料',
    path: '/organization',
    ready: '.department-tree-panel',
  },
  {
    key: 'product-catalog',
    label: '商品分类主从',
    path: '/products',
    ready: '.category-panel .el-tree-node',
  },
  { key: 'approval', label: '审批中心', path: '/workflow/todo', ready: '.approval-preview h2' },
  { key: 'login', label: '登录页', path: '/login', ready: '#login-title' },
  {
    key: 'not-found',
    label: '页面不存在',
    path: '/ui2-verification/missing',
    ready: '.status-code',
  },
]

for (const viewport of [
  { width: 1440, height: 900 },
  { width: 375, height: 812 },
]) {
  for (const item of templates) {
    test(item.label + ' · ' + viewport.width, async ({ page }) => {
      test.setTimeout(120_000)
      const errors: string[] = []
      const calls = new Set<string>()
      page.on('pageerror', (error) => errors.push(error.message))
      await page.emulateMedia({ reducedMotion: 'reduce' })
      await page.setViewportSize(viewport)
      await page.routeWebSocket('**/ws/**', (socket) => socket.close())
      await page.route('**/api/v1/**', (route) => {
        const request = route.request()
        const url = new URL(request.url())
        const path = url.pathname.slice('/api/v1'.length)
        calls.add(request.method() + ' ' + path)
        const ok = (data: unknown): Promise<void> =>
          route.fulfill({ json: { code: 200, message: '操作成功', data } })
        if (path === '/system/version') return ok({ apiMajor: 2 })
        if (path === '/auth/me')
          return item.key === 'login'
            ? route.fulfill({ status: 401, json: { code: 401, message: '请登录' } })
            : ok({
                id: '900',
                username: 'auditor',
                displayName: '张宁',
                mustChangePassword: false,
                permissions:
                  item.key === 'product-catalog'
                    ? [
                        ...permissions,
                        'product:view',
                        'product:create',
                        'product:update',
                        'product_category:view',
                        'unit:view',
                      ]
                    : permissions,
                companies: [
                  { id: '1', code: 'C01', name: '华东商贸', allWarehouses: true, warehouseIds: [] },
                ],
              })
        if (path === '/auth/methods')
          return ok({ password: true, sms: true, email: true, wechat: true, sso: [] })
        if (path === '/notify/unread-count') return ok({ total: 3 })
        if (path === '/notify/important-notices') return ok([])
        if (path === '/alerts/unhandled-count') return ok({ total: 2 })
        if (path === '/workbench/summary') {
          const task = {
            id: '70',
            kind: 'sales-orders',
            label: '销售订单待审核',
            documentNo: order.documentNo,
            partnerName: '华东商贸',
            amount: '1200.00',
            businessDate: '2026-11-18',
            createdAt: '2026-11-18T01:00:00Z',
            url: '/sales-orders/70',
          }
          return ok({
            asOf: '2026-11-18T01:00:00Z',
            blocks: [
              {
                key: 'sales-orders',
                label: '销售订单待审核',
                count: 1,
                url: '/sales-orders?status=pending',
              },
              {
                key: 'purchase-orders',
                label: '采购订单待审核',
                count: 0,
                url: '/purchase-orders?status=pending',
              },
              { key: 'workflow', label: '流程审批待办', count: 1, url: '/workflow/todo' },
            ],
            todos: [task],
            todosByType: { 'sales-orders': [task], workflow: [] },
            announcements: [
              {
                id: '500',
                title: '十一月盘点安排',
                level: 'normal',
                publishAt: '2026-11-18T01:00:00Z',
              },
            ],
            inventoryAlerts: [],
            periods: [
              {
                id: '15',
                period: '2026-11',
                status: 'open',
                startDate: '2026-11-01',
                endDate: '2026-11-30',
              },
            ],
          })
        }
        if (path === '/workbench/trade-summary')
          return ok({
            asOf: '2026-11-18T01:00:00Z',
            dataSource: 'primary',
            sales: { today: '1200.00', yesterday: '800.00', month: '123456.78', year: '987654.32' },
            purchase: {
              today: '650.00',
              yesterday: '300.00',
              month: '80200.00',
              year: '532100.00',
            },
            receivable: '25000.00',
            inventory: '168000.00',
            comparison: {
              sales: { previousMonth: '100000.00', previousYear: '80000.00' },
              purchase: { previousMonth: '70500.00', previousYear: '60200.00' },
              receivable: { previousMonth: '21000.00', previousYear: '20000.00' },
              inventory: { previousMonth: '159000.00', previousYear: '148000.00' },
            },
            trend: Array.from({ length: 12 }, (_, index) => ({
              month: new Date(Date.UTC(2025, 11 + index, 1)).toISOString().slice(0, 7),
              sales: String(10000 + index * 1000),
              purchase: String(5000 + index * 600),
              receivable: String(20000 + index * 400),
              inventory: String(150000 + index * 1000),
            })),
          })
        if (path === '/document-browser/sales-orders')
          return ok({
            items: Array.from({ length: 15 }, (_, index) => ({
              ...order,
              id: String(70 + index),
              documentNo: 'SO-C01-202611-' + String(70 + index).padStart(6, '0'),
            })),
            total: 15,
            nextCursor: null,
            statusCounts: { draft: 15, pending: 0, approved: 0 },
          })
        if (path === '/sales-orders/70')
          return ok({
            ...order,
            lines:
              item.key === 'editor'
                ? Array.from({ length: 50 }, (_, index) => ({
                    ...line,
                    id: String(80 + index),
                    lineNo: index + 1,
                  }))
                : [line],
          })
        if (path === '/sales-orders/70/settlement-summary')
          return ok({ allocatedCashAmount: '235.80', openAmount: '1194.19', scope: 'visible' })
        if (path === '/sales-orders/70/reservations' || path.includes('/related/'))
          return ok({ items: [], nextCursor: null })
        if (path === '/pricing/preview') {
          const body = request.postDataJSON() as { lines: Record<string, unknown>[] }
          return ok({
            ...order,
            lines: body.lines.map((row) => ({ ...row, receivableAmount: '1200.00' })),
          })
        }
        if (path === '/product-categories')
          return ok([
            {
              id: '10',
              code: 'ASSEMBLY',
              name: '装配件',
              status: 'active',
              children: [
                { id: '11', parentId: '10', code: 'PRECISION', name: '精密组件', status: 'active' },
              ],
            },
          ])
        if (path === '/products')
          return ok({
            items: [
              {
                id: '30',
                code: 'P-01',
                name: '标准组件',
                categoryId: '10',
                status: 'active',
                version: 1,
              },
              {
                id: '31',
                code: 'P-02',
                name: '高精度总成',
                categoryId: '11',
                status: 'active',
                version: 1,
              },
            ],
            nextCursor: null,
          })
        if (path === '/partners')
          return ok({
            items: [
              {
                id: '31',
                code: 'C01',
                name: '华东商贸',
                status: 'active',
                roles: ['customer', 'supplier'],
              },
            ],
            nextCursor: null,
          })
        if (path === '/skus')
          return ok({
            items: [{ id: '41', code: 'SKU-01', name: '标准装配件', status: 'active' }],
            nextCursor: null,
          })
        if (path === '/skus/41')
          return ok({
            id: '41',
            code: 'SKU-01',
            name: '标准装配件',
            status: 'active',
            defaultTaxRate: '0.13',
            units: [{ unitId: '51', unitName: '件', rateToBase: '1', isBase: true }],
          })
        if (path === '/warehouses')
          return ok({
            items: [{ id: '61', code: 'W01', name: '成品仓', status: 'active' }],
            nextCursor: null,
          })
        if (path === '/org/business-users')
          return ok({ items: [{ id: '900', name: '张宁' }], nextCursor: null })
        if (path === '/org/company-users')
          return ok({
            items: [{ id: '900', username: 'auditor', displayName: '张宁', status: 'active' }],
            nextCursor: null,
          })
        if (path === '/org/departments/tree')
          return ok([
            department,
            {
              ...department,
              id: '11',
              parentId: '10',
              code: 'EAST',
              name: '华东组',
              path: '/10/11/',
              depth: 2,
            },
          ])
        if (/^\/org\/departments\/\d+\/members$/.test(path))
          return ok({
            items: [{ id: '900', username: 'auditor', displayName: '张宁', status: 'active' }],
            nextCursor: null,
          })
        if (path === '/workflow/tasks/todo') return ok({ items: [todo], nextCursor: null })
        if (path === '/workflow/instances/90') return ok(workflow)
        if (path === '/workflow/progress') return ok({ instances: [], tasks: [] })
        if (path === '/workflow/start-options') return ok({ nodes: [] })
        if (path === '/dicts') return ok({ version: '1', unchanged: false, items: {} })
        if (path === '/areas/tree') return ok({ version: '1', items: [] })
        return ok([])
      })
      await page.goto(item.path)
      await expect(page.locator(item.ready).first()).toBeVisible({ timeout: 45_000 })
      if (item.key === 'product-catalog') {
        await page
          .locator('.category-panel .el-tree-node[data-key="10"] > .el-tree-node__content')
          .click()
        await page.getByRole('checkbox', { name: '含下级分类' }).check()
      }
      await page.waitForFunction(() => !document.querySelector('[aria-busy="true"]'), undefined, {
        timeout: 20000,
      })
      if (item.key === 'editor') {
        await expect(page.locator('fieldset.form-fieldset')).not.toHaveAttribute('disabled', {
          timeout: 45_000,
        })
      }
      await page.evaluate(() => document.fonts.ready)
      await page.screenshot({
        path: output + '/' + item.key + '-' + viewport.width + '.png',
        animations: 'disabled',
      })
      await page.screenshot({
        path: output + '/' + item.key + '-' + viewport.width + '-full.png',
        fullPage: true,
        animations: 'disabled',
      })
      await page.addScriptTag({ path: axe })
      const results = await page.evaluate(async () => {
        const engine = (
          window as unknown as {
            axe: { run: (root: Document, options: unknown) => Promise<AxeResults> }
          }
        ).axe
        return engine.run(document, {
          runOnly: {
            type: 'tag',
            values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'best-practice'],
          },
          resultTypes: ['violations', 'incomplete'],
        })
      })
      const measured = await page.evaluate(() => ({
        width: window.innerWidth,
        documentWidth: document.documentElement.scrollWidth,
        height: window.innerHeight,
        documentHeight: document.documentElement.scrollHeight,
      }))
      const report = {
        template: item.label,
        key: item.key,
        viewport,
        measured,
        errors,
        calls: [...calls],
        ...results,
      }
      writeFileSync(
        output + '/' + item.key + '-' + viewport.width + '.json',
        JSON.stringify(report, null, 2),
        'utf8',
      )
      await test.info().attach('模板可访问性汇总', {
        contentType: 'application/json',
        body: JSON.stringify({
          template: item.key,
          width: viewport.width,
          overflow: measured.documentWidth > measured.width,
          errors,
          violations: results.violations.map((rule) => ({
            id: rule.id,
            impact: rule.impact,
            nodes: rule.nodes.length,
          })),
          incomplete: results.incomplete.map((rule) => ({
            id: rule.id,
            impact: rule.impact,
            nodes: rule.nodes.length,
          })),
        }),
      })
      await verifyTableHeaders(page, item.key)
      if (phase === 'verified' && item.key === 'list') {
        await test.step('按单号用键盘勾选，核对半选、全选和取消选择', async () => {
          const all = page.getByRole('checkbox', { name: '选择本页全部单据', exact: true })
          const first = page.getByRole('checkbox', { name: '选择单据 ' + order.documentNo, exact: true })
          const rows = page.getByRole('checkbox', { name: /^选择单据 / })
          const checkedRows = rows.and(page.locator('input:checked'))
          const bar = page.getByRole('group', { name: '单据批量操作' })
          await expect(rows).toHaveCount(15)
          await expect(checkedRows).toHaveCount(0)
          await all.focus()
          await page.keyboard.press('Tab')
          await expect(first).toBeFocused()
          await page.keyboard.press('Space')
          await expect(first).toBeChecked()
          await expect(all).toBeChecked({ indeterminate: true })
          await expect(bar).toContainText('已选 1 项')
          await page.keyboard.press('Tab')
          await expect(page.getByRole('link', { name: order.documentNo, exact: true })).toBeFocused()
          await all.focus()
          await page.keyboard.press('Space')
          await expect(all).toBeChecked()
          await expect(checkedRows).toHaveCount(15)
          await expect(bar).toContainText('已选 15 项')
          await page.keyboard.press('Space')
          await expect(checkedRows).toHaveCount(0)
          await expect(all).not.toBeChecked()
          await expect(bar).toBeHidden()
        })
      }
      if (phase === 'verified' && item.key === 'editor' && viewport.width === 375) {
        const scroll = page.getByRole('region', { name: '商品明细横向滚动明细' })
        await page.getByRole('button', { name: '添加商品行', exact: true }).focus()
        await page.keyboard.press('Tab')
        await expect(scroll).toBeFocused()
        await page.keyboard.press('ArrowRight')
        await expect.poll(() => scroll.evaluate((element) => element.scrollLeft)).toBeGreaterThan(0)
        await page.screenshot({ path: output + '/editor-375-keyboard.png', animations: 'disabled' })
        const keyboardResults = await scroll.evaluate(async (region) => {
          const engine = (
            window as unknown as {
              axe: { run: (root: Element, options: unknown) => Promise<AxeResults> }
            }
          ).axe
          return engine.run(region, {
            runOnly: {
              type: 'tag',
              values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'best-practice'],
            },
            resultTypes: ['violations', 'incomplete'],
          })
        })
        writeFileSync(
          output + '/editor-375-keyboard.json',
          JSON.stringify(keyboardResults, null, 2),
          'utf8',
        )
        expect(keyboardResults.violations).toEqual([])
      }
      if (phase === 'verified' && item.key === 'workbench' && viewport.width === 375) {
        const menu = page.getByRole('button', { name: '展开导航', exact: true })
        await menu.click()
        await expect(page.getByRole('dialog', { name: '业务导航' })).toBeVisible()
        await page.screenshot({ path: output + '/navigation-375-open.png', animations: 'disabled' })
        const navigationResults = await page.evaluate(async () => {
          const engine = (
            window as unknown as {
              axe: { run: (root: Document, options: unknown) => Promise<AxeResults> }
            }
          ).axe
          return engine.run(document, {
            runOnly: {
              type: 'tag',
              values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'best-practice'],
            },
            resultTypes: ['violations', 'incomplete'],
          })
        })
        writeFileSync(
          output + '/navigation-375-open.json',
          JSON.stringify(navigationResults, null, 2),
          'utf8',
        )
        expect(navigationResults.violations).toEqual([])
        await page.keyboard.press('Escape')
        await expect(page.getByRole('dialog', { name: '业务导航' })).toBeHidden()
        await expect(menu).toBeFocused()
      }
      if (phase === 'verified') {
        expect(errors).toEqual([])
        expect(measured.documentWidth).toBeLessThanOrEqual(measured.width)
        expect(results.violations.map((rule) => rule.id)).toEqual([])
      }
    })
  }
}
