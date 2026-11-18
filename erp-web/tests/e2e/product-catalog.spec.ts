import { expect, test } from '@playwright/test'

test('商品分类主从支持搜索、含下级、新建及窄屏布局', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (cause) => errors.push(cause.message))
  const parent = { id: '10', name: '装配件', code: 'ASSEMBLY', status: 'active', version: 1 }
  const child = {
    id: '11',
    name: '精密组件',
    code: 'PRECISION',
    parentId: '10',
    status: 'active',
    version: 1,
  }
  const products = [
    { id: '30', name: '标准组件', code: 'P-01', categoryId: '10', status: 'active', version: 1 },
    { id: '31', name: '高精度总成', code: 'P-02', categoryId: '11', status: 'active', version: 1 },
  ]
  const calls: string[] = []
  await page.routeWebSocket('**/ws/**', (socket) => socket.close())
  await page.route('**/api/v1/**', (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname.slice('/api/v1'.length)
    const ok = (data: unknown): Promise<void> =>
      route.fulfill({ json: { code: 200, message: '操作成功', data } })
    if (path === '/auth/me')
      return ok({
        id: '900',
        username: 'auditor',
        displayName: '张宁',
        mustChangePassword: false,
        permissions: [
          'product:view',
          'product:create',
          'product:update',
          'product_category:view',
          'unit:view',
          'sku:view',
        ],
        companies: [
          { id: '1', code: 'C01', name: '华东商贸', allWarehouses: true, warehouseIds: [] },
        ],
      })
    if (path === '/system/version') return ok({ apiMajor: 2 })
    if (path === '/workbench/summary')
      return ok({ blocks: [], todos: [], announcements: [], inventoryAlerts: [], periods: [] })
    if (path === '/dicts') return ok({ version: '1', unchanged: false, items: {} })
    if (path === '/product-categories') return ok([{ ...parent, children: [child] }])
    if (path === '/products') {
      calls.push(url.search)
      const id = url.searchParams.get('categoryId')
      const include = url.searchParams.get('includeChildren') === 'true'
      return ok({
        items: products.filter((row) => !id || row.categoryId === id || (id === '10' && include)),
        nextCursor: null,
      })
    }
    if (path === '/units')
      return ok({ items: [{ id: '50', name: '件', status: 'active' }], nextCursor: null })
    if (path === '/notify/unread-count' || path === '/alerts/unhandled-count')
      return ok({ total: 0 })
    return ok([])
  })
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/products')
  const tree = page.locator('.category-panel')
  const table = page.locator('.product-results .el-table__body')
  await expect(table).toContainText('标准组件')
  await expect(table).toContainText('高精度总成')
  await expect(page.getByRole('alert')).toHaveCount(0)
  const divider = page.getByRole('separator', { name: '调整商品分类宽度' })
  await expect(divider).toHaveAttribute('aria-valuenow', '280')
  await divider.focus()
  await page.keyboard.press('ArrowRight')
  await expect(divider).toHaveAttribute('aria-valuenow', '296')
  await tree.locator('.el-tree-node[data-key="10"] > .el-tree-node__content').click()
  await expect(table).toContainText('标准组件')
  await expect(table).not.toContainText('高精度总成')
  await page.getByRole('checkbox', { name: '含下级分类' }).check()
  await expect(table).toContainText('高精度总成')
  expect(
    calls.some(
      (query) => query.includes('categoryId=10') && query.includes('includeChildren=true'),
    ),
  ).toBe(true)
  await page.screenshot({
    path: test.info().outputPath('product-catalog-1440.png'),
    animations: 'disabled',
  })
  await page.getByRole('textbox', { name: '搜索分类' }).fill('精密')
  await expect(tree.locator('.el-tree-node[data-key="10"]')).toBeVisible()
  await expect(tree.locator('.el-tree-node[data-key="11"]')).toBeVisible()
  await tree.locator('.el-tree-node[data-key="11"] > .el-tree-node__content').click()
  await page.getByRole('button', { name: '新建商品', exact: true }).click()
  const editor = page.getByRole('region', { name: '新建商品', exact: true })
  await expect(editor).toBeVisible()
  await expect(editor.locator('fieldset')).not.toHaveAttribute('disabled')
  await expect(editor.getByRole('combobox', { name: /商品分类/ })).toBeVisible()
  await expect(editor.locator('.el-form-item').filter({ hasText: '商品分类' })).toContainText(
    '精密组件',
  )
  await editor.getByRole('button', { name: '取消', exact: true }).click()
  await page.setViewportSize({ width: 375, height: 812 })
  await expect(divider).toBeHidden()
  await expect(tree).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375)
  await page.screenshot({
    path: test.info().outputPath('product-catalog-375.png'),
    fullPage: true,
    animations: 'disabled',
  })
  expect(errors).toEqual([])
})
