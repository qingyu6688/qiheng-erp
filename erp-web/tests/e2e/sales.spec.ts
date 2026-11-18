import { expect, test } from '@playwright/test'
import type { Page, Locator } from '@playwright/test'
import { env } from 'node:process'

const customer = '验收销售客户'
const warehouse = '验收销售仓'
const account = '验收银行'

async function selectId(page: Page, id: string, option: string | RegExp): Promise<void> {
  const combobox = page.locator('#' + id)
  await expect(combobox).toBeEnabled()
  await page.locator('.el-select').filter({ has: combobox }).click()
  await expect(combobox).toHaveAttribute('aria-expanded', 'true')
  await page.getByRole('option', { name: option, exact: typeof option === 'string' }).click()
}

async function selectName(page: Page, name: string, option: string | RegExp): Promise<void> {
  const combobox = page.getByRole('combobox', { name, exact: true })
  await expect(combobox).toBeEnabled()
  await page.locator('.el-select').filter({ has: combobox }).click()
  await expect(combobox).toHaveAttribute('aria-expanded', 'true')
  await page.getByRole('option', { name: option, exact: typeof option === 'string' }).click()
}

async function login(page: Page, username: string): Promise<void> {
  if (!env.ERP_E2E_PASSWORD || !/^E2E-[a-f0-9]{12}$/.test(env.ERP_E2E_TENANT_CODE ?? '')) {
    throw new Error('销售浏览器验收须使用 project.ps1 e2e 的随机测试企业和临时口令')
  }
  await page.goto('/login')
  await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE!)
  await page.getByLabel('用户名').fill(username)
  await page.locator('#password').fill(env.ERP_E2E_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
}

async function command(page: Page, action: string): Promise<void> {
  await page.getByRole('button', { name: action, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: action, exact: true })
  await dialog.getByRole('button', { name: '确认' + action, exact: true }).click()
  await expect(dialog).toBeHidden()
}

async function documentSaved(
  page: Page,
  kind: string,
  expectedNo: string | RegExp = /-[0-9]{6}-[0-9]{6}$/,
): Promise<string> {
  await page.getByRole('dialog').getByRole('button', { name: '保存单据', exact: true }).click()
  await expect(page).toHaveURL(new RegExp('/' + kind + '/[0-9]+$'))
  await expect(page.locator('.sales-detail-header .status-tag, [data-order-status]').first()).toBeVisible()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(expectedNo)
  return new URL(page.url()).pathname
}

async function status(page: Page, text: string): Promise<void> {
  await expect(page.locator('.sales-detail-header .status-tag, [data-order-status]').first()).toHaveText(text)
}

