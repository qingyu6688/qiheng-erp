import { expect, test } from '@playwright/test'

test('单据列可调整顺序和固定，刷新恢复并支持键盘与窄屏', async ({ page }, testInfo) => {
  test.setTimeout(60_000)
  const items = Array.from({ length: 20 }, (_, index) => ({
    id: String(index + 1),
    documentNo: 'SO-COLUMN-' + (index + 1),
    customerName: '列设置验收客户',
    businessDate: '2026-11-18',
    status: 'approved',
    version: 1,
    totalAmount: '1250.20',
    allowedActions: [],
  }))
  await page.routeWebSocket('**/ws/**', (socket) => socket.close())
  await page.route('**/api/v1/**', (route) => {
    const path = new URL(route.request().url()).pathname.slice('/api/v1'.length)
    const ok = (data: unknown): Promise<void> => route.fulfill({ json: { data } })
    if (path === '/system/version') return ok({ apiMajor: 2 })
    if (path === '/auth/me')
      return ok({
        id: '561',
        username: 'columns',
        displayName: '列设置验收员',
        mustChangePassword: false,
        permissions: ['sales_order:view', 'partner:view'],
        companies: [
          { id: '1', code: 'A', name: '列设置验收公司', allWarehouses: true, warehouseIds: [] },
        ],
      })
    if (path === '/document-browser/sales-orders')
      return ok({
        items,
        total: items.length,
        nextCursor: null,
        statusCounts: { approved: items.length },
      })
    if (path === '/notify/unread-count') return ok({ total: 0 })
    if (path === '/workbench/summary') return ok({ blocks: [] })
    return ok([])
  })
  await page.setViewportSize({ width: 1024, height: 900 })
  await page.goto('/sales-orders')
  const allHeaders = page.getByRole('columnheader')
  await expect(
    allHeaders.first().getByRole('checkbox', { name: '选择本页全部单据', exact: true }),
  ).toBeVisible()
  await expect(allHeaders.first()).toHaveClass(/el-table-fixed-column--left/)
  const header = allHeaders.filter({ hasNot: page.getByRole('checkbox') })
  await expect(header).toHaveText(
    ['单号', '客户 / 账户', '业务日期', '状态', '履约 / 结算', '金额（元）', '操作'],
    { timeout: 30_000 },
  )
  const table = page.getByRole('table').filter({ has: page.getByRole('columnheader', { name: '单号', exact: true }) })
  await expect(table.locator('tbody tr')).toHaveCount(items.length)
  await expect(table.locator('thead th[scope="col"]')).toHaveCount(8)
  await expect(table.locator('tbody tr').first().getByRole('cell')).toHaveCount(8)
  await expect.poll(async () => (await allHeaders.first().boundingBox())?.width).toBe(44)

  const scrollArea = page.locator('.el-table__body-wrapper .el-scrollbar__wrap')
  await expect.poll(() => scrollArea.evaluate((el) => el.scrollHeight - el.clientHeight)).toBeGreaterThan(40)
  const headTop = (await allHeaders.first().boundingBox())!.y
  await scrollArea.evaluate((el) => { el.scrollTop = el.scrollHeight })
  await expect.poll(() => scrollArea.evaluate((el) => el.scrollTop)).toBeGreaterThan(40)
  await expect.poll(async () => (await allHeaders.first().boundingBox())!.y).toBeCloseTo(headTop, 0)
  await scrollArea.evaluate((el) => { el.scrollTop = 0 })

  const settings = page.locator('.document-column-options')
  await settings.locator('summary').focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('checkbox', { name: '单号', exact: true })).toBeDisabled()
  await expect(page.getByRole('combobox', { name: '操作固定位置' })).toBeDisabled()
  await page.getByRole('button', { name: '上移业务日期', exact: true }).click()
  await expect(header).toHaveText([
    '单号',
    '业务日期',
    '客户 / 账户',
    '状态',
    '履约 / 结算',
    '金额（元）',
    '操作',
  ])
  await page.getByRole('combobox', { name: '金额（元）固定位置' }).selectOption('right')
  await page.getByRole('combobox', { name: '客户 / 账户固定位置' }).selectOption('left')
  await page.keyboard.press('Escape')
  await expect(settings.locator('summary')).toBeFocused()
  await expect(header).toHaveText([
    '单号',
    '客户 / 账户',
    '业务日期',
    '状态',
    '履约 / 结算',
    '金额（元）',
    '操作',
  ])
  await expect(page.getByRole('columnheader', { name: '客户 / 账户', exact: true })).toHaveClass(
    /el-table-fixed-column--left/,
  )
  await expect(page.getByRole('columnheader', { name: '金额（元）', exact: true })).toHaveClass(
    /el-table-fixed-column--right/,
  )

  const first = page.getByRole('columnheader', { name: '单号', exact: true })
  const actions = page.getByRole('columnheader', { name: '操作', exact: true })
  const before = await Promise.all([first.boundingBox(), actions.boundingBox()])
  await scrollArea.evaluate((element) => {
    element.scrollLeft = element.scrollWidth
  })
  await expect.poll(async () => (await first.boundingBox())!.x).toBeCloseTo(before[0]!.x, 0)
  await expect.poll(async () => (await actions.boundingBox())!.x).toBeCloseTo(before[1]!.x, 0)
  await page.reload()
  await expect(page.getByRole('columnheader', { name: '客户 / 账户', exact: true })).toHaveClass(
    /el-table-fixed-column--left/,
  )
  await expect(page.getByRole('columnheader', { name: '金额（元）', exact: true })).toHaveClass(
    /el-table-fixed-column--right/,
  )
  await page.setViewportSize({ width: 375, height: 812 })
  const firstRow = table.locator('tbody tr').first()
  const customer = firstRow.getByRole('cell', { name: '列设置验收客户', exact: true })
  await expect(customer).not.toHaveClass(/el-table-fixed-column--left/)
  await expect(first).toHaveClass(/el-table-fixed-column--left/)
  await expect(actions).toHaveClass(/el-table-fixed-column--right/)
  await expect.poll(async () => (await first.boundingBox())!.width).toBe(112)
  await expect.poll(async () => (await actions.boundingBox())!.width).toBe(60)
  await customer.scrollIntoViewIfNeeded()
  await scrollArea.evaluate((el) => { el.scrollLeft = 0 })
  // 用文字的每个可见片段检查命中元素，按实际页面位置排除固定列遮挡。
  await expect.poll(() => customer.evaluate((cell) => {
    const walker = document.createTreeWalker(cell, NodeFilter.SHOW_TEXT)
    let text = walker.nextNode()
    while (text && text.textContent?.trim() !== '列设置验收客户') text = walker.nextNode()
    if (!text) return false
    const range = document.createRange()
    range.selectNodeContents(text)
    const rects = Array.from(range.getClientRects()).filter((rect) => rect.width > 0 && rect.height > 0)
    return rects.length > 0 && rects.every((rect) =>
      [rect.left + 1, rect.right - 1].every((x) =>
        cell.contains(document.elementFromPoint(x, rect.top + rect.height / 2)),
      ),
    )
  })).toBe(true)
  await page.setViewportSize({ width: 1024, height: 900 })
  await expect(customer).toHaveClass(/el-table-fixed-column--left/)
  await page.setViewportSize({ width: 375, height: 812 })
  await settings.locator('summary').click()
  const options = await page.locator('.document-column-options-list').boundingBox()
  expect(options!.x).toBeGreaterThanOrEqual(0)
  expect(options!.x + options!.width).toBeLessThanOrEqual(375)
  await page.screenshot({ path: testInfo.outputPath('columns-mobile.png'), animations: 'disabled' })
  await page.getByRole('button', { name: '恢复默认列', exact: true }).click()
  await expect(
    page.getByRole('columnheader', { name: '客户 / 账户', exact: true }),
  ).not.toHaveClass(/el-table-fixed-column--left/)
  await expect(page.getByRole('columnheader', { name: '金额（元）', exact: true })).not.toHaveClass(
    /el-table-fixed-column--right/,
  )
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(375)
})
