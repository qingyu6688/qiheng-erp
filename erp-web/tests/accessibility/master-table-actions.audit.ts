import { expect, test } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'

const department = {
  id: '10',
  parentId: '0',
  code: 'QA',
  name: '质检部',
  path: '/10/',
  depth: 1,
  sortNo: 0,
  status: 'active',
  version: 1,
  memberCount: 1,
}
const member = { id: '901', displayName: '周宁', username: 'zhou', status: 'active' }
const category = { id: '2', parentId: '0', code: 'PARTS', name: '零件', status: 'active' }
const unit = { id: '5', name: '件', status: 'active' }
const product = {
  id: '8',
  code: 'P-8',
  name: '精加工轴套',
  categoryId: category.id,
  baseUnitId: unit.id,
  status: 'active',
  version: 1,
}

async function openPage(page: Page, path: string, permissions: string[]): Promise<void> {
  const data: Record<string, unknown> = {
    '/auth/me': {
      id: '900',
      username: 'tester',
      displayName: '测试员',
      mustChangePassword: false,
      permissions,
      companies: [{ id: '1', code: 'QA', name: '演练公司', allWarehouses: true, warehouseIds: [] }],
    },
    '/system/version': { apiMajor: 2 },
    '/notify/unread-count': { total: 0 },
    '/alerts/unhandled-count': { total: 0 },
    '/dicts': { version: '1', unchanged: false, items: {} },
    '/org/departments/tree': [department],
    '/org/departments/10/members': { items: [member], nextCursor: null },
    '/org/company-users': { items: [member], nextCursor: null },
    '/org/assignment-options': { departments: [department], posts: [] },
    '/users/901/organization': {
      userId: member.id,
      companyId: '1',
      departments: [{ id: department.id, primary: true }],
      postIds: [],
      version: 1,
    },
    '/product-categories/tree': [category],
    '/product-categories': { items: [category], nextCursor: null },
    '/units': { items: [unit], nextCursor: null },
    '/products': { items: [product], nextCursor: null },
    '/products/8': product,
    '/workbench/summary': {
      asOf: '2026-11-18T01:00:00Z',
      blocks: [
        { key: 'inventory-shortage', label: '库存不足', count: 1, url: '/inventory-balances' },
      ],
      todos: [],
      todosByType: {},
      periods: [],
      announcements: [],
      inventoryAlerts: [
        {
          warehouseId: '6',
          skuId: '41',
          skuCode: 'SKU-41',
          skuName: '丝杆',
          warehouseName: '总仓',
          availableQty: '2',
          pendingDemandQty: '9',
        },
      ],
    },
  }
  await page.setViewportSize({ width: 375, height: 812 })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.routeWebSocket('**/ws/**', (socket) => socket.close())
  await page.route('**/api/v1/**', (route) => {
    const key = new URL(route.request().url()).pathname.slice('/api/v1'.length)
    return route.fulfill({ json: { code: 200, message: '操作成功', data: data[key] ?? [] } })
  })
  await page.goto(path)
}

async function tabTo(page: Page, target: Locator): Promise<void> {
  await expect(target).toBeAttached()
  const candidateCount = await page
    .locator('a[href],button,input,select,textarea,[tabindex]')
    .count()
  for (let step = 0; step <= candidateCount; step++) {
    await page.keyboard.press('Tab')
    if (await target.evaluate((element) => element === document.activeElement)) return
  }
  throw new Error('通过页面 Tab 顺序未能到达指定操作')
}

async function expectActionVisible(action: Locator): Promise<void> {
  await expect(action).toBeFocused()
  await expect
    .poll(() =>
      action.evaluate((node) => {
        const rect = node.getBoundingClientRect()
        if (rect.width <= 2 || rect.height <= 2) return false
        const y = rect.top + rect.height / 2
        return [rect.left + 1, rect.right - 1].every((x) => {
          const hit = document.elementFromPoint(x, y)
          return hit !== null && node.contains(hit)
        })
      }),
    )
    .toBe(true)
}

test('工作台键盘切换库存不足后，各库存数值与本表列标题对应', async ({ page }) => {
  await openPage(page, '/', ['warehouse:view'])
  const filter = page.getByRole('button', { name: /库存不足/ })
  await tabTo(page, filter)
  await page.keyboard.press('Enter')
  await expect(filter).toHaveAttribute('aria-pressed', 'true')
  const table = page.getByRole('table').filter({ has: page.getByRole('cell', { name: '总仓' }) })
  await expect(table.getByRole('columnheader')).toHaveText(['商品', '仓库', '可用', '待审核需求'])
  const row = table.getByRole('row').filter({ hasText: 'SKU-41' })
  await expect(row.getByRole('cell')).toHaveCount(4)
  await expect(row.getByRole('cell').nth(2)).toHaveText(/^2(?:\.0+)?$/)
  await expect(row.getByRole('cell').nth(3)).toHaveText(/^9(?:\.0+)?$/)
})

test('部门成员表末列配置任职可用 Tab 到达，打开选中用户的部门与岗位', async ({ page }) => {
  await openPage(page, '/organization', ['org_department:view', 'user:view', 'user:update'])
  const memberRow = page.getByRole('row').filter({ hasText: member.username })
  const configure = memberRow.getByRole('button', { name: '配置任职' })
  await tabTo(page, configure)
  await expectActionVisible(configure)
  const configuration = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/users/901/organization' && response.ok(),
  )
  await page.keyboard.press('Enter')
  await configuration
  await expect(
    page
      .getByRole('region', { name: '用户部门与岗位' })
      .getByRole('button', { name: '保存部门与岗位' }),
  ).toBeVisible()
})

test('商品表末列编辑可用 Tab 到达，表单显示所选商品', async ({ page }) => {
  await openPage(page, '/products', [
    'product:view',
    'product:update',
    'product_category:view',
    'unit:view',
  ])
  const row = page.getByRole('row').filter({ hasText: product.code })
  const edit = row.getByRole('button', { name: '编辑', exact: true })
  await tabTo(page, edit)
  await expectActionVisible(edit)
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { name: '编辑商品' })).toBeVisible()
  await expect(
    page.getByRole('region', { name: '编辑商品' }).getByRole('textbox', { name: /名称$/ }),
  ).toHaveValue(product.name)
})
