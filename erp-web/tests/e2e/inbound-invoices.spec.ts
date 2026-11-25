import { expect, test } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { env } from 'node:process'
import type { InboundInvoiceDetail } from '../../src/types/inbound-invoices'

interface Document {
  id: string
  documentNo: string
  version: number
  lines: { id: string }[]
}
interface Master {
  id: string
  code: string
  name: string
  taxNo?: string
  units?: { unitId: string; isBase: boolean }[]
}
async function login(page: Page, user: string): Promise<void> {
  if (!env.ERP_E2E_PASSWORD || !/^E2E-[a-f0-9]{12}$/.test(env.ERP_E2E_TENANT_CODE ?? ''))
    throw new Error('进项台账验收须使用随机测试企业和临时口令')
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
async function settle(page: Page, drawer: Locator): Promise<void> {
  await expect
    .poll(async () => {
      const box = await drawer.boundingBox(),
        width = page.viewportSize()!.width
      return box !== null && box.x >= -1 && Math.abs(box.x + box.width - width) < 1
    })
    .toBe(true)
}

test('进项登记、多对多尾差、丢失响应重试、分批到票与右侧历史闭环', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(180_000)
  await page.setViewportSize({ width: 1440, height: 1000 })
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  const reviewerContext = await browser.newContext({
    baseURL: 'http://127.0.0.1:15173',
    locale: 'zh-CN',
  })
  const reviewer = await reviewerContext.newPage()
  try {
    await login(page, 'admin')
    await login(reviewer, 'reviewer')
    const profile = await api<{ companies: { id: string; code: string }[] }>(page, '', '/auth/me')
    const company = profile.companies.find((item) => item.code === 'DEMO-A')!.id
    const all = async (resource: string): Promise<Master[]> => {
      const rows = await api<Master[] | { items: Master[] }>(
        page,
        company,
        '/' + resource + '?limit=200',
      )
      return Array.isArray(rows) ? rows : rows.items
    }
    const supplier = (await all('partners')).find((item) => item.code === 'E2E-M5-SUPPLIER')!
    const supplierDetail = await api<Master>(page, company, '/partners/' + supplier.id)
    const sku = (await all('skus')).find((item) => item.code === 'E2E-M5-SKU')!
    const unit = (await api<Master>(page, company, '/skus/' + sku.id)).units!.find(
      (item) => item.isBase,
    )!.unitId
    const warehouse = await api<Master>(page, company, '/warehouses', {
      code: 'INV-' + randomUUID().slice(0, 12),
      name: '进项核对验收仓',
    })
    const day = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
    async function receive(qty: string): Promise<Document> {
      const order = await api<Document>(page, company, '/purchase-orders', {
        supplierId: supplier.id,
        businessDate: day,
        priceTaxInclusive: true,
        orderDiscountAmount: '0',
        lines: [
          {
            skuId: sku.id,
            warehouseId: warehouse.id,
            unitId: unit,
            qty,
            unitPrice: '10',
            taxRate: '0',
          },
        ],
        fees: [],
      })
      await api(page, company, `/purchase-orders/${order.id}/submit`, { version: 0 })
      await api(reviewer, company, `/purchase-orders/${order.id}/approve`, { version: 1 })
      const receipt = await api<Document>(page, company, '/goods-receipts', {
        supplierId: supplier.id,
        warehouseId: warehouse.id,
        businessDate: day,
        lines: [{ orderLineId: order.lines[0]!.id, unitId: unit, qty }],
      })
      await api(page, company, `/goods-receipts/${receipt.id}/submit`, { version: 0 })
      await api(page, company, `/goods-receipts/${receipt.id}/post`, { version: 1 })
      return receipt
    }
    const first = await receive('10'),
      second = await receive('5')
    await page.goto('/e-invoices')
    await page.getByRole('tab', { name: '进项台账', exact: true }).click()
    const listUrl = page.url(),
      prefix = 'B04-' + randomUUID().slice(0, 8)
    await page.getByLabel('号码或销方', { exact: true }).fill(prefix)
    await page.getByRole('button', { name: '查询台账', exact: true }).click()
    async function register(
      no: string,
      amount: string,
    ): Promise<{ drawer: Locator; invoice: InboundInvoiceDetail }> {
      await page.getByRole('button', { name: '登记手工票', exact: true }).click()
      const create = page.getByRole('dialog', { name: '手工登记进项发票', exact: true })
      await create.getByRole('combobox', { name: '供应商', exact: true }).fill(supplier.code)
      await page
        .getByRole('option', { name: supplier.code + ' · ' + supplier.name, exact: true })
        .click()
      await create.getByLabel('发票号码', { exact: true }).fill(no)
      await create
        .getByLabel('销方纳税人识别号', { exact: true })
        .fill(supplierDetail.taxNo || 'TEST12345678')
      await create.getByLabel('价税合计（元）', { exact: true }).fill(amount)
      const submitted = page.waitForResponse(
        (response) =>
          response.url().endsWith('/api/v1/e-invoices/inbound') &&
          response.request().method() === 'POST',
      )
      await create.getByRole('button', { name: '保存手工票', exact: true }).click()
      const response = await submitted
      expect(response.status()).toBe(200)
      const invoice = (await response.json()).data as InboundInvoiceDetail
      const drawer = page.getByRole('dialog', { name: '进项发票 · ' + no, exact: true })
      await expect(drawer.getByText('已保存，并更新为当前详情。', { exact: true })).toBeVisible()
      await expect(page).toHaveURL(listUrl)
      return { drawer, invoice }
    }
    const created = await register(prefix + '-MULTI', '149.99'),
      drawer = created.drawer
    await drawer.getByRole('button', { name: '核对采购应付', exact: true }).click()
    const candidates = drawer.getByRole('region', { name: '同供应商候选应付', exact: true })
    const sourceRow = (no: string): Locator => candidates.getByRole('row').filter({ hasText: no })
    await sourceRow(first.documentNo).getByRole('button', { name: '加入分配', exact: true }).click()
    await sourceRow(second.documentNo)
      .getByRole('button', { name: '加入分配', exact: true })
      .click()
    const allocation = drawer.getByRole('region', { name: '编辑进项核对分配', exact: true })
    await allocation
      .getByRole('row')
      .filter({ hasText: second.documentNo })
      .getByRole('button', { name: '覆盖来源余额', exact: true })
      .click()
    await drawer.getByRole('button', { name: '预览核对', exact: true }).click()
    const result = drawer.getByRole('region', { name: '核对结果', exact: true })
    await expect(result.getByText('有差异', { exact: true })).toBeVisible()
    await expect(result.getByText('票面已分配完', { exact: true })).toBeVisible()
    await expect(
      drawer
        .getByRole('region', { name: '核对差异预览', exact: true })
        .locator('.financial-amount'),
    ).toHaveText(['0.00', '0.00', '−0.01', '0.00'])
    await settle(page, drawer)
    await expect(allocation.getByText(first.documentNo, { exact: true }).first()).toBeInViewport()
    await page.screenshot({ path: info.outputPath('进项多对多尾差预览.png') })
    await page.setViewportSize({ width: 390, height: 844 })
    await settle(page, drawer)
    await expect
      .poll(() =>
        drawer
          .locator('.inbound-panel')
          .evaluate((element) => element.scrollWidth <= element.clientWidth + 1),
      )
      .toBe(true)
    await page.screenshot({ path: info.outputPath('进项核对录入移动端.png') })
    await page.setViewportSize({ width: 1440, height: 1000 })
    await settle(page, drawer)
    const writes: { key: string; body: string | null }[] = []
    const allocationsUrl = `**/api/v1/e-invoices/inbound/${created.invoice.id}/allocations`
    await page.route(allocationsUrl, async (route) => {
      writes.push({
        key: route.request().headers()['idempotency-key']!,
        body: route.request().postData(),
      })
      const response = await route.fetch()
      expect(response.status()).toBe(200)
      if (writes.length === 1) await route.abort('failed')
      else await route.fulfill({ response })
    })
    await drawer.getByRole('button', { name: '保存核对', exact: true }).click()
    await expect(
      drawer.getByText('提交结果尚未确认。录入内容已锁定，请重试本次提交确认结果。', {
        exact: true,
      }),
    ).toBeVisible()
    await expect(drawer.getByRole('button', { name: '关闭', exact: true })).toBeDisabled()
    await expect(allocation.getByRole('textbox').first()).toBeDisabled()
    await drawer.getByRole('button', { name: '重试本次提交', exact: true }).click()
    await expect(drawer.getByText('已保存，并更新为当前详情。', { exact: true })).toBeVisible()
    expect(writes).toHaveLength(2)
    expect(writes[1]).toEqual(writes[0])
    const committed = await api<InboundInvoiceDetail>(
      page,
      company,
      `/e-invoices/inbound/${created.invoice.id}`,
    )
    expect(committed).toMatchObject({
      version: 1,
      absoluteDifference: '0.01',
      untaxedDifference: '-0.01',
      fullyAllocated: true,
      matchStatus: 'difference',
    })
    expect(committed.allocations).toHaveLength(2)
    await page.unroute(allocationsUrl)
    await page.setViewportSize({ width: 390, height: 844 })
    await settle(page, drawer)
    await expect
      .poll(() =>
        drawer
          .locator('.inbound-panel')
          .evaluate((element) => element.scrollWidth <= element.clientWidth + 1),
      )
      .toBe(true)
    await page.screenshot({ path: info.outputPath('进项核对移动端.png') })
    await page.setViewportSize({ width: 1440, height: 1000 })
    await drawer.getByRole('button', { name: '解除当前核对', exact: true }).click()
    await page
      .getByRole('dialog', { name: '解除当前核对', exact: true })
      .getByRole('button', { name: '确定', exact: true })
      .click()
    await expect(
      drawer
        .getByRole('region', { name: '已释放核对历史', exact: true })
        .getByText('手工解除核对', { exact: true }),
    ).toHaveCount(2)
    await drawer.getByRole('button', { name: '更正手工票', exact: true }).click()
    await drawer.getByLabel('价税合计（元）', { exact: true }).fill('60.00')
    await drawer.getByRole('button', { name: '保存手工票', exact: true }).click()
    await expect(drawer.getByText('已保存，并更新为当前详情。', { exact: true })).toBeVisible()
    await drawer.getByRole('button', { name: '核对采购应付', exact: true }).click()
    await sourceRow(first.documentNo).getByRole('button', { name: '加入分配', exact: true }).click()
    await drawer.getByRole('button', { name: '预览核对', exact: true }).click()
    await drawer.getByRole('button', { name: '保存核对', exact: true }).click()
    await expect(drawer.getByText('已保存，并更新为当前详情。', { exact: true })).toBeVisible()
    await drawer.getByRole('button', { name: '关闭', exact: true }).click()
    await expect(page.getByLabel('号码或销方', { exact: true })).toHaveValue(prefix)
    await expect(page).toHaveURL(listUrl)
    const split = await register(prefix + '-SPLIT', '40.00'),
      splitDrawer = split.drawer
    await splitDrawer.getByRole('button', { name: '核对采购应付', exact: true }).click()
    const splitSource = splitDrawer
      .getByRole('region', { name: '同供应商候选应付', exact: true })
      .getByRole('row')
      .filter({ hasText: first.documentNo })
    await expect(splitSource.locator('.financial-amount')).toHaveText(['40.00', '0.00'])
    await splitSource.getByRole('button', { name: '加入分配', exact: true }).click()
    await splitDrawer.getByRole('button', { name: '预览核对', exact: true }).click()
    await splitDrawer.getByRole('button', { name: '保存核对', exact: true }).click()
    await expect(splitDrawer.getByText('已保存，并更新为当前详情。', { exact: true })).toBeVisible()
    await page.screenshot({ path: info.outputPath('进项分批到票及历史.png') })
    await splitDrawer.getByRole('button', { name: '作废手工票', exact: true }).click()
    const voidPrompt = page.getByRole('dialog', { name: '作废手工发票', exact: true })
    await voidPrompt.getByRole('textbox').fill('验收作废，保留分配历史')
    await voidPrompt.getByRole('button', { name: '确认作废', exact: true }).click()
    await expect(splitDrawer.getByText('已作废', { exact: true })).toBeVisible()
    await expect(
      splitDrawer.getByRole('button', { name: '核对采购应付', exact: true }),
    ).toHaveCount(0)
    const report = await api<{ summary: { uninvoicedAmount: string } }>(
      page,
      company,
      `/reports/uninvoiced-receipts?dateFrom=${day}&dateTo=${day}&warehouseId=${warehouse.id}`,
    )
    expect(report.summary.uninvoicedAmount).toBe('90.00')
    await splitDrawer.getByRole('button', { name: '关闭', exact: true }).click()
    await expect(page.getByLabel('号码或销方', { exact: true })).toHaveValue(prefix)
    await page.screenshot({ path: info.outputPath('进项台账筛选保留.png'), fullPage: true })
    expect(errors).toEqual([])
  } finally {
    await reviewerContext.close()
  }
})
