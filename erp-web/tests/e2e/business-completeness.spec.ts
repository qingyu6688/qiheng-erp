import { expect, test } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { env } from 'node:process'

interface Master { id: string; code: string; name: string; units?: { unitId: string; isBase: boolean }[] }
interface SkuDetail extends Master {
  productId: string; spec: string | null; defaultTaxRate: string; purchasePrice: string; salePrice: string; minSalePrice: string; status: string; version: number
}
interface Doc { id: string; version: number; documentNo: string; status: string; totalAmount?: string; convertedOrderId?: string; convertedOrderNo?: string }
async function login(page: Page): Promise<void> {
  if (!env.ERP_E2E_PASSWORD || !/^E2E-[a-f0-9]{12}$/.test(env.ERP_E2E_TENANT_CODE ?? '')) throw new Error('业务完整性浏览器验收须使用随机隔离企业和临时口令')
  await page.goto('/login')
  await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE!)
  await page.getByLabel('用户名').fill('admin')
  await page.locator('#password').fill(env.ERP_E2E_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '退出登录', exact: true })).toBeVisible()
}
async function api<T>(page: Page, company: string, path: string, data?: unknown, method?: 'GET' | 'POST' | 'PUT'): Promise<T> {
  const csrf = (await page.context().cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')
  const response = await page.request.fetch('/api/v1' + path, { method: method ?? (data === undefined ? 'GET' : 'POST'), data,
    headers: { 'X-Company-Id': company, 'Idempotency-Key': randomUUID(), 'X-XSRF-TOKEN': decodeURIComponent(csrf?.value ?? '') } })
  expect(response.ok(), path + '：' + await response.text()).toBeTruthy()
  return (await response.json()).data as T
}
function skuInput(value: SkuDetail, minSalePrice: string): Record<string, unknown> {
  return { productId: value.productId, code: value.code, name: value.name, spec: value.spec, defaultTaxRate: value.defaultTaxRate,
    purchasePrice: value.purchasePrice, salePrice: value.salePrice, minSalePrice, status: value.status, version: value.version }
}
async function rightEdge(page: Page, drawer: Locator): Promise<void> {
  await expect.poll(async () => {
    const box = await drawer.boundingBox(), width = page.viewportSize()!.width
    return box !== null && box.x >= -1 && box.width <= width + 1 && Math.abs(box.x + box.width - width) < 1
  }).toBe(true)
}

test('采购协议取价、报价确认转单及成本重估详情在右侧展示，兼容390px', async ({ page }, info) => {
  test.setTimeout(150_000)
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.setViewportSize({ width: 1440, height: 1000 })
  await login(page)
  const profile = await api<{ companies: { id: string; code: string }[] }>(page, '', '/auth/me')
  const company = profile.companies.find((item) => item.code === 'DEMO-A')!.id
  const list = async (resource: string): Promise<Master[]> => {
    const result = await api<Master[] | { items: Master[] }>(page, company, '/' + resource + '?limit=200')
    return Array.isArray(result) ? result : result.items
  }
  const partners = await list('partners'), skus = await list('skus'), warehouses = await list('warehouses')
  const customer = partners.find((item) => item.code === 'E2E-CUSTOMER')!, supplier = partners.find((item) => item.code === 'E2E-M5-SUPPLIER')!
  const sku = skus.find((item) => item.code === 'E2E-SKU')!, warehouse = warehouses.find((item) => item.code === 'E2E-WAREHOUSE')!
  const unit = (await api<Master>(page, company, '/skus/' + sku.id)).units!.find((item) => item.isBase)!.unitId
  const day = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
  const listName = '浏览器采购协议-' + randomUUID().slice(0, 8)
  await api(page, company, '/purchase-price-lists', { name: listName, priceIncludesTax: true, validFrom: day, validTo: day, priority: 20, status: 'active',
    items: [{ skuId: sku.id, unitId: unit, minQty: '1', unitPrice: '11.30' }, { skuId: sku.id, unitId: unit, minQty: '10', unitPrice: '10.17' }],
    targets: [{ targetType: 'supplier', targetValue: supplier.id }] })
  const price = await api<{ lines: { unitPrice: string }[]; priceSources: { referenceSource: string }[] }>(page, company, '/pricing/preview', {
    documentType: 'purchase_order', supplierId: supplier.id, businessDate: day, priceTaxInclusive: false, orderDiscountAmount: '0',
    lines: [{ skuId: sku.id, unitId: unit, warehouseId: warehouse.id, qty: '12', taxRate: '0.13' }], fees: [] })
  expect(price.lines[0]!.unitPrice).toBe('9.00000000'); expect(price.priceSources[0]!.referenceSource).toBe('supplier_price_list')
  await page.goto('/purchase-price-lists')
  await page.getByRole('row').filter({ hasText: listName }).getByRole('button', { name: '查看', exact: true }).click()
  const priceDrawer = page.getByRole('dialog', { name: '采购价格表详情', exact: true })
  await expect(priceDrawer).toContainText('指定供应商')
  await rightEdge(page, priceDrawer)
  await page.screenshot({ path: info.outputPath('采购价格表及供应商阶梯.png'), fullPage: true })
  await priceDrawer.locator('.el-drawer__close-btn').click()
  await expect(page).toHaveURL(/\/purchase-price-lists$/)

  const quote = await api<Doc>(page, company, '/sales-quotes', { validTo: day, order: { customerId: customer.id, businessDate: day,
    priceTaxInclusive: true, orderDiscountAmount: '1', lines: [{ skuId: sku.id, unitId: unit, warehouseId: warehouse.id, qty: '2', unitPrice: '22.60', lineDiscountAmount: '1', taxRate: '0.13' }], fees: [], remark: '报价冻结及原位查看' } })
  await page.goto('/sales-quotes')
  await page.getByRole('row').filter({ hasText: quote.documentNo }).getByRole('button', { name: '查看', exact: true }).click()
  const quoteDrawer = page.getByRole('dialog', { name: '销售报价 · ' + quote.documentNo, exact: true })
  await expect(quoteDrawer.getByLabel('报价金额摘要')).toContainText('43.20')
  await expect(quoteDrawer.getByLabel('销售报价资料').locator('dt')).toHaveText(['客户', '报价日期', '报价口径', '商机', '备注'])
  await quoteDrawer.getByRole('button', { name: '确认报价', exact: true }).click()
  const submitted = page.waitForResponse((response) => response.url().endsWith(`/sales-quotes/${quote.id}/submit`) && response.request().method() === 'POST')
  await quoteDrawer.getByRole('button', { name: '确认', exact: true }).click()
  expect((await (await submitted).json()).data.status).toBe('issued')
  const converted = page.waitForResponse((response) => response.url().endsWith(`/sales-quotes/${quote.id}/convert`) && response.request().method() === 'POST')
  await quoteDrawer.getByRole('button', { name: '转销售订单', exact: true }).click()
  await page.getByRole('dialog', { name: '转销售订单', exact: true }).getByRole('button', { name: '确定', exact: true }).click()
  const result = (await (await converted).json()).data as Doc
  expect(result.status).toBe('converted'); expect(result.totalAmount).toBe('43.20'); expect(result.convertedOrderId).toMatch(/^[1-9][0-9]+$/)
  await expect(page).toHaveURL(/\/sales-quotes$/)
  await quoteDrawer.getByRole('button', { name: '右侧查看订单', exact: true }).click()
  const source = page.getByRole('dialog', { name: '销售订单 · ' + result.convertedOrderNo, exact: true })
  await expect(source).toContainText('43.20'); await rightEdge(page, source)
  await expect(source.getByLabel('销售订单来源资料').locator('dt')).toHaveText(['客户', '订单日期', '价税合计', '交货日期'])
  await page.screenshot({ path: info.outputPath('报价转单及只读订单来源.png'), fullPage: true })
  await source.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(quoteDrawer).toBeVisible(); await expect(page).toHaveURL(/\/sales-quotes$/)
  await page.setViewportSize({ width: 390, height: 844 }); await rightEdge(page, quoteDrawer)
  await page.screenshot({ path: info.outputPath('报价右侧详情390像素.png'), fullPage: true })
  await quoteDrawer.getByRole('button', { name: '关闭', exact: true }).click()
  const order = await api<{ status: string; totalAmount: string }>(page, company, '/sales-orders/' + result.convertedOrderId)
  expect(order.status).toBe('draft'); expect(order.totalAmount).toBe('43.20')

  const cost = await api<Doc>(page, company, '/cost-revaluations', { warehouseId: warehouse.id, skuId: sku.id, startDate: day, businessDate: day, reason: '核对当前期初成本' })
  await page.setViewportSize({ width: 1440, height: 1000 }); await page.goto('/cost-revaluations')
  await page.getByRole('row').filter({ hasText: cost.documentNo }).getByRole('button', { name: '查看', exact: true }).click()
  const costDrawer = page.getByRole('dialog', { name: '成本重估 · ' + cost.documentNo, exact: true })
  await expect(costDrawer.getByLabel('成本重估差异摘要')).toContainText('200.00')
  await expect(costDrawer.getByLabel('成本重估任务资料').locator('dt')).toHaveText(['仓库', '商品', '起算日', '调整入账日', '当前基础数量', '重放范围', '重估原因'])
  await expect(costDrawer).toContainText('当前没有需要调整的成本差异'); await rightEdge(page, costDrawer)
  await page.screenshot({ path: info.outputPath('成本重估零差异预览.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 }); await rightEdge(page, costDrawer)
  await page.screenshot({ path: info.outputPath('成本重估右侧详情390像素.png'), fullPage: true })
  await costDrawer.getByRole('button', { name: '关闭', exact: true }).click(); await expect(page).toHaveURL(/\/cost-revaluations$/)

  const originalSku = await api<SkuDetail>(page, company, '/skus/' + sku.id)
  const originalPolicy = await api<Record<string, unknown>>(page, company, `/companies/${company}/policy`)
  try {
    await api(page, company, '/skus/' + sku.id, skuInput(originalSku, '50'), 'PUT')
    const currentPolicy = await api<Record<string, unknown>>(page, company, `/companies/${company}/policy`)
    await api(page, company, `/companies/${company}/policy`, { ...currentPolicy, belowMinPricePolicy: 'warn' }, 'PUT')
    const low = await api<Doc>(page, company, '/sales-quotes', { validTo: day, order: { customerId: customer.id, businessDate: day,
      priceTaxInclusive: true, orderDiscountAmount: '0', lines: [{ skuId: sku.id, unitId: unit, warehouseId: warehouse.id, qty: '1', unitPrice: '20', taxRate: '0.13' }], fees: [] } })
    await api(page, company, `/sales-quotes/${low.id}/submit`, { version: low.version })
    const warningPolicy = await api<Record<string, unknown>>(page, company, `/companies/${company}/policy`)
    await api(page, company, `/companies/${company}/policy`, { ...warningPolicy, belowMinPricePolicy: 'block' }, 'PUT')
    const blocked = await api<Doc & { allowedActions: string[] }>(page, company, `/sales-quotes/${low.id}`)
    expect(blocked.allowedActions).not.toContain('convert')
    await page.setViewportSize({ width: 1440, height: 1000 }); await page.goto('/sales-quotes')
    await page.getByRole('row').filter({ hasText: low.documentNo }).getByRole('button', { name: '查看', exact: true }).click()
    const blockedDrawer = page.getByRole('dialog', { name: '销售报价 · ' + low.documentNo, exact: true })
    await expect(blockedDrawer).toContainText('当前公司禁止低于最低售价')
    await expect(blockedDrawer.getByRole('button', { name: '转销售订单', exact: true })).toHaveCount(0)
    await rightEdge(page, blockedDrawer)
    await page.screenshot({ path: info.outputPath('报价从提示改为禁止后的拦截.png'), fullPage: true })
    const csrf = (await page.context().cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')
    const forbidden = await page.request.post(`/api/v1/sales-quotes/${low.id}/convert`, { data: { version: blocked.version, businessDate: day },
      headers: { 'X-Company-Id': company, 'Idempotency-Key': randomUUID(), 'X-XSRF-TOKEN': decodeURIComponent(csrf?.value ?? '') } })
    expect(forbidden.status()).toBe(422)
    expect((await api<Doc>(page, company, `/sales-quotes/${low.id}`)).status).toBe('issued')
    await blockedDrawer.getByRole('button', { name: '关闭', exact: true }).click()
  } finally {
    const currentPolicy = await api<Record<string, unknown>>(page, company, `/companies/${company}/policy`)
    await api(page, company, `/companies/${company}/policy`, { ...originalPolicy, version: currentPolicy.version }, 'PUT')
    const currentSku = await api<SkuDetail>(page, company, '/skus/' + sku.id)
    await api(page, company, '/skus/' + sku.id, skuInput({ ...originalSku, version: currentSku.version }, originalSku.minSalePrice ?? '0'), 'PUT')
  }
  expect(errors).toEqual([])
})
