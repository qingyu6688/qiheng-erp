import { expect, test, type Locator } from '@playwright/test'

async function expectReadable(locator: Locator): Promise<void> {
  const { color, backgroundColor } = await locator.evaluate((element) => {
    const style = getComputedStyle(element)
    return { color: style.color, backgroundColor: style.backgroundColor }
  })
  function luminance(colorValue: string): number {
    const [r, g, b] = (colorValue.match(/\d+\.?\d*/g) ?? []).slice(0, 3).map((value) => {
      const channel = Number(value) / 255
      return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
    })
    return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!
  }
  const light = luminance(color),
    dark = luminance(backgroundColor)
  expect((Math.max(light, dark) + 0.05) / (Math.min(light, dark) + 0.05)).toBeGreaterThanOrEqual(
    4.5,
  )
}

test('单据列表的控件尺寸、表格密度和状态文字对比度', async ({ page }, testInfo) => {
  const states = ['draft', 'posted', 'signed_with_diff', 'signed', 'voided']
  const items = Array.from({ length: 15 }, (_, index) => ({
    id: String(index + 1),
    documentNo: 'FH-202611-' + String(index + 1).padStart(6, '0'),
    businessDate: '2026-11-17',
    customerName: '验收客户',
    status: states[index % states.length]!,
    version: 1,
    allowedActions: [],
  }))
  await page.routeWebSocket('**/ws/**', (socket) => socket.close())
  await page.route('**/api/v1/**', (route) => {
    const path = new URL(route.request().url()).pathname.slice('/api/v1'.length)
    const ok = (data: unknown): Promise<void> => route.fulfill({ json: { data } })
    if (path === '/system/version') return ok({ apiMajor: 2 })
    if (path === '/auth/me')
      return ok({
        id: '560',
        username: 'verify',
        displayName: '单据验收员',
        mustChangePassword: false,
        permissions: ['shipment:view', 'shipment:create', 'partner:view'],
        companies: [
          { id: '1', code: 'A', name: '验收公司', allWarehouses: true, warehouseIds: [] },
        ],
      })
    if (path === '/document-browser/shipments')
      return ok({
        items,
        nextCursor: null,
        total: items.length,
        statusCounts: Object.fromEntries(states.map((state) => [state, 3])),
      })
    if (path === '/notify/unread-count') return ok({ total: 0 })
    if (path === '/partners') return ok({ items: [{ id: '1', code: 'C01', name: '验收客户' }] })
    if (path === '/workbench/summary') return ok({ blocks: [] })
    return ok([])
  })

  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/shipments')
  const query = page.getByRole('button', { name: '查询', exact: true })
  const date = page.getByLabel('开始日期')
  const row = page.getByRole('row').filter({ hasText: items[0]!.documentNo })
  const density = page.getByRole('combobox', { name: '表格密度' })
  await expect(query).toHaveCSS('height', '32px')
  await expect(date).toHaveCSS('height', '32px')
  await expect(row).toHaveCSS('height', '40px')
  await expect(page.locator('.document-partner label')).toHaveText('客户')
  const queryLayout = await page.locator('.document-query-main').evaluate((main) =>
    [...main.children].map((field) => {
      const { top, width } = field.getBoundingClientRect()
      return { top, width }
    }),
  )
  const filterTops = queryLayout.map(({ top }) => top)
  expect(
    Math.max(...filterTops) - Math.min(...filterTops),
    JSON.stringify(queryLayout),
  ).toBeLessThanOrEqual(4)
  await expectReadable(query)
  await query.hover()
  await expect(query).toHaveCSS('background-color', 'rgb(14, 66, 210)')
  await expectReadable(query)

  const tags = page.locator('.status-tag')
  await expect(tags).toHaveCount(15)
  expect((await tags.allTextContents()).slice(0, 5)).toEqual([
    '草稿',
    '已出库待签收',
    '签收差异待处理',
    '已签收',
    '已作废',
  ])
  for (const tag of (await tags.all()).slice(0, 5)) await expectReadable(tag)
  expect(
    await page
      .getByRole('row')
      .filter({ hasText: items[11]!.documentNo })
      .evaluate(
        (element) =>
          element.getBoundingClientRect().bottom <=
          Math.min(
            window.innerHeight,
            element.closest('.el-table')!.getBoundingClientRect().bottom,
          ),
      ),
  ).toBe(true)

  await density.selectOption('compact')
  await expect(query).toHaveCSS('height', '24px')
  await expect(date).toHaveCSS('height', '24px')
  await expect(row).toHaveCSS('height', '32px')
  await page.reload()
  await expect(density).toHaveValue('compact')
  await expect(row).toHaveCSS('height', '32px')
  await density.selectOption('comfortable')
  await expect(query).toHaveCSS('height', '36px')
  await expect(date).toHaveCSS('height', '36px')
  await expect(row).toHaveCSS('height', '48px')
  await density.selectOption('default')

  await page.getByLabel('日期快捷项').selectOption('today')
  const selectedDay = await date.inputValue()
  expect(selectedDay).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  await expect(page.getByLabel('结束日期')).toHaveValue(selectedDay)
  await page.getByRole('button', { name: '重置', exact: true }).click()
  await page.getByRole('button', { name: '展开筛选' }).click()
  const overdue = page.locator('.document-query-more').getByText('仅签收超期', { exact: true })
  await expect(overdue).toBeVisible()
  await page.getByText('列设置', { exact: true }).click()
  await page.getByRole('checkbox', { name: '业务日期', exact: true }).uncheck()
  await expect(page.getByRole('columnheader', { name: '业务日期', exact: true })).toHaveCount(0)
  await page.getByRole('combobox', { name: '每页条数' }).selectOption('50')
  await expect(page).toHaveURL(/pageSize=50/)
  await page.reload()
  await expect(page.getByRole('combobox', { name: '每页条数' })).toHaveValue('50')
  await expect(page.getByRole('columnheader', { name: '业务日期', exact: true })).toHaveCount(0)
  await expect(overdue).toBeVisible()
  await page.getByRole('button', { name: '收起筛选' }).click()
  await page.getByText('列设置', { exact: true }).click()
  await page.getByRole('checkbox', { name: '业务日期', exact: true }).check()
  await expect(page.getByRole('columnheader', { name: '业务日期', exact: true })).toBeVisible()
  await page.getByText('列设置', { exact: true }).click()
  await page.getByRole('combobox', { name: '每页条数' }).selectOption('20')

  await page.screenshot({
    path: testInfo.outputPath('document-foundation-1440.png'),
    animations: 'disabled',
  })
  await page.setViewportSize({ width: 375, height: 812 })
  await expect(density).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(375)
  await page.screenshot({
    path: testInfo.outputPath('document-foundation-375.png'),
    animations: 'disabled',
  })
})

