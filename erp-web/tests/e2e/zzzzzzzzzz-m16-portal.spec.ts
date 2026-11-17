import { expect, test, type Route } from '@playwright/test'

function ok(route: Route, data: unknown): Promise<void> {
  return route.fulfill({ json: { code: 200, data, message: '操作成功' } })
}

test('M16 门户从独立入口登录，可核对订单、账单和客服来源', async ({ page }) => {
  await page.setViewportSize({ width: 420, height: 850 })
  let signedIn = false
  let confirmed = false
  let internalCalls = 0
  const profile = { id: '10', companyName: '演练公司', customerName: '门户客户', contactName: '测试联系人' }
  const order = { id: '9007199254740993', documentNo: 'PORTAL-ORDER-01', status: 'approved', totalAmount: '83.20' }
  await page.route('**/api/v1/auth/**', (route) => { internalCalls++; return route.abort() })
  await page.route('**/api/v1/portal/**', (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname.slice('/api/v1/portal'.length)
    expect(request.headers()['x-company-id']).toBeUndefined()
    if (request.method() === 'POST') expect(request.headers()['x-portal-xsrf-token']).toBe('portal-token')
    switch (path) {
      case '/auth/csrf': return route.fulfill({
        headers: { 'Set-Cookie': 'PORTAL-XSRF-TOKEN=portal-token; Path=/; SameSite=Strict' },
        json: { code: 200, data: { smsEnabled: false } },
      })
      case '/auth/me': return signedIn ? ok(route, profile) : route.fulfill({ status: 401,
        json: { code: 401, errorCode: 'PORTAL_LOGIN_REQUIRED', message: '请登录客户门户' } })
      case '/auth/login': signedIn = true; return ok(route, profile)
      case '/auth/logout': signedIn = false; return ok(route, null)
      case '/dashboard': return ok(route, { pendingReceiptCount: 0, openOrderCount: 1, unpaidAmount: '83.20', recentShipments: [] })
      case '/orders': return ok(route, { items: [order], nextCursor: null })
      case '/orders/9007199254740993': return ok(route, { order, lines: [{ skuName: '验收商品', unitName: '件', qty: '2.000000' }] })
      case '/statements': return ok(route, { digest: 'stmt', confirmation: confirmed ? { id: '3' } : null,
        items: [], summary: { closingBalance: '83.20' } })
      case '/statements/confirm': expect(request.postDataJSON().digest).toBe('stmt'); confirmed = true; return ok(route, { id: '3' })
      case '/assistant/status': return ok(route, { enabled: true })
      case '/assistant':
        expect(request.postDataJSON()).toEqual({ text: '查我的订单' })
        return ok(route, { conversationId: 'a', answerId: 'b', message: '最近订单', topic: 'orders', sourcePath: '/orders',
          refused: false, handedOff: false, items: [order], summary: {} })
      default: return route.abort()
    }
  })
  await page.goto('/portal.html')
  await expect(page.getByRole('heading', { name: '客户登录' })).toBeVisible()
  await page.getByLabel('企业编码').fill('PORTAL-FIXTURE')
  await page.getByLabel('联系人账号').fill('fixture@example.test')
  await page.getByLabel('密码', { exact: true }).fill('Temporary-test-only!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByText('未付款金额')).toBeVisible()
  await page.getByRole('link', { name: '我的订单' }).click()
  await expect(page.getByRole('cell', { name: 'PORTAL-ORDER-01' })).toBeVisible()
  await page.getByRole('button', { name: '查看', exact: true }).click()
  await expect(page.getByRole('cell', { name: '验收商品' })).toBeVisible()
  await page.getByRole('link', { name: '对账', exact: true }).click()
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: '确认对账' }).click()
  await expect(page.getByRole('button', { name: '已确认' })).toBeDisabled()
  await page.getByRole('link', { name: '在线客服' }).click()
  await page.getByLabel('咨询订单、物流、对账或发票').fill('查我的订单')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.getByText('PORTAL-ORDER-01', { exact: false })).toBeVisible()
  await page.getByRole('link', { name: '查看来源' }).click()
  await expect(page.getByRole('heading', { name: '我的订单' })).toBeVisible()
  await page.getByRole('button', { name: '退出' }).click()
  await expect(page.getByRole('heading', { name: '客户登录' })).toBeVisible()
  expect(internalCalls).toBe(0)
})
