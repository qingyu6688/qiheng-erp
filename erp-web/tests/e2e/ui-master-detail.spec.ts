import { expect, test } from '@playwright/test'

test('部门分栏支持键盘拖动、搜索树、下级成员和公司隔离恢复', async ({ page }, testInfo) => {
  test.setTimeout(60_000)
  const department = (id: string, name: string, parentId = '0') => ({
    id,
    name,
    parentId,
    code: 'D' + id,
    path: parentId === '0' ? '/' + id + '/' : '/' + parentId + '/' + id + '/',
    depth: parentId === '0' ? 1 : 2,
    sortNo: 0,
    status: 'active',
    version: 0,
    memberCount: 1,
    leaderUserId: null,
    leaderName: null,
  })
  await page.routeWebSocket('**/ws/**', (socket) => socket.close())
  await page.route('**/api/v1/**', (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname.slice('/api/v1'.length)
    const ok = (data: unknown): Promise<void> => route.fulfill({ json: { data } })
    if (path === '/system/version') return ok({ apiMajor: 2 })
    if (path === '/auth/me')
      return ok({
        id: '562',
        username: 'org-layout',
        displayName: '组织资料验收员',
        mustChangePassword: false,
        permissions: ['org_department:view'],
        companies: [
          { id: '1', code: 'A', name: '甲公司', allWarehouses: true, warehouseIds: [] },
          { id: '2', code: 'B', name: '乙公司', allWarehouses: true, warehouseIds: [] },
        ],
      })
    const company = route.request().headers()['x-company-id']
    if (path === '/org/departments/tree')
      return ok(
        company === '1'
          ? [
              department('10', '销售部'),
              department('11', '华东组', '10'),
              department('12', '采购部'),
            ]
          : [department('20', '乙公司部门')],
      )
    if (/^\/org\/departments\/\d+\/members$/.test(path)) {
      const selectedCompany = company === '1'
      return ok({
        items: selectedCompany
          ? [
              { id: '100', username: 'sales', displayName: '直属成员', status: 'active' },
              ...(url.searchParams.get('includeChildren') === 'true'
                ? [{ id: '101', username: 'east', displayName: '下级成员', status: 'active' }]
                : []),
            ]
          : [],
        nextCursor: null,
      })
    }
    if (path === '/notify/unread-count') return ok({ total: 0 })
    if (path === '/workbench/summary') return ok({ blocks: [], todos: [] })
    return ok([])
  })
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/organization')
  const separator = page.getByRole('separator', { name: '调整部门树宽度' })
  await expect(separator).toHaveAttribute('aria-valuenow', '280', { timeout: 30_000 })
  await separator.press('ArrowRight')
  await expect(separator).toHaveAttribute('aria-valuenow', '296')
  const divider = (await separator.boundingBox())!
  await page.mouse.move(divider.x + divider.width / 2, divider.y + 60)
  await page.mouse.down()
  await page.mouse.move(divider.x + 60, divider.y + 60)
  await page.mouse.up()
  const draggedWidth = await separator.getAttribute('aria-valuenow')
  expect(Number(draggedWidth)).toBeGreaterThan(296)
  await page.getByRole('textbox', { name: '搜索部门', exact: true }).fill('D11')
  await expect(page.getByRole('treeitem', { name: '销售部' })).toBeVisible()
  await expect(page.getByRole('treeitem', { name: '华东组', exact: true })).toBeVisible()
  await expect(page.getByRole('treeitem', { name: '采购部', exact: true })).toBeHidden()
  await page.getByRole('checkbox', { name: '含下级', exact: true }).check()
  await expect(page.getByRole('cell', { name: '下级成员', exact: true })).toBeVisible()
  await page.reload()
  await expect(separator).toHaveAttribute('aria-valuenow', draggedWidth!)
  await expect(page.getByRole('checkbox', { name: '含下级', exact: true })).not.toBeChecked()
  await page.locator('.company-select').click()
  await page.getByRole('option', { name: '乙公司', exact: true }).click()
  await expect(page.getByRole('heading', { name: '乙公司部门', exact: true })).toBeVisible()
  await expect(separator).toHaveAttribute('aria-valuenow', '280')
  await expect(page.getByRole('cell', { name: '直属成员', exact: true })).toHaveCount(0)
  await page.locator('.company-select').click()
  await page.getByRole('option', { name: '甲公司', exact: true }).click()
  await expect(separator).toHaveAttribute('aria-valuenow', draggedWidth!)
  await page.setViewportSize({ width: 375, height: 812 })
  await expect(separator).toBeHidden()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(375)
  await page.screenshot({
    path: testInfo.outputPath('organization-ui2-mobile.png'),
    fullPage: true,
  })
})