test('选中订单只审核所选单据，刷新后显示处理结果', async ({ page }) => {
  const ids = ['9007199254740993', '9007199254740995']
  const items = ids.map((id, index) => ({
    id,
    documentNo: 'SO-批量-' + (index + 1),
    status: 'pending',
    businessDate: '2026-11-17',
    version: 1,
    allowedActions: [],
  }))
  const approved: string[] = []
  await page.routeWebSocket('**/ws/**', (socket) => socket.close())
  await page.route('**/api/v1/**', (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname.slice('/api/v1'.length)
    const ok = (data: unknown): Promise<void> => route.fulfill({ json: { data } })
    if (path === '/system/version') return ok({ apiMajor: 2 })
    if (path === '/auth/me')
      return ok({
        id: '561',
        username: 'reviewer',
        displayName: '订单审核员',
        mustChangePassword: false,
        permissions: ['sales_order:view', 'sales_order:approve'],
        companies: [
          { id: '1', code: 'A', name: '验收公司', allWarehouses: true, warehouseIds: [] },
        ],
      })
    if (path === '/document-browser/sales-orders')
      return ok({
        items,
        total: items.length,
        nextCursor: null,
        statusCounts: { pending: items.length },
      })
    if (path === '/sales-orders/' + ids[0]) return ok({ ...items[0], allowedActions: ['approve'] })
    if (path === '/sales-orders/' + ids[0] + '/approve') {
      expect(request.postDataJSON()).toEqual({ version: 1 })
      expect(request.headers()['idempotency-key']).toBeTruthy()
      approved.push(ids[0]!)
      return ok({ ...items[0], version: 2, status: 'approved' })
    }
    if (path === '/notify/unread-count') return ok({ total: 0 })
    if (path === '/workbench/summary') return ok({ blocks: [] })
    return ok([])
  })

  await page.goto('/sales-orders?status=pending')
  const row = page.getByRole('row').filter({ hasText: 'SO-批量-1' })
  await row.getByRole('checkbox', { name: '选择单据 SO-批量-1', exact: true }).check()
  await expect(row.getByRole('checkbox')).toBeChecked()
  const bar = page.getByRole('group', { name: '单据批量操作' })
  await expect(bar).toContainText('已选 1 项')
  await expect(bar.getByRole('button', { name: '批量打印' })).toHaveCount(0)
  await bar.getByRole('button', { name: '批量审核' }).click()
  await page
    .getByRole('dialog', { name: '批量审核' })
    .getByRole('button', { name: '确认批量审核' })
    .click()
  await expect(page.getByRole('status').filter({ hasText: '已完成 1 张' })).toBeVisible()
  await expect(row.getByRole('checkbox')).not.toBeChecked()
  expect(approved).toEqual([ids[0]])
})
