import { expect, test } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { env } from 'node:process'

const supplier = '采购验收供应商'
const warehouse = '采购验收仓'
const sku = 'E2E-M5-SKU 采购验收商品'
const bankA = '采购验收银行甲'
const bankB = '采购验收银行乙'

async function selectId(page: Page, id: string, label: string | RegExp): Promise<void> {
  const combobox = page.locator('#' + id)
  await expect(combobox).toBeEnabled()
  await page.locator('.el-select').filter({ has: combobox }).click()
  await expect(combobox).toHaveAttribute('aria-expanded', 'true')
  await expect(combobox).toHaveAttribute('aria-controls', /.+/)
  const listboxId = await combobox.getAttribute('aria-controls')
  if (!listboxId) throw new Error('下拉框缺少选项列表关联：' + id)
  await page
    .locator('[role="listbox"][id=' + JSON.stringify(listboxId) + ']')
    .getByRole('option', { name: label, exact: typeof label === 'string' })
    .click()
}
async function selectName(page: Page, name: string, label: string | RegExp): Promise<void> {
  const combobox = page.getByRole('combobox', { name, exact: true })
  await expect(combobox).toBeEnabled()
  await page.locator('.el-select').filter({ has: combobox }).click()
  await expect(combobox).toHaveAttribute('aria-expanded', 'true')
  await expect(combobox).toHaveAttribute('aria-controls', /.+/)
  const listboxId = await combobox.getAttribute('aria-controls')
  if (!listboxId) throw new Error('下拉框缺少选项列表关联：' + name)
  await page
    .locator('[role="listbox"][id=' + JSON.stringify(listboxId) + ']')
    .getByRole('option', { name: label, exact: typeof label === 'string' })
    .click()
}
async function login(page: Page, username: string): Promise<void> {
  if (!env.ERP_E2E_PASSWORD || !/^E2E-[a-f0-9]{12}$/.test(env.ERP_E2E_TENANT_CODE ?? ''))
    throw new Error('请使用项目脚本准备随机采购验收企业和临时口令')
  await page.goto('/login')
  await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE!)
  await page.getByLabel('用户名').fill(username)
  await page.locator('#password').fill(env.ERP_E2E_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
}
async function command(
  page: Page,
  label: string,
  reason?: string,
  footerName = label.startsWith('确认') ? label : '确认' + label,
): Promise<void> {
  await page.getByRole('button', { name: label, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: label, exact: true })
  if (reason) await dialog.locator('#purchase-command-reason').fill(reason)
  await dialog.getByRole('button', { name: footerName, exact: true }).click()
  await expect(dialog).toBeHidden()
}
async function status(page: Page, text: string): Promise<void> {
  await expect(page.locator('.sales-detail-header .el-tag').first()).toHaveText(text)
}
async function save(page: Page, kind: string, singular: string): Promise<string> {
  await (kind === 'purchase-orders' ? page : page.getByRole('dialog'))
    .getByRole('button', { name: '保存' + singular, exact: true })
    .click()
  await expect(page).toHaveURL(new RegExp('/' + kind + '/[0-9]+$'))
  await expect(page.locator('.sales-detail-header .el-tag').first()).toBeVisible()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(/-[0-9]{6}-[0-9]{6}$/)
  return new URL(page.url()).pathname
}
async function reviewAndPost(page: Page, reviewer: Page, path: string): Promise<void> {
  await reviewer.goto(path)
  await command(reviewer, '审批通过')
  await page.reload()
  await command(page, '过账')
  await status(page, '已过账')
}
async function apWorkbench(page: Page): Promise<{ debits: Locator; credits: Locator }> {
  await page.goto('/settlements')
  await selectName(page, '往来侧', '供应商应付')
  await selectId(page, 'settlement-customer', supplier)
  return {
    debits: page
      .locator('.settlement-columns > section')
      .filter({ has: page.getByRole('heading', { name: '可用预付及退货额度', exact: true }) }),
    credits: page
      .locator('.settlement-columns > section')
      .filter({ has: page.getByRole('heading', { name: '待付应付', exact: true }) }),
  }
}
async function allocate(page: Page, amount: string, creditAmount: string): Promise<void> {
  const columns = await apWorkbench(page)
  await columns.debits.locator('.el-radio').first().click()
  await columns.credits
    .locator('.el-radio')
    .filter({ hasText: new RegExp('未核销 ' + creditAmount.replace('.', '\\.') + '$') })
    .click()
  await page.locator('#settlement-amount').fill(amount)
  await page.getByRole('button', { name: '确认核销', exact: true }).click()
  await expect(page.getByText('核销已完成', { exact: true })).toBeVisible()
}

