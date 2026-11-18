import { expect, test } from '@playwright/test'

test('驾驶舱全屏使用局部暗色，月份控件可用且退出后恢复管理端样式', async ({ page }, testInfo) => {
  test.setTimeout(60_000)
  await page.routeWebSocket('**/ws/**', (socket) => socket.close())
  await page.route('**/api/v1/**', (route) => {
    const path = new URL(route.request().url()).pathname.slice('/api/v1'.length)
    const ok = (data: unknown): Promise<void> => route.fulfill({ json: { data } })
    if (path === '/system/version') return ok({ apiMajor: 2 })
    if (path === '/auth/me')
      return ok({
        id: '563',
        username: 'dashboard-theme',
        displayName: '驾驶舱验收员',
        mustChangePassword: false,
        permissions: ['dashboard:view', 'report:sales_summary'],
        companies: [
          { id: '1', code: 'A', name: '驾驶舱验收公司', allWarehouses: true, warehouseIds: [] },
        ],
      })
    if (path === '/dashboard/metrics')
      return ok({ items: [{ code: 'sales', name: '销售', detailPath: '/reports/sales_summary' }] })
    if (path === '/dashboard/layout') return ok({ version: 0, preset: 'owner', metrics: ['sales'] })
    if (path === '/dashboard/data')
      return ok({
        period: '2026-11',
        dateFrom: '2026-11-01',
        dateTo: '2026-11-18',
        items: [
          {
            code: 'sales',
            asOf: '2026-11-18T01:00:00Z',
            dataSource: 'primary',
            note: '销售确认金额',
            values: [{ label: '销售额', value: '123456.78', unit: '元' }],
            plots: [
              {
                name: '销售月度趋势',
                points: [
                  { label: '2026-09', value: '80000.00', unit: '元' },
                  { label: '2026-10', value: '100000.00', unit: '元' },
                  { label: '2026-11', value: '123456.78', unit: '元' },
                ],
              },
              { name: '空数据示例', points: [{ label: '暂无', value: null, unit: '元' }] },
            ],
          },
        ],
      })
    if (path === '/notify/unread-count') return ok({ total: 0 })
    if (path === '/workbench/summary') return ok({ blocks: [], todos: [] })
    return ok([])
  })
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/dashboard')
  await expect(page.getByRole('heading', { name: '销售', exact: true })).toBeVisible({
    timeout: 30_000,
  })
  const month = page.locator('.dashboard .el-date-editor input')
  await expect(month).toBeVisible()
  await expect(page.getByText('本期无统计数据', { exact: true })).toBeVisible()
  await expect(page.locator('.dashboard .trade-chart')).toHaveCount(1)
  await expect(page.locator('.dashboard-values dd')).toHaveText('123,456.78')
  const bodyBackground = await page
    .locator('body')
    .evaluate((element) => getComputedStyle(element).backgroundColor)
  await page.getByRole('button', { name: '全屏轮播', exact: true }).click()
  const screen = page.locator('.dashboard--screen')
  await expect(screen).toBeVisible()
  await expect(screen).toHaveCSS('background-color', 'rgb(23, 23, 26)')
  await expect(screen.locator('.dashboard-block')).toHaveCSS('background-color', 'rgb(35, 35, 36)')
  await expect(screen.locator('h1')).toHaveCSS('color', 'rgb(246, 246, 246)')
  await expect(screen.locator('.dashboard-stamp')).toHaveCSS('color', 'rgb(197, 197, 197)')
  expect(
    await page.evaluate(() => document.fullscreenElement?.classList.contains('dashboard')),
  ).toBe(true)
  await month.click()
  await expect(screen.locator('.el-picker-panel')).toBeVisible()
  await expect(screen.locator('.el-picker__popper')).toHaveCSS('background-color', 'rgb(35, 35, 36)')
  await screen.getByRole('heading', { name: '经营驾驶舱', exact: true }).click()
  await page.screenshot({ path: testInfo.outputPath('dashboard-dark.png'), animations: 'disabled' })
  await page.getByRole('button', { name: '退出全屏', exact: true }).click()
  await expect(screen).toHaveCount(0)
  expect(await page.evaluate(() => document.fullscreenElement)).toBeNull()
  await expect(page.locator('body')).toHaveCSS('background-color', bodyBackground)
  expect(await page.evaluate(() => document.documentElement.classList.contains('dark'))).toBe(false)
  await expect(page.getByRole('link', { name: '查看报表', exact: true })).toBeVisible()
})
