import { expect, test } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { env } from 'node:process'

interface Document {
  id: string
  version: number
  documentNo: string
  status: string
  amount?: string
  lines: { id: string; costAmount?: string; signedCostAmount?: string }[]
}
interface Master {
  id: string
  code: string
  units?: { unitId: string; isBase: boolean }[]
}
interface Receivable {
  id: string
  sourceId: string
  sourceType: string
  amount: string
}

async function login(page: Page, username: string): Promise<void> {
  if (!env.ERP_E2E_PASSWORD || !/^E2E-[a-f0-9]{12}$/.test(env.ERP_E2E_TENANT_CODE ?? ''))
    throw new Error('销售调价浏览器验收必须使用随机隔离企业和临时口令')
  await page.goto('/login')
  await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE!)
  await page.getByLabel('用户名').fill(username)
  await page.locator('#password').fill(env.ERP_E2E_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '退出登录', exact: true })).toBeVisible()
}
async function changeUser(page: Page, username: string): Promise<void> {
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible()
  await login(page, username)
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
async function confirm(page: Page, label: string): Promise<void> {
  await page
    .getByRole('dialog', { name: label, exact: true })
    .getByRole('button', { name: '确定', exact: true })
    .click()
}
async function settled(page: Page, drawer: Locator): Promise<void> {
  const width = page.viewportSize()!.width
  await expect
    .poll(async () => {
      const box = await drawer.boundingBox()
      return (
        box !== null &&
        box.x >= -1 &&
        box.width <= width + 1 &&
        Math.abs(box.x + box.width - width) < 1
      )
    })
    .toBe(true)
  await expect
    .poll(() => drawer.evaluate((element) => element.scrollWidth <= element.clientWidth + 1))
    .toBe(true)
}
async function approve(reviewer: Page, document: Document): Promise<void> {
  await reviewer.goto('/sales-price-adjustments/' + document.id)
  await reviewer.getByRole('button', { name: '批准', exact: true }).click()
  await confirm(reviewer, '批准')
  await expect(reviewer.getByText('已批准', { exact: true }).first()).toBeVisible()
}

test('无成本角色调价、原键恢复、整票红字和右侧报表形成真实闭环', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(240_000)
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.setViewportSize({ width: 1440, height: 1000 })
  const context = await browser.newContext({ baseURL: 'http://127.0.0.1:15173', locale: 'zh-CN' })
  const reviewer = await context.newPage()
  reviewer.on('pageerror', (error) => errors.push(error.message))
  try {
    await login(page, 'admin')
    await login(reviewer, 'reviewer')
    const profile = await api<{ companies: { id: string; code: string }[] }>(page, '', '/auth/me')
    const company = profile.companies.find((item) => item.code === 'DEMO-A')!.id
    const list = async (resource: string): Promise<Master[]> => {
      const result = await api<Master[] | { items: Master[] }>(
        page,
        company,
        '/' + resource + '?limit=200',
      )
      return Array.isArray(result) ? result : result.items
    }
    const partners = await list('partners'),
      skus = await list('skus')
    const supplier = partners.find((item) => item.code === 'E2E-M5-SUPPLIER')!.id
    const customer = partners.find((item) => item.code === 'E2E-CUSTOMER')!.id
    const sku = skus.find((item) => item.code === 'E2E-M5-SKU')!.id
    const unit = (await api<Master>(page, company, '/skus/' + sku)).units!.find(
      (item) => item.isBase,
    )!.unitId
    const warehouse = (
      await api<Master>(page, company, '/warehouses', {
        code: 'SA-' + randomUUID().slice(0, 12),
        name: '销售调价浏览器验收仓',
      })
    ).id
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
          unitPrice: '11.30',
          taxRate: '0.13',
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
          unitPrice: '22.60',
          taxRate: '0.13',
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
    await changeUser(page, 'sales-price-maker')
    await changeUser(reviewer, 'sales-price-reviewer')
    for (const actor of [page, reviewer]) {
      const current = await api<{ permissions: string[] }>(actor, '', '/auth/me')
      expect(current.permissions).not.toContain('cost:view')
      expect(current.permissions).toContain('sales_price_adjustment:view')
    }

    let created: Document | undefined
    let abortFirst = true
    const keys: string[] = []
    await page.route('**/api/v1/sales-price-adjustments', async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      keys.push(route.request().headers()['idempotency-key'] ?? '')
      if (abortFirst) {
        abortFirst = false
        const response = await route.fetch()
        expect(response.ok()).toBeTruthy()
        created = (await response.json()).data as Document
        await route.abort('failed')
      } else await route.continue()
    })
    await page.goto('/shipments/' + shipment.id)
    await page.getByRole('button', { name: '销售调价', exact: true }).click()
    const editor = page.getByRole('dialog', { name: '新建销售调价单', exact: true })
    await expect(editor.getByRole('button', { name: '添加明细', exact: true })).toBeEnabled()
    await editor.getByRole('button', { name: '添加明细', exact: true }).click()
    await editor.getByRole('textbox', { name: '调价原因', exact: true }).fill('浏览器结算补价')
    await editor.getByRole('textbox', { name: '第1行含税调整金额', exact: true }).fill('13.56')
    await editor.getByRole('button', { name: '保存草稿', exact: true }).click()
    await expect(editor.getByRole('button', { name: '重试本次请求', exact: true })).toBeVisible()
    await expect(
      editor.getByRole('textbox', { name: '第1行含税调整金额', exact: true }),
    ).toBeDisabled()
    expect(created).toBeDefined()
    await editor.getByRole('button', { name: '重试本次请求', exact: true }).click()
    const adjustment = created!
    const panel = page.getByRole('dialog', {
      name: '销售调价 · ' + adjustment.documentNo,
      exact: true,
    })
    await expect(panel).toBeVisible()
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBeTruthy()
    expect(keys[1]).toBe(keys[0])
    await page.unroute('**/api/v1/sales-price-adjustments')
    await expect(page).toHaveURL(new RegExp('/shipments/' + shipment.id + '$'))
    await panel.getByRole('button', { name: '提交审批', exact: true }).click()
    await panel.getByRole('button', { name: '确认提交审批', exact: true }).click()
    await expect(panel.getByText('待审批', { exact: true }).first()).toBeVisible()
    await approve(reviewer, adjustment)
    await panel.getByRole('button', { name: '刷新单据', exact: true }).click()
    await panel.getByRole('button', { name: '过账', exact: true }).click()
    await confirm(page, '过账')
    await expect(panel.getByText('已过账', { exact: true }).first()).toBeVisible()
    await expect(panel.locator('.price-new-value')).toHaveText('24.86')
    await expect(panel.getByText('成本处理结果', { exact: true })).toHaveCount(0)
    await settled(page, panel)
    await page.screenshot({ path: info.outputPath('销售补价与原键恢复.png'), fullPage: true })
    await panel.getByRole('button', { name: '关闭', exact: true }).click()

    const receivables = (
      await api<{ items: Receivable[] }>(
        page,
        company,
        `/receivables?customerId=${customer}&limit=200`,
      )
    ).items
    const original = receivables.find(
      (item) => item.sourceType === 'shipment_line' && item.sourceId === shipment.id,
    )!
    const extra = receivables.find(
      (item) => item.sourceType === 'price_adjustment_line' && item.sourceId === adjustment.id,
    )!
    const invoiceNo = 'B' + randomUUID().replaceAll('-', '') + 'X'.repeat(31)
    const blue = await api<Document>(page, company, '/invoice-registrations', {
      invoiceType: 'sales',
      partnerId: customer,
      invoiceNo,
      invoiceDate: day,
      amount: '149.16',
      taxAmount: '17.16',
      lines: [
        { receivableId: original.id, amount: '135.60' },
        { receivableId: extra.id, amount: '13.56' },
      ],
    })
    const discount = await api<Document>(page, company, '/sales-price-adjustments', {
      partnerId: customer,
      warehouseId: warehouse,
      businessDate: day,
      reason: '浏览器整票红字后折让',
      lines: [{ shipmentLineId: shipment.lines[0]!.id, adjustmentAmount: '-13.56' }],
    })
    await api(page, company, `/sales-price-adjustments/${discount.id}/submit`, { version: 0 })
    await approve(reviewer, discount)
    await page.goto('/sales-price-adjustments/' + discount.id)
    const discountPanel = page.getByRole('dialog', {
      name: '销售调价 · ' + discount.documentNo,
      exact: true,
    })
    await discountPanel.getByRole('button', { name: '过账', exact: true }).click()
    await confirm(page, '过账')
    await expect(discountPanel.getByText('该发货行已经开票', { exact: false })).toBeVisible()
    await discountPanel.getByRole('button', { name: '查看原票及红字', exact: true }).click()
    const invoicePanel = page.getByRole('dialog', {
      name: '原票及红字 · ' + invoiceNo,
      exact: true,
    })
    await expect(invoicePanel.getByRole('row')).toHaveCount(3)
    await invoicePanel.getByRole('button', { name: '登记实际红字', exact: true }).click()
    const redNo = 'RED-' + randomUUID().replaceAll('-', '')
    await invoicePanel.getByRole('textbox', { name: '手工红字发票号', exact: true }).fill(redNo)
    await invoicePanel
      .getByRole('textbox', { name: '手工红字原因', exact: true })
      .fill('结算折让，完整冲红原票两条来源')
    await invoicePanel.getByRole('button', { name: '登记并等待财务确认', exact: true }).click()
    await expect(invoicePanel.getByText('待财务确认', { exact: true })).toBeVisible()
    await expect(
      invoicePanel.getByRole('button', { name: '财务确认红字', exact: true }),
    ).toHaveCount(0)
    await page.setViewportSize({ width: 390, height: 844 })
    await settled(page, invoicePanel)
    await expect
      .poll(() =>
        invoicePanel
          .locator('.el-drawer__title')
          .evaluate((element) => element.scrollWidth <= element.clientWidth + 1),
      )
      .toBe(true)
    await page.screenshot({ path: info.outputPath('整票红字移动端来源.png') })
    await invoicePanel.getByText('待财务确认', { exact: true }).scrollIntoViewIfNeeded()
    await page.screenshot({ path: info.outputPath('手工红字待确认移动端.png') })
    await page.setViewportSize({ width: 1440, height: 1000 })
    await reviewer.goto('/manual-invoice-reds')
    await reviewer
      .getByRole('row')
      .filter({ hasText: redNo })
      .getByRole('button', { name: '查看原票及红字', exact: true })
      .click()
    const reviewInvoicePanel = reviewer.getByRole('dialog', {
      name: '原票及红字 · ' + invoiceNo,
      exact: true,
    })
    await reviewInvoicePanel.getByRole('button', { name: '财务确认红字', exact: true }).click()
    await confirm(reviewer, '财务确认红字')
    await expect(reviewInvoicePanel.getByText('红字已确认', { exact: true })).toBeVisible()
    await reviewInvoicePanel.getByText('红字已确认', { exact: true }).scrollIntoViewIfNeeded()
    await reviewer.screenshot({ path: info.outputPath('另一人确认整票红字.png'), fullPage: true })
    await invoicePanel.getByRole('button', { name: '刷新原票及红字', exact: true }).click()
    await expect(invoicePanel.getByText('红字已确认', { exact: true })).toBeVisible()
    await invoicePanel.getByRole('button', { name: '关闭', exact: true }).click()
    await discountPanel.getByRole('button', { name: '刷新单据', exact: true }).click()
    await discountPanel.getByRole('button', { name: '过账', exact: true }).click()
    await confirm(page, '过账')
    await expect(discountPanel.getByText('已过账', { exact: true }).first()).toBeVisible()
    await page.setViewportSize({ width: 390, height: 844 })
    await settled(page, discountPanel)
    await expect
      .poll(() =>
        discountPanel
          .locator('.el-drawer__title')
          .evaluate((element) => element.scrollWidth <= element.clientWidth + 1),
      )
      .toBe(true)
    await expect
      .poll(() =>
        discountPanel
          .locator('.price-table-scroll')
          .first()
          .evaluate((element) => element.scrollWidth > element.clientWidth),
      )
      .toBe(true)
    await page.screenshot({ path: info.outputPath('销售折让移动端重点列.png') })
    await page.setViewportSize({ width: 1440, height: 1000 })
    await discountPanel.getByRole('button', { name: '关闭', exact: true }).click()
    await page.goto(
      `/reports/sales-summary?detail=true&partnerId=${customer}&warehouseId=${warehouse}&dateFrom=${day}&dateTo=${day}`,
    )
    const reportUrl = page.url()
    await page
      .getByRole('row')
      .filter({ hasText: discount.documentNo })
      .getByRole('button', { name: '查看原单', exact: true })
      .click()
    await expect(
      page.getByRole('dialog', { name: '销售调价 · ' + discount.documentNo, exact: true }),
    ).toBeVisible()
    expect(page.url()).toBe(reportUrl)
    await page.screenshot({ path: info.outputPath('销售报表右侧穿透.png'), fullPage: true })
    await page
      .getByRole('dialog', { name: '销售调价 · ' + discount.documentNo, exact: true })
      .getByRole('button', { name: '关闭', exact: true })
      .click()
    expect(page.url()).toBe(reportUrl)
    await changeUser(page, 'admin')
    const finalShipment = await api<Document>(page, company, '/shipments/' + shipment.id)
    expect(finalShipment.lines[0]!.costAmount).toBe('60.00')
    expect(finalShipment.lines[0]!.signedCostAmount).toBe('60.00')
    expect(errors).toEqual([])
    await writeFile(
      info.outputPath('业务验收结果.json'),
      JSON.stringify(
        {
          company,
          shipmentId: shipment.id,
          adjustmentId: adjustment.id,
          discountId: discount.id,
          invoiceId: blue.id,
          twoSources: true,
          sameNetworkKey: keys[0] === keys[1],
          noCostActors: true,
          finalCost: finalShipment.lines[0]!.costAmount,
          pageErrors: errors,
        },
        null,
        2,
      ),
      'utf-8',
    )
  } finally {
    await context.close()
  }
})