test('资金往来与库存期初、请购采购收货、付款退货退款和转账闭环', async ({
  page,
  browser,
}, testInfo) => {
  test.setTimeout(240_000)
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  const context = await browser.newContext({ baseURL: 'http://127.0.0.1:15173', locale: 'zh-CN' })
  const reviewer = await context.newPage()
  reviewer.on('pageerror', (error) => errors.push(error.message))
  try {
    await login(page, 'admin')
    await login(reviewer, 'reviewer')

    await page.goto('/opening-entries')
    await page.getByRole('button', { name: '新建期初余额', exact: true }).click()
    await selectId(page, 'purchase-account', bankA)
    await page.locator('#purchase-amount').fill('1000')
    const cashPath = await save(page, 'opening-entries', '期初余额')
    await reviewAndPost(page, reviewer, cashPath)

    await page.goto('/opening-entries')
    await page.getByRole('button', { name: '新建期初余额', exact: true }).click()
    await selectName(page, '期初类型', '供应商往来期初')
    await selectId(page, 'purchase-partner', supplier)
    await page.locator('#purchase-amount').fill('50')
    const payableOpeningPath = await save(page, 'opening-entries', '期初余额')
    await reviewAndPost(page, reviewer, payableOpeningPath)

    await page.goto('/stock-adjustments')
    await page.getByRole('button', { name: '新建库存调整单', exact: true }).click()
    await selectId(page, 'purchase-warehouse', warehouse)
    await page.locator('#purchase-reason').fill('采购验收库存期初')
    await selectName(page, '采购商品1', sku)
    await page.getByRole('textbox', { name: '采购数量1', exact: true }).fill('20')
    await page.getByRole('textbox', { name: '期初总成本1', exact: true }).fill('240')
    const stockPath = await save(page, 'stock-adjustments', '库存调整单')
    await command(page, '提交')
    await expect(page.getByRole('button', { name: '审批通过', exact: true })).toHaveCount(0)
    await reviewAndPost(page, reviewer, stockPath)

    await page.goto('/purchase-requisitions')
    await page.getByRole('button', { name: '新建请购单', exact: true }).click()
    await selectName(page, '采购商品1', sku)
    await page.getByRole('textbox', { name: '采购数量1', exact: true }).fill('10')
    const requisitionPath = await save(page, 'purchase-requisitions', '请购单')
    const requisitionNo = await page.getByRole('heading', { level: 1 }).innerText()
    await command(page, '提交')
    await reviewer.goto(requisitionPath)
    await command(reviewer, '审批通过')

    await page.goto('/purchase-orders')
    await page.getByRole('button', { name: '新建采购订单', exact: true }).click()
    await selectId(page, 'purchase-partner', supplier)
    await selectName(page, '采购来源1', new RegExp(requisitionNo + '.*采购验收商品'))
    await selectName(page, '采购仓库1', warehouse)
    await page.getByRole('textbox', { name: '采购数量1', exact: true }).fill('10')
    await page.getByRole('textbox', { name: '采购单价1', exact: true }).fill('14')
    await page.getByRole('textbox', { name: '采购税率1', exact: true }).fill('0')
    await expect(page.locator('.sales-totals')).toContainText('140.00')
    const orderPath = await save(page, 'purchase-orders', '采购订单')
    const orderNo = await page.getByRole('heading', { level: 1 }).innerText()
    await command(page, '提交')
    await expect(page.getByRole('button', { name: '审批通过', exact: true })).toHaveCount(0)
    await reviewer.goto(orderPath)
    await command(reviewer, '审批通过')

    await page.goto('/goods-receipts')
    await page.getByRole('button', { name: '新建收货单', exact: true }).click()
    await selectId(page, 'purchase-partner', supplier)
    await selectId(page, 'purchase-warehouse', warehouse)
    await selectName(page, '采购来源1', new RegExp(orderNo + '.*采购验收商品'))
    await page.getByRole('textbox', { name: '采购数量1', exact: true }).fill('10')
    const receiptPath = await save(page, 'goods-receipts', '收货单')
    const receiptNo = await page.getByRole('heading', { level: 1 }).innerText()
    await command(page, '提交')
    await command(page, '过账')
    await status(page, '已过账')
    await expect(page.locator('.sales-detail-header')).toContainText('140.00')

    await page.goto('/payments')
    await page.getByRole('button', { name: '新建付款单', exact: true }).click()
    await selectId(page, 'purchase-partner', supplier)
    await selectId(page, 'purchase-account', bankA)
    await selectId(page, 'purchase-related', orderNo)
    await page.locator('#purchase-amount').fill('190')
    const paymentPath = await save(page, 'payments', '付款单')
    await command(page, '提交')
    await expect(page.getByRole('button', { name: '确认付款', exact: true })).toHaveCount(0)
    await reviewer.goto(paymentPath)
    await command(reviewer, '确认付款')
    await status(reviewer, '已确认')
    await allocate(page, '50', '50.00')
    await allocate(page, '140', '140.00')

    await page.goto('/purchase-returns')
    await page.getByRole('button', { name: '新建采购退货单', exact: true }).click()
    await selectId(page, 'purchase-partner', supplier)
    await selectId(page, 'purchase-warehouse', warehouse)
    await page.locator('#purchase-reason').fill('退回五件采购商品')
    await selectName(page, '采购来源1', new RegExp(receiptNo + '.*采购验收商品'))
    await page.getByRole('textbox', { name: '采购数量1', exact: true }).fill('5')
    const returnPath = await save(page, 'purchase-returns', '采购退货单')
    await command(page, '提交')
    await command(page, '过账')
    await expect(page.locator('.sales-detail-header')).toContainText('70.00')

    await page.goto('/receipts')
    await page.getByRole('button', { name: '新建收款单', exact: true }).click()
    await selectName(page, '收款类型', '供应商退款')
    await selectId(page, 'doc-customer', supplier)
    await selectId(page, 'doc-account', bankA)
    await page.locator('#doc-amount').fill('70')
    await page.getByRole('dialog').getByRole('button', { name: '保存单据', exact: true }).click()
    await expect(page).toHaveURL(/\/receipts\/[0-9]+$/)
    const supplierRefundPath = new URL(page.url()).pathname
    await command(page, '提交')
    await reviewer.goto(supplierRefundPath)
    await command(reviewer, '确认到账', undefined, '确认确认到账')
    await status(reviewer, '已确认')
    await allocate(page, '70', '70.00')

    await page.goto('/account-transfers')
    await page.getByRole('button', { name: '新建转账单', exact: true }).click()
    await selectId(page, 'transfer-from', bankA)
    await selectId(page, 'transfer-to', bankB)
    await page.locator('#purchase-amount').fill('100')
    await page.locator('#transfer-fee').fill('2')
    const transferPath = await save(page, 'account-transfers', '转账单')
    await command(page, '提交')
    await reviewer.goto(transferPath)
    await command(reviewer, '确认转账')
    await status(reviewer, '已确认')

    await page.goto('/cash-accounts')
    await expect(page.getByRole('row').filter({ hasText: bankA })).toContainText('778.00')
    await expect(page.getByRole('row').filter({ hasText: bankB })).toContainText('100.00')
    await page.goto('/inventory-balances')
    await expect(
      page.getByRole('row').filter({ hasText: '采购验收商品' }).getByRole('cell').nth(3),
    ).toHaveText('25')
    const columns = await apWorkbench(page)
    await expect(columns.debits.getByText('暂无可核销额度', { exact: true })).toBeVisible()
    await expect(columns.credits.getByText('暂无待付应付', { exact: true })).toBeVisible()

    const current: { data: { companies: { id: string; code: string }[] } } = await (
      await page.request.get('/api/v1/auth/me')
    ).json()
    const company = current.data.companies.find((item) => item.code === 'DEMO-A')
    if (!company) throw new Error('未找到采购验收公司')
    const headers = { 'X-Company-Id': company.id }
    const receipt: { data: { costAmount: string } } = await (
      await page.request.get('/api/v1' + receiptPath, { headers })
    ).json()
    const returned: { data: { costAmount: string } } = await (
      await page.request.get('/api/v1' + returnPath, { headers })
    ).json()
    expect(receipt.data.costAmount).toBe('140.00')
    expect(returned.data.costAmount).toBe('70.00')
    await page.goto(orderPath)
    await expect(page.locator('.sales-detail-header .el-tag')).toHaveCSS('transform', 'none')
    await page.screenshot({
      path: testInfo.outputPath('procurement-completed.png'),
      fullPage: true,
    })
    expect(errors).toEqual([])
  } finally {
    await context.close()
  }
})
