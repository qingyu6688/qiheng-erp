import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

async function mockLocations(
  page: Page,
  writable: boolean,
): Promise<{ generated: unknown[]; reads: string[] }> {
  const generated: unknown[] = []
  const reads: string[] = []
  const zone = (id: string, code: string, name: string, status = 'active') => ({
    id,
    code,
    name,
    status,
    type: 'storage',
    version: 0,
  })
  const slot = (id: string, zoneId: string, code: string, name: string, status = 'active') => ({
    id,
    zoneId,
    code,
    name,
    status,
    version: 0,
    pickSequence: 1,
    mixedSkuAllowed: true,
    mixedBatchAllowed: true,
  })
  const warehouse = (id: string, code: string, name: string) => ({
    id,
    code,
    name,
    locationManaged: true,
    status: 'active',
    version: 0,
  })
  const first = {
    warehouse: warehouse('11', 'WH-A', '甲仓'),
    zones: [zone('21', 'ST', '常温区'), zone('22', 'QT', '隔离区')],
    locations: [
      slot('31', '21', 'ST-01', '常温一号'),
      slot('32', '21', 'ST-02', '停用货位', 'disabled'),
      slot('33', '22', 'QT-01', '隔离一号'),
    ],
  }
  const other = {
    warehouse: warehouse('12', 'WH-B', '乙仓'),
    zones: [zone('25', 'OT', '乙仓区')],
    locations: [slot('35', '25', 'OT-01', '乙仓一号')],
  }

  await page.routeWebSocket('**/ws/**', (socket) => socket.close())
  await page.route('**/api/v1/**', (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname.slice('/api/v1'.length)
    const ok = (data: unknown): Promise<void> => route.fulfill({ json: { data } })
    if (path === '/system/version') return ok({ apiMajor: 2 })
    if (path === '/auth/me')
      return ok({
        id: '660',
        username: 'warehouse-layout',
        displayName: '库位验收员',
        mustChangePassword: false,
        permissions: [
          'warehouse:view',
          'wms_location:view',
          'inventory:view',
          ...(writable ? ['warehouse:update', 'wms_location:manage'] : []),
        ],
        companies: [
          {
            id: '1',
            code: 'A',
            name: '隔离公司',
            allWarehouses: false,
            warehouseIds: ['11', '12'],
          },
        ],
      })
    if (path === '/workbench/summary') return ok({ blocks: [] })
    if (path === '/notify/unread-count') return ok({ total: 0 })
    if (path === '/warehouses') {
      expect(request.headers()['x-company-id']).toBe('1')
      return ok({ items: [first.warehouse, other.warehouse], nextCursor: null })
    }
    if (path === '/warehouses/11/locations/generate') {
      expect(request.method()).toBe('POST')
      expect(request.headers()['x-company-id']).toBe('1')
      generated.push(request.postDataJSON())
      first.locations.push(slot('36', '21', 'AD-01', '批量生成库位'))
      return ok({ count: 1 })
    }
    if (/^\/warehouses\/(11|12)\/locations$/.test(path)) {
      expect(request.method()).toBe('GET')
      expect(request.headers()['x-company-id']).toBe('1')
      reads.push(path)
      return ok(path.includes('/11/') ? first : other)
    }
    return ok([])
  })
  return { generated, reads }
}

test('库区库位按实际范围分栏，叠加筛选并保留生成和仓库切换', async ({ page }, testInfo) => {
  const { generated, reads } = await mockLocations(page, true)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/warehouse-locations?warehouseId=11')
  const zones = page.getByRole('region', { name: '库区列表' })
  const slots = page.getByRole('region', { name: '库位列表' })
  const divider = page.getByRole('separator', { name: '调整库区列表宽度' })
  await expect(divider).toHaveAttribute('aria-valuenow', '280')
  await divider.press('ArrowRight')
  await expect(divider).toHaveAttribute('aria-valuenow', '296')
  await expect(slots.getByRole('cell', { name: 'QT-01', exact: true })).toBeVisible()
  await zones.getByRole('textbox', { name: '搜索库区' }).fill('st')
  await expect(zones.getByRole('button', { name: /隔离区/ })).toHaveCount(0)
  const storage = zones.getByRole('button', { name: /ST 常温区/ })
  await storage.press('Enter')
  await expect(storage).toHaveAttribute('aria-pressed', 'true')
  await expect(slots.getByRole('cell', { name: 'QT-01', exact: true })).toHaveCount(0)
  await slots.locator('.location-filters .el-select').click()
  await page.getByRole('option', { name: '停用', exact: true }).click()
  await expect(slots.getByRole('cell', { name: 'ST-02', exact: true })).toBeVisible()
  await expect(slots.getByRole('cell', { name: 'ST-01', exact: true })).toHaveCount(0)
  const slotSearch = slots.getByRole('textbox', { name: '搜索库位' })
  await slotSearch.fill('常温一号')
  await expect(slots.getByText('未找到匹配的库位')).toBeVisible()
  await slotSearch.clear()
  await slots.locator('.location-filters .el-select').click()
  await page.getByRole('option', { name: '全部状态', exact: true }).click()

  await slots.getByRole('button', { name: '批量生成' }).click()
  const form = page.getByRole('dialog', { name: '批量生成库位' })
  await form.getByLabel('编码前缀').fill('AD')
  await form.getByRole('button', { name: '生成', exact: true }).click()
  await expect(slots.getByRole('cell', { name: 'AD-01', exact: true })).toBeVisible()
  expect(generated).toEqual([
    { zoneId: '21', prefix: 'AD', aisles: 1, shelves: 1, layers: 1, positions: 1 },
  ])

  await page
    .locator('.el-select')
    .filter({ has: page.locator('#slot-warehouse') })
    .click()
  await page.getByRole('option', { name: 'WH-B 乙仓', exact: true }).click()
  await expect(zones.getByRole('textbox', { name: '搜索库区' })).toHaveValue('')
  await expect(zones.getByRole('button', { name: '全部库区' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  await expect(slots.getByRole('cell', { name: 'OT-01', exact: true })).toBeVisible()
  await expect(slots.getByRole('cell', { name: 'AD-01', exact: true })).toHaveCount(0)
  expect(new Set(reads)).toEqual(new Set(['/warehouses/11/locations', '/warehouses/12/locations']))

  await page.setViewportSize({ width: 375, height: 812 })
  await expect(divider).toBeHidden()
  const left = await zones.boundingBox(),
    right = await slots.boundingBox()
  expect(right!.y).toBeGreaterThanOrEqual(left!.y + left!.height)
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375)
  await page.screenshot({
    path: testInfo.outputPath('warehouse-locations-375.png'),
    animations: 'disabled',
  })
})

test('只读库位角色可以搜索和查看标签', async ({ page }) => {
  const { generated } = await mockLocations(page, false)
  await page.goto('/warehouse-locations')
  await page.getByRole('button', { name: /QT 隔离区/ }).click()
  const slots = page.getByRole('region', { name: '库位列表' })
  await expect(slots.getByRole('link', { name: '标签' })).toHaveAttribute(
    'href',
    '/warehouse-locations/11/labels/33',
  )
  for (const name of ['新增库区', '编辑库区', '新增库位', '批量生成', '编辑']) {
    await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0)
  }
  await expect(page.getByRole('tab', { name: '仓库设置' })).toHaveCount(0)
  expect(generated).toEqual([])
})
