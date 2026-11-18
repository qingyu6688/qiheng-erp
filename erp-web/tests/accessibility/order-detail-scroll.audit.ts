import { expect, test } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'

type OrderKind = 'sales-orders' | 'purchase-orders'

async function openDetail(page: Page, kind: OrderKind): Promise<void> {
  const line = {
    id: '51', skuName: '钢制轴套', qty: '12', unitName: '件', baseQty: '12',
    payableAmount: '240', receivableAmount: '240', costAmount: '180',
    progress: { shippableQty: '12', receivedQty: '0' },
  }
  const order = {
    id: '71', documentNo: 'TEST-71', businessDate: '2026-11-18', status: 'draft',
    version: 1, totalAmount: '240', settlementStatus: 'none', fulfillmentStatus: 'none',
    allowedActions: [], fees: [], lines: [line],
  }
  await page.routeWebSocket('**/ws/**', (socket) => socket.close())
  await page.route('**/api/v1/**', (route) => {
    const path = new URL(route.request().url()).pathname.slice('/api/v1'.length)
    const data: Record<string, unknown> = {
      '/system/version': { apiMajor: 2 },
      '/auth/me': {
        id: '1', username: 'tester', displayName: '测试员', mustChangePassword: false,
        permissions: ['sales_order:view', 'purchase_order:view', 'cost:view'],
        companies: [{ id: '1', code: 'T1', name: '演练公司', allWarehouses: true, warehouseIds: [] }],
      },
      '/sales-orders/71/reservations': { items: [], nextCursor: null },
      '/notify/unread-count': { total: 0 },
      '/alerts/unhandled-count': { total: 0 },
      '/workflow/progress': { instances: [], tasks: [] },
      '/dicts': { version: '1', unchanged: false, items: {} },
    }
    data['/' + kind + '/71'] = order
    return route.fulfill({ json: { code: 200, message: '操作成功', data: data[path] ?? [] } })
  })
  await page.goto('/' + kind + '/71')
  await expect(page.getByText('钢制轴套', { exact: true })).toBeVisible()
}

function availableWidth(scrollArea: Locator): Promise<number> {
  return scrollArea.evaluate((area) => area.scrollWidth - area.clientWidth)
}

for (const config of [
  { kind: 'sales-orders', name: '销售订单', lastColumn: '可发 / 预留' },
  { kind: 'purchase-orders', name: '采购订单', lastColumn: '成本' },
] as const) {
  test(config.name + '明细可用键盘查看末列并返回，宽屏自动退出 Tab 顺序', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.setViewportSize({ width: 375, height: 812 })
    await openDetail(page, config.kind)
    const details = page.getByRole('region', { name: '明细与进度', exact: true })
    const scrollArea = details.locator('.el-table__body-wrapper .el-scrollbar__wrap')
    await expect.poll(() => availableWidth(scrollArea)).toBeGreaterThan(20)
    await expect(scrollArea).toHaveAttribute('tabindex', '0')

    await page.getByRole('tab', { name: '明细', exact: true }).focus()
    for (let step = 0; step < 5; step++) {
      await page.keyboard.press('Tab')
      if (await scrollArea.evaluate((area) => document.activeElement === area)) break
    }
    await expect(scrollArea).toBeFocused()
    await expect(scrollArea).toHaveCSS('outline-style', 'solid')
    await expect.poll(() => scrollArea.evaluate((area) => area.scrollLeft)).toBe(0)

    await expect.poll(async () => {
      await page.keyboard.press('ArrowRight')
      return scrollArea.evaluate((area) => area.scrollWidth - area.clientWidth - area.scrollLeft)
    }, { intervals: [80], timeout: 8000 }).toBeLessThanOrEqual(1)

    const lastHeading = details.getByRole('columnheader', { name: config.lastColumn })
    const headingBox = await lastHeading.boundingBox()
    const scrollBox = await scrollArea.boundingBox()
    if (!headingBox || !scrollBox) throw new Error('无法测量末列标题与明细滚动区')
    expect(headingBox.x).toBeGreaterThanOrEqual(scrollBox.x - 1)
    expect(headingBox.x + headingBox.width).toBeLessThanOrEqual(scrollBox.x + scrollBox.width + 1)

    await expect.poll(async () => {
      await page.keyboard.press('ArrowLeft')
      return scrollArea.evaluate((area) => area.scrollLeft)
    }, { intervals: [80], timeout: 8000 }).toBeLessThanOrEqual(1)
    await page.keyboard.press('Tab')
    await expect(scrollArea).not.toBeFocused()

    await page.setViewportSize({ width: 1440, height: 900 })
    await expect.poll(() => availableWidth(scrollArea)).toBeLessThanOrEqual(1)
    await expect(scrollArea).not.toHaveAttribute('tabindex', '0')
    await expect(lastHeading).toBeVisible()

    await page.setViewportSize({ width: 375, height: 812 })
    await expect(scrollArea).toHaveAttribute('tabindex', '0')
  })
}
