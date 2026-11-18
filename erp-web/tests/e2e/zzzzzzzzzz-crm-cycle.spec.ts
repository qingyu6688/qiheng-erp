import { expect, test, type Route } from '@playwright/test'

function ok(route: Route, data: unknown): Promise<void> {
  return route.fulfill({ json: { code: 200, data, message: '操作成功' } })
}

test('合同分批生成草稿保留行标识、仓库范围和精确数量', async ({ page }) => {
  const company = '101'
  const contractId = '901'
  const lineId = '9007199254740995'
  const warehouseId = '880'
  const salesId = '970'
  await page.routeWebSocket('**/ws/**', (socket) => socket.close())
  await page.route('**/api/v1/**', (route) => {
    if (new URL(route.request().url()).pathname === '/api/v1/system/version') return route.fallback()
    const request = route.request()
    const path = new URL(request.url()).pathname
    switch (path) {
      case '/api/v1/auth/me':
        return ok(route, {
          id: '3',
          username: 'reviewer',
          displayName: '测试用户',
          mustChangePassword: false,
          permissions: [
            'crm_contract:view',
            'crm_contract:order',
            'sales_order:create',
            'sales_order:view',
            'warehouse:view',
          ],
          companies: [
            {
              id: company,
              code: 'A01',
              name: '验收公司',
              allWarehouses: false,
              warehouseIds: [warehouseId],
            },
          ],
        })
      case '/api/v1/workbench/summary':
        return ok(route, { blocks: [] })
      case '/api/v1/notify/unread-count':
        return ok(route, { total: 0 })
      case '/api/v1/notify/important-notices':
        return ok(route, [])
      case '/api/v1/crm/contracts':
        return ok(route, { items: [], nextCursor: null })
      case `/api/v1/crm/contracts/${contractId}`:
        expect(request.headers()['x-company-id']).toBe(company)
        return ok(route, {
          id: contractId,
          name: '已审批的订货合同',
          documentNo: 'CRM-901',
          status: 'approved',
          allowedActions: ['create_sales_order'],
          totalAmount: '100.00',
          lines: [{ id: lineId, skuName: '验收商品', qty: '5.000000', remainingQty: '5.000000' }],
        })
      case '/api/v1/warehouses':
        expect(request.headers()['x-company-id']).toBe(company)
        return ok(route, { items: [{ id: warehouseId, name: '授权发货仓' }], nextCursor: null })
      case `/api/v1/crm/contracts/${contractId}/create-sales-order`:
        expect(request.method()).toBe('POST')
        expect(request.headers()['x-company-id']).toBe(company)
        expect(request.headers()['idempotency-key']).toMatch(/^[A-Za-z0-9_-]+$/)
        expect(request.postDataJSON().lines).toEqual([
          { contractLineId: lineId, warehouseId, qty: '2.500001' },
        ])
        return ok(route, { id: salesId, status: 'draft' })
      case `/api/v1/sales-orders/${salesId}`:
        return ok(route, { id: salesId, documentNo: 'SO-970', status: 'draft', allowedActions: [] })
      case `/api/v1/sales-orders/${salesId}/reservations`:
        return ok(route, { items: [] })
      case '/api/v1/attachments':
        return ok(route, [])
      default:
        return route.fulfill({ status: 404, json: { message: '测试接口不存在' } })
    }
  })

  await page.goto(`/crm/contracts/${contractId}`)
  await expect(page.getByRole('dialog', { name: 'CRM-901' })).toBeVisible()
  await page.getByRole('button', { name: '生成销售订单' }).click()
  const dialog = page.getByRole('dialog', { name: '生成销售订单' })
  await expect(dialog.getByText('验收商品 · 剩余 5.000000')).toBeVisible()
  await dialog.getByRole('textbox', { name: '本次数量' }).fill('2.500001')
  await dialog.getByRole('combobox').click()
  await page.getByRole('option', { name: '授权发货仓' }).click()
  await dialog.getByRole('button', { name: '生成草稿' }).click()
  await expect(page).toHaveURL(/\/sales-orders\/970$/)
  await expect(page.getByRole('heading', { name: 'SO-970' })).toBeVisible()
})
