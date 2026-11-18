import { expect, test } from '@playwright/test'

const examples = {
  'sales-orders': {
    owner: 'sales_order',
    fulfillment: 'shipments',
    fulfillmentPermission: 'shipment:view',
    cash: 'receipts',
    cashPermission: 'receipt:view',
    returned: 'sales-returns',
    returnPermission: 'sales_return:view',
    documentNo: 'SO-70',
    relatedNo: 'FH-90',
    allocationNo: 'SK-85',
  },
  'purchase-orders': {
    owner: 'purchase_order',
    fulfillment: 'goods-receipts',
    fulfillmentPermission: 'goods_receipt:view',
    cash: 'payments',
    cashPermission: 'payment:view',
    returned: 'purchase-returns',
    returnPermission: 'purchase_return:view',
    documentNo: 'PO-70',
    relatedNo: 'SH-90',
    allocationNo: 'FK-85',
  },
} as const

for (const kind of ['sales-orders', 'purchase-orders'] as const) {
  test(`${kind} 详情显示真实履约进度、关联核销和日志`, async ({ page }, testInfo) => {
    const fixture = examples[kind]
    const sales = kind === 'sales-orders'
    const requests: string[] = []
    const companyId = '1'
    await page.routeWebSocket('**/ws/**', (socket) => socket.close())
    await page.route('**/api/v1/**', (route) => {
      const path = new URL(route.request().url())
      const endpoint = path.pathname.slice('/api/v1'.length)
      const ok = (data: unknown): Promise<void> => route.fulfill({ json: { data } })
      if (endpoint === '/system/version') return ok({ apiMajor: 2 })
      if (endpoint === '/auth/me')
        return ok({
          id: '560',
          username: 'verify',
          displayName: '订单验收员',
          mustChangePassword: false,
          permissions: [
            `${fixture.owner}:view`,
            fixture.fulfillmentPermission,
            fixture.cashPermission,
            fixture.returnPermission,
            'settlement:view',
            'audit:view',
          ],
          companies: [
            { id: companyId, code: 'A', name: '验收公司', allWarehouses: true, warehouseIds: [] },
          ],
        })
      if (endpoint === `/${kind}/70`)
        return ok({
          id: '70',
          documentNo: fixture.documentNo,
          status: 'approved',
          version: 3,
          customerId: '31',
          customerName: '验收客户',
          supplierId: '32',
          supplierName: '验收供应商',
          businessDate: '2026-11-18',
          submittedAt: '2026-11-18T02:00:00Z',
          approvedAt: '2026-11-18T02:03:00Z',
          updatedAt: '2026-11-18T02:00:00Z',
          totalAmount: '1800.00',
          settlementStatus: 'partial',
          priceTaxInclusive: true,
          lines: [
            {
              id: '72',
              skuName: '样例商品',
              qty: '20',
              unitName: '件',
              progress: {
                baseQty: '20',
                closedQty: '3',
                shippedQty: '10',
                signedQty: '8',
                receivedQty: '14',
              },
            },
          ],
          allowedActions: [],
        })
      if (endpoint === `/${kind}/70/settlement-summary`) {
        expect(route.request().headers()['x-company-id']).toBe(companyId)
        return ok({ allocatedCashAmount: '235.80', openAmount: '1194.19', scope: sales ? 'company' : 'visible' })
      }
      if (endpoint.startsWith(`/${kind}/70/related/`)) {
        expect(route.request().headers()['x-company-id']).toBe(companyId)
        const resource = endpoint.split('/').at(-1)
        requests.push(`${resource}:${path.searchParams.get('cursor') ?? ''}`)
        if (resource === fixture.fulfillment) {
          const more = path.searchParams.get('cursor') === '90'
          return ok({
            items: [
              {
                id: more ? '89' : '90',
                documentId: more ? '89' : '90',
                documentNo: more ? '关联单据第二页' : fixture.relatedNo,
                status: 'posted',
                businessDate: '2026-11-18',
              },
            ],
            nextCursor: more ? null : '90',
          })
        }
        if (resource === 'settlement-allocations')
          return ok({
            items: [
              {
                id: '86',
                documentId: '85',
                documentNo: fixture.allocationNo,
                status: 'active',
                amount: '42.50',
                businessDate: '2026-11-18',
              },
            ],
            nextCursor: null,
          })
        return ok({ items: [], nextCursor: null })
      }
      if (endpoint === '/audit-logs') {
        expect(path.searchParams.get('entityType')).toBe(fixture.owner)
        expect(path.searchParams.get('entityId')).toBe('70')
        expect(path.searchParams.get('companyId')).toBe(companyId)
        requests.push('audit')
        return ok({
          items: [
            {
              id: '95',
              createdAt: '2026-11-18T02:03:04Z',
              action: `${fixture.owner}.create`,
              operatorName: '操作验收员',
              entityId: '70',
              entityType: fixture.owner,
            },
          ],
          nextCursor: null,
        })
      }
      if (endpoint === '/notify/unread-count') return ok({ total: 0 })
      if (endpoint === '/workbench/summary') return ok({ blocks: [] })
      return ok({ items: [], nextCursor: null })
    })

    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/${kind}/70`)
    const summary = page.getByRole('region', { name: '订单摘要' })
    async function assertOverview(width: number): Promise<void> {
      await expect(summary).toBeVisible()
      await expect(summary.locator('[data-order-metric="amount"]')).toHaveText('1,800.00')
      await expect(summary.locator('[data-order-metric="settlement"]')).toHaveText('部分结算')
      await expect(summary.getByText(sales ? '已核销收款' : '已核销付款', { exact: true })).toBeVisible()
      await expect(summary.getByText(sales ? '应收未清' : '应付未清', { exact: true })).toBeVisible()
      await expect(summary.locator('[data-order-metric="allocated-cash"]')).toHaveText('235.80')
      await expect(summary.locator('[data-order-metric="open-amount"]')).toHaveText('1,194.19')
      await expect(summary.getByText('结算金额仅汇总当前可见范围。')).toHaveCount(sales ? 0 : 1)
      const step = sales ? 'shipped' : 'received'
      await expect(summary.locator(`[data-order-metric="${step}"]`)).toHaveText(
        sales ? '58.8%' : '82.3%',
      )
      if (sales) await expect(summary.locator('[data-order-metric="signed"]')).toHaveText('47%')
      const current = summary.locator('[aria-current="step"]')
      await expect(current).toBeVisible()
      await expect(current).toHaveAttribute('data-step', step)
      await expect(summary.getByText('42.50', { exact: true })).toHaveCount(0)
      await expect(page.getByText('操作未完成，请重试。')).toHaveCount(0)
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
      await summary.scrollIntoViewIfNeeded()
      await page.screenshot({ path: testInfo.outputPath(`order-detail-${width}.png`), animations: 'disabled' })
    }

    await assertOverview(1440)
    const side = page.getByRole('region', { name: '关联单据' })
    await expect(side.getByRole('link', { name: fixture.relatedNo })).toHaveAttribute(
      'href',
      `/${fixture.fulfillment}/90`,
    )
    expect(requests).not.toContain('audit')
    await page.getByRole('tab', { name: '收发记录' }).click()
    await page.getByRole('button', { name: '加载更多' }).click()
    await expect(
      page.getByRole('tabpanel').getByRole('link', { name: '关联单据第二页' }),
    ).toHaveAttribute('href', `/${fixture.fulfillment}/89`)
    expect(requests).toContain(`${fixture.fulfillment}:90`)
    await page.getByRole('tab', { name: '结算' }).click()
    const settlement = page.getByRole('tabpanel')
    await expect(settlement.getByText('42.50', { exact: true })).toBeVisible()
    await expect(settlement.getByText('已核销', { exact: true })).toBeVisible()
    await expect(settlement.getByRole('link', { name: fixture.allocationNo })).toHaveAttribute(
      'href',
      `/${fixture.cash}/85`,
    )
    await page.getByRole('tab', { name: '操作日志' }).click()
    await expect(page.getByRole('tabpanel').getByText('操作验收员')).toBeVisible()
    expect(requests).toContain('audit')

    await page.setViewportSize({ width: 375, height: 812 })
    await page.getByRole('tab', { name: '明细' }).click()
    await expect(page.getByRole('tab', { name: '明细' })).toHaveAttribute('aria-selected', 'true')
    await assertOverview(375)
  })
}

test('只有订单查看权限时不请求下游记录和审计', async ({ page }) => {
  const requested: string[] = []
  await page.routeWebSocket('**/ws/**', (socket) => socket.close())
  await page.route('**/api/v1/**', (route) => {
    const endpoint = new URL(route.request().url()).pathname.slice('/api/v1'.length)
    const ok = (data: unknown): Promise<void> => route.fulfill({ json: { data } })
    requested.push(endpoint)
    if (endpoint === '/system/version') return ok({ apiMajor: 2 })
    if (endpoint === '/auth/me')
      return ok({
        id: '560',
        username: 'verify',
        displayName: '查看员',
        mustChangePassword: false,
        permissions: ['sales_order:view'],
        companies: [
          { id: '1', code: 'A', name: '验收公司', allWarehouses: true, warehouseIds: [] },
        ],
      })
    if (endpoint === '/sales-orders/70')
      return ok({
        id: '70',
        documentNo: 'SO-70',
        status: 'approved',
        version: 2,
        customerId: '31',
        businessDate: '2026-11-18',
        lines: [],
        allowedActions: [],
      })
    if (endpoint === '/notify/unread-count') return ok({ total: 0 })
    if (endpoint === '/workbench/summary') return ok({ blocks: [] })
    return ok({ items: [], nextCursor: null })
  })
  await page.goto('/sales-orders/70')
  await expect(page.getByRole('tab', { name: '明细' })).toBeVisible()
  for (const label of ['收发记录', '结算', '操作日志'])
    await expect(page.getByRole('tab', { name: label })).toHaveCount(0)
  await expect(page.locator('[data-order-metric="open-amount"]')).toHaveCount(0)
  expect(requested.filter((path) => path.includes('/related/') || path.endsWith('/settlement-summary') || path === '/audit-logs')).toEqual(
    [],
  )
})