test('销售订单、发货签收、收款核销、开票和退货退款形成完整闭环', async ({
  page,
  browser,
}, testInfo) => {
  test.setTimeout(180_000)
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  const reviewerContext = await browser.newContext({ baseURL: 'http://127.0.0.1:15173' })
  const reviewer = await reviewerContext.newPage()
  reviewer.on('pageerror', (error) => errors.push(error.message))
  try {
    await login(page, 'admin')
    await login(reviewer, 'reviewer')
    await page.goto('/sales-orders')
    await page.getByRole('button', { name: '新建销售订单', exact: true }).click()
    await selectId(page, 'order-customer', customer)
    await selectName(page, '商品1', 'E2E-SKU 验收商品')
    await selectName(page, '仓库1', warehouse)
    await page.getByRole('textbox', { name: '数量1', exact: true }).fill('4')
    await page.getByRole('textbox', { name: '单价1', exact: true }).fill('100')
    await expect(page.locator('.order-editor-footer')).toContainText('400.00')
    await page.screenshot({ path: testInfo.outputPath('sales-order-form.png'), fullPage: true })
    await page.getByRole('button', { name: '保存订单', exact: true }).click()
    await expect(page).toHaveURL(/\/sales-orders\/[0-9]+$/)
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(/^SO-.*-[0-9]{6}-[0-9]{6}$/)
    const orderPath = new URL(page.url()).pathname
    const orderNo = await page.getByRole('heading', { level: 1 }).innerText()
    await command(page, '提交')
    await status(page, '待审核')
    await expect(page.getByRole('button', { name: '审批通过', exact: true })).toHaveCount(0)
    await reviewer.goto(orderPath)
    await command(reviewer, '审批通过')
    await status(reviewer, '已批准')

    await page.goto('/shipments')
    await page.getByRole('button', { name: '新建发货单', exact: true }).click()
    await selectId(page, 'doc-customer', customer)
    await selectId(page, 'doc-warehouse', warehouse)
    await selectName(page, '来源行1', new RegExp(orderNo + '.*验收商品'))
    await page.getByRole('textbox', { name: '数量1', exact: true }).fill('4')
    const shipmentPath = await documentSaved(page, 'shipments')
    const shipmentNo = await page.getByRole('heading', { level: 1 }).innerText()
    await command(page, '提交')
    await command(page, '过账')
    await status(page, '已出库待签收')
    await expect(
      page
        .locator('.sales-detail-header dt')
        .filter({ hasText: /^金额/ })
        .locator('xpath=following-sibling::dd[1]'),
    ).toHaveText('0.00')
    await page.getByRole('button', { name: '登记签收', exact: true }).click()
    const signDialog = page.getByRole('dialog', { name: '登记签收', exact: true })
    await expect(signDialog.getByRole('textbox', { name: '签收数量', exact: true })).toHaveValue(
      /^4(?:\.0+)?$/,
    )
    await signDialog.getByLabel('上传回单').setInputFiles({
      name: '签收回单.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('%PDF-1.7\n签收回单验收\n%%EOF', 'utf8'),
    })
    await signDialog.getByRole('button', { name: '上传并选中', exact: true }).click()
    await expect(
      signDialog.getByRole('checkbox', { name: '签收回单.pdf', exact: true }),
    ).toBeChecked()
    await page.screenshot({
      path: testInfo.outputPath('shipment-sign-receipt.png'),
      fullPage: true,
    })
    const signRequest = page.waitForRequest(
      (request) =>
        request.method() === 'POST' && request.url().endsWith('/api/v1' + shipmentPath + '/sign'),
    )
    await signDialog.getByRole('button', { name: '确认登记签收', exact: true }).click()
    expect((await signRequest).postDataJSON().attachmentIds).toEqual([
      expect.stringMatching(/^[1-9][0-9]*$/),
    ])
    await expect(signDialog).toBeHidden()
    await status(page, '已签收')
    await expect(
      page
        .locator('.sales-detail-header dt')
        .filter({ hasText: /^金额/ })
        .locator('xpath=following-sibling::dd[1]'),
    ).toHaveText('400.00')
    await expect(page.locator('.sales-detail-header')).toContainText('46.02')

    await page.goto('/receipts')
    await page.getByRole('button', { name: '新建收款单', exact: true }).click()
    await selectId(page, 'doc-customer', customer)
    await selectId(page, 'doc-account', account)
    await selectId(page, 'doc-order', orderNo)
    await page.locator('#doc-amount').fill('400')
    const receiptPath = await documentSaved(page, 'receipts')
    const receiptNo = await page.getByRole('heading', { level: 1 }).innerText()
    await command(page, '提交')
    await status(page, '待确认')
    await expect(page.getByRole('button', { name: '确认到账', exact: true })).toHaveCount(0)
    await reviewer.goto(receiptPath)
    await command(reviewer, '确认到账')
    await status(reviewer, '已确认')

    await page.goto('/settlements')
    await selectId(page, 'settlement-customer', customer)
    const debitSection: Locator = page
      .locator('.settlement-columns > section')
      .filter({ has: page.getByRole('heading', { name: '待收应收', exact: true }) })
    const creditSection = page
      .locator('.settlement-columns > section')
      .filter({ has: page.getByRole('heading', { name: '可用预收 / 退货信用额', exact: true }) })
    await debitSection.locator('.el-radio').first().click()
    await creditSection.locator('.el-radio').first().click()
    await expect(debitSection.getByRole('radio').first()).toBeChecked()
    await expect(creditSection.getByRole('radio').first()).toBeChecked()
    await page.locator('#settlement-amount').fill('400')
    await page.getByRole('button', { name: '确认核销', exact: true }).click()
    await expect(page.getByText('核销已完成', { exact: true })).toBeVisible()
    await expect(debitSection.getByText('暂无待收应收', { exact: true })).toBeVisible()
    await expect(creditSection.getByText('暂无可核销额度', { exact: true })).toBeVisible()

    await page.goto('/invoice-registrations')
    await page.getByRole('button', { name: '新建开票登记', exact: true }).click()
    await selectId(page, 'doc-customer', customer)
    const invoiceNo = 'E2E-INVOICE-' + Date.now()
    await page.locator('#doc-invoice').fill(invoiceNo)
    await page.locator('#doc-amount').fill('400')
    await page.locator('#doc-tax').fill('46.02')
    await selectName(page, '来源行1', new RegExp(shipmentNo))
    await page.getByRole('textbox', { name: '开票金额1', exact: true }).fill('400')
    await documentSaved(page, 'invoice-registrations', invoiceNo)
    await status(page, '已登记')

    await page.goto('/sales-returns')
    await page.getByRole('button', { name: '新建退货单', exact: true }).click()
    await selectId(page, 'doc-customer', customer)
    await selectId(page, 'doc-warehouse', warehouse)
    await page.locator('#doc-reason').fill('客户退回一件合格商品')
    await selectName(page, '来源行1', new RegExp(orderNo + '.*验收商品'))
    await page.getByRole('textbox', { name: '数量1', exact: true }).fill('1')
    await selectName(page, '质检1', '合格')
    await documentSaved(page, 'sales-returns')
    await command(page, '提交')
    await command(page, '过账')
    await expect(page.locator('.sales-detail-header')).toContainText('100.00')

    await page.goto('/refund-requests')
    await page.getByRole('button', { name: '新建退款申请', exact: true }).click()
    await selectId(page, 'doc-customer', customer)
    await selectId(page, 'doc-account', account)
    await selectId(page, 'doc-receipt', receiptNo)
    await page.locator('#doc-amount').fill('100')
    await page.locator('#doc-reason').fill('退货信用额原路退款')
    const refundPath = await documentSaved(page, 'refund-requests')
    await command(page, '提交')
    await reviewer.goto(refundPath)
    await command(reviewer, '审批通过')
    await status(reviewer, '待退款')
    await page.reload()
    await page.getByRole('button', { name: '登记已退款', exact: true }).click()
    const refundDialog = page.getByRole('dialog', { name: '登记已退款', exact: true })
    await refundDialog.locator('#command-bank').fill('E2E-REFUND-' + Date.now())
    await refundDialog.getByRole('button', { name: '确认登记已退款', exact: true }).click()
    await expect(refundDialog).toBeHidden()
    await status(page, '已退款')

    await page.goto('/cash-accounts')
    await expect(
      page.getByRole('row').filter({ has: page.getByRole('cell', { name: account, exact: true }) }),
    ).toContainText('300.00')
    await page.goto('/inventory-balances')
    await expect(
      page
        .getByRole('row')
        .filter({ has: page.getByRole('cell', { name: '验收商品', exact: true }) })
        .getByRole('cell')
        .nth(3),
    ).toHaveText('17')
    await page.goto('/settlements')
    await selectId(page, 'settlement-customer', customer)
    await expect(page.getByText('暂无待收应收', { exact: true })).toBeVisible()
    await expect(page.getByText('暂无可核销额度', { exact: true })).toBeVisible()
    await page.goto(shipmentPath)
    await status(page, '已签收')
    await page.goto(orderPath)
    await expect(page.locator('[data-order-metric="allocated-cash"]')).toHaveText('400.00')
    await expect(page.locator('[data-order-metric="open-amount"]')).toHaveText('0.00')
    await expect(page.getByRole('region', { name: '订单摘要' }).getByText('已确认金额结清', { exact: true })).toBeVisible()
    const settlement = page.getByRole('list', { name: '订单进度' }).getByRole('listitem').filter({ hasText: '结清' })
    await expect(settlement).toHaveAttribute('data-state', 'done')
    await page.screenshot({
      path: testInfo.outputPath('sales-order-completed.png'),
      fullPage: true,
    })
    expect(errors).toEqual([])
  } finally {
    await reviewerContext.close()
  }
})
