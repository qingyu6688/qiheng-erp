import { expect, test } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { env } from 'node:process'

interface Document {
  id: string
  version: number
  documentNo: string
  status: string
  stockCost?: string
  issuedCost?: string
  amount?: string
  lines: { id: string }[]
}
interface Master {
  id: string
  code: string
  units?: { unitId: string; isBase: boolean }[]
}

async function login(page: Page, user: string): Promise<void> {
  if (!env.ERP_E2E_PASSWORD || !/^E2E-[a-f0-9]{12}$/.test(env.ERP_E2E_TENANT_CODE ?? ''))
    throw new Error('采购调价验收必须使用随机隔离企业和临时口令')
  await page.goto('/login')
  await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE!)
  await page.getByLabel('用户名').fill(user)
  await page.locator('#password').fill(env.ERP_E2E_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
}

async function api<T>(page: Page, company: string, path: string, data?: unknown): Promise<T> {
  const csrf = (await page.context().cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')
  const response = await page.request.fetch('/api/v1' + path, {
    method: data === undefined ? 'GET' : 'POST',
    data,
    headers: {
      'X-Company-Id': company,
      'Idempotency-Key': randomUUID(),
      'X-XSRF-TOKEN': decodeURIComponent(csrf?.value ?? ''),
    },
  })
  expect(response.ok(), path + '：' + (await response.text())).toBeTruthy()
  return (await response.json()).data as T
}

async function settledDrawer(page: Page, drawer: Locator): Promise<void> {
  const width = page.viewportSize()!.width
  await expect
    .poll(async () => {
      const box = await drawer.boundingBox()
      return box !== null && Math.abs(box.x + box.width - width) < 1
    })
    .toBe(true)
}

test('真实采购和部分销售后调价，双人审批过账及右侧详情保持原页面', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(150_000)
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.setViewportSize({ width: 1440, height: 1000 })
  const reviewContext = await browser.newContext({
    baseURL: 'http://127.0.0.1:15173',
    locale: 'zh-CN',
  })
  const reviewer = await reviewContext.newPage()
  reviewer.on('pageerror', (error) => errors.push(error.message))
  try {
    await login(page, 'admin')
    await login(reviewer, 'reviewer')
    const profile = await api<{ companies: { id: string; code: string }[] }>(page, '', '/auth/me')
    const company = profile.companies.find((item) => item.code === 'DEMO-A')!.id
    const list = async (resource: string): Promise<Master[]> => {
      const data = await api<Master[] | { items: Master[] }>(
        page,
        company,
        '/' + resource + '?limit=200',
      )
      return Array.isArray(data) ? data : data.items
    }
    const partners = await list('partners'),
      warehouses = await list('warehouses'),
      skus = await list('skus')
    const supplier = partners.find((item) => item.code === 'E2E-M5-SUPPLIER')!.id
    const customer = partners.find((item) => item.code === 'E2E-CUSTOMER')!.id
    const warehouse = warehouses.find((item) => item.code === 'E2E-M5-WAREHOUSE')!.id
    const sku = skus.find((item) => item.code === 'E2E-M5-SKU')!.id
    const detail = await api<Master>(page, company, '/skus/' + sku)
    const unit = detail.units!.find((item) => item.isBase)!.unitId
    const day = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
    const purchase = await api<Document>(page, company, '/purchase-orders', {
      supplierId: supplier,
      businessDate: day,
      priceTaxInclusive: true,
      orderDiscountAmount: '0',
      lines: [
        {
          skuId: sku,
          warehouseId: warehouse,
          unitId: unit,
          qty: '10',
          unitPrice: '10',
          taxRate: '0',
        },
      ],
      fees: [],
    })
    await api(page, company, `/purchase-orders/${purchase.id}/submit`, { version: 0 })
    await api(reviewer, company, `/purchase-orders/${purchase.id}/approve`, { version: 1 })
    const receipt = await api<Document>(page, company, '/goods-receipts', {
      supplierId: supplier,
      warehouseId: warehouse,
      businessDate: day,
      lines: [{ orderLineId: purchase.lines[0]!.id, unitId: unit, qty: '10' }],
    })
    await api(page, company, `/goods-receipts/${receipt.id}/submit`, { version: 0 })
    await api(page, company, `/goods-receipts/${receipt.id}/post`, { version: 1 })
    const sale = await api<Document>(page, company, '/sales-orders', {
      customerId: customer,
      businessDate: day,
      priceTaxInclusive: true,
      orderDiscountAmount: '0',
      lines: [
        {
          skuId: sku,
          warehouseId: warehouse,
          unitId: unit,
          qty: '6',
          unitPrice: '20',
          taxRate: '0',
        },
      ],
      fees: [],
    })
    await api(page, company, `/sales-orders/${sale.id}/submit`, { version: 0 })
    await api(reviewer, company, `/sales-orders/${sale.id}/approve`, { version: 1 })
    const shipment = await api<Document>(page, company, '/shipments', {
      customerId: customer,
      warehouseId: warehouse,
      businessDate: day,
      deliveryMethod: 'pickup',
      lines: [{ orderLineId: sale.lines[0]!.id, unitId: unit, qty: '6' }],
    })
    await api(page, company, `/shipments/${shipment.id}/submit`, { version: 0 })
    await api(reviewer, company, `/shipments/${shipment.id}/post`, { version: 1 })

    await page.goto('/goods-receipts/' + receipt.id)
    await page.getByRole('button', { name: '采购调价', exact: true }).click()
    const drawer = page.getByRole('dialog', { name: '新建采购调价单', exact: true })
    await drawer.getByLabel('调价日期', { exact: true }).fill(day)
    await drawer
      .getByRole('textbox', { name: '调价原因', exact: true })
      .fill('结算补价，验证部分已出库成本')
    await drawer
      .locator('.el-select')
      .filter({ has: page.getByRole('combobox', { name: '第1行调整方式', exact: true }) })
      .click()
    await page.getByRole('option', { name: '新含税单价', exact: true }).click()
    await drawer.getByRole('textbox', { name: '第1行新含税单价', exact: true }).fill('12')
    const saved = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/v1/purchase-price-adjustments') &&
        response.request().method() === 'POST',
    )
    await drawer.getByRole('button', { name: '保存草稿', exact: true }).click()
    const created = (await (await saved).json()).data as Document
    const panel = page.getByRole('dialog', {
      name: '采购调价 · ' + created.documentNo,
      exact: true,
    })
    await expect(page).toHaveURL(new RegExp('/goods-receipts/' + receipt.id + '$'))
    await panel.getByRole('button', { name: '预览成本分配', exact: true }).click()
    await expect(panel.getByRole('heading', { name: '成本分配预览', exact: true })).toBeVisible()
    await panel.getByRole('button', { name: '提交审批', exact: true }).click()
    await panel.getByRole('button', { name: '确认提交审批', exact: true }).click()
    await expect(panel.getByText('待审批', { exact: true }).first()).toBeVisible()
    await panel.getByRole('button', { name: '关闭', exact: true }).click()

    await reviewer.goto('/purchase-price-adjustments/' + created.id)
    await reviewer.getByRole('button', { name: '批准', exact: true }).click()
    await reviewer
      .getByRole('dialog', { name: '批准', exact: true })
      .getByRole('button', { name: '确定', exact: true })
      .click()
    await expect(reviewer.getByText('已批准', { exact: true }).first()).toBeVisible()
    await page.goto('/purchase-price-adjustments')
    const row = page.getByRole('row').filter({ hasText: created.documentNo })
    await row.getByRole('button', { name: '查看', exact: true }).click()
    const posted = page.waitForResponse((response) =>
      response.url().endsWith(`/purchase-price-adjustments/${created.id}/post`),
    )
    await page
      .getByRole('dialog', { name: '采购调价 · ' + created.documentNo })
      .getByRole('button', { name: '过账', exact: true })
      .click()
    await page
      .getByRole('dialog', { name: '过账', exact: true })
      .getByRole('button', { name: '确定', exact: true })
      .click()
    const result = (await (await posted).json()).data as Document
    expect(result).toMatchObject({
      status: 'posted',
      amount: '20.00',
      stockCost: '8.00',
      issuedCost: '12.00',
    })
    await expect(page.getByRole('heading', { name: '成本处理结果', exact: true })).toBeVisible()
    await expect(page).toHaveURL(/\/purchase-price-adjustments$/)
    await expect(page.getByRole('dialog', { name: '过账', exact: true })).toBeHidden()
    await settledDrawer(
      page,
      page.getByRole('dialog', { name: '采购调价 · ' + created.documentNo }),
    )
    await page.screenshot({ path: info.outputPath('采购调价过账与成本拆分.png'), fullPage: true })
    await page
      .getByRole('dialog', { name: '采购调价 · ' + created.documentNo })
      .getByRole('button', { name: '关闭', exact: true })
      .click()
    await expect(row).toContainText('已过账')
    await row.getByRole('button', { name: '查看', exact: true }).click()
    await expect(page).toHaveURL(/\/purchase-price-adjustments$/)
    await page
      .getByRole('dialog', { name: '采购调价 · ' + created.documentNo })
      .getByRole('button', { name: '关闭', exact: true })
      .click()
    await expect(
      page.getByRole('dialog', { name: '采购调价 · ' + created.documentNo }),
    ).toHaveCount(0)
    await page.screenshot({ path: info.outputPath('采购调价列表保留原位置.png'), fullPage: true })
    await page.goto('/reports/purchase-summary?detail=true')
    const reportUrl = page.url()
    const reportRow = page.getByRole('row').filter({ hasText: created.documentNo })
    await reportRow.getByRole('button', { name: '查看原单', exact: true }).click()
    const reportDrawer = page.getByRole('dialog', { name: '采购调价 · ' + created.documentNo })
    await expect(
      reportDrawer.getByRole('heading', { name: '成本处理结果', exact: true }),
    ).toBeVisible()
    await expect(page).toHaveURL(reportUrl)
    await settledDrawer(page, reportDrawer)
    await page.screenshot({ path: info.outputPath('采购报表穿透右侧调价详情.png'), fullPage: true })
    await reportDrawer.getByRole('button', { name: '关闭', exact: true }).click()
    await expect(page).toHaveURL(reportUrl)
    await expect(reportRow).toBeVisible()
    expect(errors).toEqual([])
  } finally {
    await reviewContext.close()
  }
})
