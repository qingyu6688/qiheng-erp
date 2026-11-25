import { expect, test } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { env } from 'node:process'

interface Doc {
  id: string
  documentNo: string
  status: string
  version: number
  costAmount?: string
  creditAmount?: string
  amount?: string
  wfInstanceId?: string
  creditSources?: { sourceNo: string; openAmount: string }[]
}
interface Master {
  id: string
  code: string
  name: string
  units?: { unitId: string; isBase: boolean }[]
}
async function login(page: Page, user: string): Promise<void> {
  if (!env.ERP_E2E_PASSWORD || !/^E2E-[a-f0-9]{12}$/.test(env.ERP_E2E_TENANT_CODE ?? ''))
    throw new Error('无原单退货浏览器验收须使用随机隔离企业及临时口令')
  await page.goto('/login')
  await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE!)
  await page.getByLabel('用户名').fill(user)
  await page.locator('#password').fill(env.ERP_E2E_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '退出登录', exact: true })).toBeVisible()
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
async function choose(page: Page, combo: Locator, name: string): Promise<void> {
  await expect(combo).toBeEnabled()
  await combo
    .locator(
      "xpath=ancestor::div[contains(concat(' ',normalize-space(@class),' '),' el-select ')][1]",
    )
    .click()
  const controls = await combo.getAttribute('aria-controls')
  if (!controls) throw new Error('下拉框未关联候选列表')
  await page
    .locator('#' + controls)
    .getByRole('option', { name, exact: true })
    .click()
}
async function open(
  page: Page,
  kind: 'sales-returns' | 'refund-requests',
  no: string,
): Promise<Locator> {
  await page.goto('/' + kind + '?keyword=' + encodeURIComponent(no))
  await page.getByRole('button', { name: no, exact: true }).click()
  const drawer = page.getByRole('dialog', {
    name: kind === 'sales-returns' ? '无原单销售退货' : '退货信用额退款',
    exact: true,
  })
  await expect(drawer.locator('.sourceless-panel h2').first()).toHaveText(no)
  return drawer
}
async function submit(drawer: Locator): Promise<void> {
  await drawer.getByRole('button', { name: '提交', exact: true }).click()
  await drawer.getByRole('button', { name: '确认提交', exact: true }).click()
}
async function fits(page: Page, drawer: Locator): Promise<void> {
  await expect
    .poll(async () => {
      const box = await drawer.boundingBox()
      return box !== null && box.x >= -1 && box.width <= page.viewportSize()!.width + 1
    })
    .toBe(true)
  await expect
    .poll(() => drawer.evaluate((element) => element.scrollWidth <= element.clientWidth + 1))
    .toBe(true)
}

test('无原单退货和信用额退款在右侧完成双人审批、原键恢复及实际付款登记', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(240_000)
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
    const company = profile.companies.find((row) => row.code === 'DEMO-A')!.id
    const list = async (path: string): Promise<Master[]> => {
      const value = await api<Master[] | { items: Master[] }>(
        page,
        company,
        '/' + path + '?limit=200',
      )
      return Array.isArray(value) ? value : value.items
    }
    const customer = (await list('partners')).find((row) => row.code === 'E2E-CUSTOMER')!
    const sku = (await list('skus')).find((row) => row.code === 'E2E-M5-SKU')!
    const skuDetail = await api<Master>(page, company, '/skus/' + sku.id)
    const warehouse = await api<Master>(page, company, '/warehouses', {
      code: 'SR-' + randomUUID().slice(0, 12),
      name: '无原单浏览器验收仓',
    })
    const bank = await api<Master>(page, company, '/cash-accounts', {
      code: 'RF-' + randomUUID().slice(0, 12),
      name: '信用额退款验收银行',
      accountType: 'bank',
      currency: 'CNY',
    })
    const day = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
    const opening = await api<Doc>(page, company, '/opening-entries', {
      entryType: 'cash',
      cashAccountId: bank.id,
      direction: 'D',
      amount: '1000',
      businessDate: day,
      remark: '隔离验收资金期初',
    })
    await api(reviewer, company, `/opening-entries/${opening.id}/approve`, {
      version: opening.version,
    })
    await api(page, company, `/opening-entries/${opening.id}/post`, {
      version: opening.version + 1,
    })

    const routeBefore = '/sales-returns?warehouseId=' + warehouse.id
    await page.goto(routeBefore)
    await page.getByRole('button', { name: '无原单退货', exact: true }).click()
    let drawer = page.getByRole('dialog', { name: '无原单销售退货', exact: true })
    await choose(
      page,
      drawer.getByRole('combobox', { name: '退货客户', exact: true }),
      customer.name,
    )
    await choose(
      page,
      drawer.getByRole('combobox', { name: '退回仓库', exact: true }),
      warehouse.name,
    )
    await choose(
      page,
      drawer.getByRole('combobox', { name: '退货商品', exact: true }),
      sku.code + ' ' + sku.name,
    )
    await expect(drawer.getByRole('combobox', { name: '退货单位', exact: true })).toBeEnabled()
    await drawer.getByLabel('退货数量', { exact: true }).fill('2')
    await drawer.getByLabel('含税退货单价', { exact: true }).fill('11.30')
    await drawer.getByLabel('退货税率', { exact: true }).fill('0.13')
    await choose(page, drawer.getByRole('combobox', { name: '退货质检', exact: true }), '合格')
    await drawer.getByLabel('补填基本单位成本', { exact: true }).fill('5')
    await drawer.getByLabel('补填成本依据', { exact: true }).fill('旧系统单位成本台账')
    await drawer.getByLabel('历史依据', { exact: true }).fill('旧系统销售单001')
    await drawer.getByLabel('退货原因', { exact: true }).fill('浏览器验收历史退货')
    const createKeys: string[] = []
    let created!: Doc
    await page.route('**/api/v1/sales-returns', async (route) => {
      if (route.request().method() !== 'POST') return route.continue()
      createKeys.push(route.request().headers()['idempotency-key']!)
      const response = await route.fetch()
      expect(response.ok()).toBeTruthy()
      created = (await response.json()).data as Doc
      await route.abort('failed')
    })
    await drawer.getByRole('button', { name: '保存草稿', exact: true }).click()
    await expect(drawer.getByText('原请求结果尚未确认', { exact: true })).toBeVisible()
    await page.unroute('**/api/v1/sales-returns')
    await drawer.getByRole('button', { name: '确认原请求结果', exact: true }).click()
    await expect(drawer.locator('.sourceless-panel h2').first()).toHaveText(created.documentNo)
    await expect(page).toHaveURL(new RegExp('/sales-returns\\?warehouseId=' + warehouse.id + '$'))
    const filterPage = page.url()
    await drawer.getByRole('button', { name: '更正草稿', exact: true }).click()
    await drawer.getByLabel('退货数量', { exact: true }).fill('3')
    const updateKeys: string[] = []
    await page.route(`**/api/v1/sales-returns/${created.id}`, async (route) => {
      if (route.request().method() !== 'PUT') return route.continue()
      updateKeys.push(route.request().headers()['idempotency-key']!)
      const response = await route.fetch()
      expect(response.ok()).toBeTruthy()
      await route.abort('failed')
    })
    await drawer.getByRole('button', { name: '保存草稿', exact: true }).click()
    await expect(drawer.getByText('原请求结果尚未确认', { exact: true })).toBeVisible()
    await page.unroute(`**/api/v1/sales-returns/${created.id}`)
    await drawer.getByRole('button', { name: '确认原请求结果', exact: true }).click()
    await expect(drawer.locator('.sourceless-panel h2').first()).toHaveText(created.documentNo)
    await drawer.getByRole('button', { name: '预览当前成本', exact: true }).click()
    await expect(drawer.locator('.cost-preview')).toContainText('15.00')
    await expect(drawer.locator('.cost-preview')).toContainText('人工复核成本')
    await page.screenshot({ path: info.outputPath('无原单退货成本预览.png'), fullPage: true })
    await submit(drawer)
    await expect(drawer.locator('.document-facts')).toContainText('待审核')

    const reviewDrawer = await open(reviewer, 'sales-returns', created.documentNo)
    await expect(reviewDrawer.locator('.financial-summary')).toContainText('15.00')
    await reviewDrawer.getByLabel('审批处理意见', { exact: true }).fill('核对成本和历史凭据')
    await reviewDrawer.getByRole('button', { name: /^同意 ·/ }).click()
    await expect(reviewDrawer.locator('.document-facts')).toContainText('已批准')
    await drawer.getByRole('button', { name: '刷新单据', exact: true }).click()
    await drawer.getByRole('button', { name: '过账', exact: true }).click()
    await drawer.getByRole('button', { name: '确认过账', exact: true }).click()
    await expect(drawer.locator('.document-facts')).toContainText('已过账')
    const posted = await api<Doc>(page, company, `/sales-returns/${created.id}`)
    expect(posted.costAmount).toBe('15.00')
    expect(posted.creditAmount).toBe('33.90')
    await page.screenshot({ path: info.outputPath('无原单退货过账及成本.png'), fullPage: true })
    await drawer.getByRole('button', { name: '关闭', exact: true }).click()
    await expect(page).toHaveURL(filterPage)
    await expect(page.getByRole('button', { name: created.documentNo, exact: true })).toBeVisible()

    await page.goto('/refund-requests')
    await page.getByRole('button', { name: '退货信用额退款', exact: true }).click()
    drawer = page.getByRole('dialog', { name: '退货信用额退款', exact: true })
    await choose(
      page,
      drawer.getByRole('combobox', { name: '退款客户', exact: true }),
      customer.name,
    )
    await choose(page, drawer.getByRole('combobox', { name: '退款账户', exact: true }), bank.name)
    await drawer.getByLabel('退款金额', { exact: true }).fill('10.00')
    await drawer.getByLabel('退款原因', { exact: true }).fill('隔离验收历史退货退款')
    const checkbox = drawer.getByRole('checkbox', {
      name: '选择信用额 ' + created.documentNo,
      exact: true,
    })
    await checkbox
      .locator(
        "xpath=ancestor::*[contains(concat(' ',normalize-space(@class),' '),' el-checkbox ')][1]",
      )
      .click()
    await expect(checkbox).toBeChecked()
    await expect(drawer.locator('.credit-summary')).toContainText('33.90')
    const refundResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/v1/refund-requests') &&
        response.request().method() === 'POST',
    )
    await drawer.getByRole('button', { name: '保存草稿', exact: true }).click()
    const refund = (await (await refundResponse).json()).data as Doc
    await expect(drawer.locator('.sourceless-panel h2').first()).toHaveText(refund.documentNo)
    await drawer.getByRole('button', { name: '更正草稿', exact: true }).click()
    await drawer.getByLabel('退款原因', { exact: true }).fill('核对后登记历史退货退款')
    const refundUpdateKeys: string[] = []
    await page.route(`**/api/v1/refund-requests/${refund.id}`, async (route) => {
      if (route.request().method() !== 'PUT') return route.continue()
      refundUpdateKeys.push(route.request().headers()['idempotency-key']!)
      const response = await route.fetch()
      expect(response.ok()).toBeTruthy()
      await route.abort('failed')
    })
    await drawer.getByRole('button', { name: '保存草稿', exact: true }).click()
    await expect(drawer.getByText('原请求结果尚未确认', { exact: true })).toBeVisible()
    await page.unroute(`**/api/v1/refund-requests/${refund.id}`)
    await drawer.getByRole('button', { name: '确认原请求结果', exact: true }).click()
    await expect(drawer.getByRole('button', { name: '更正草稿', exact: true })).toBeVisible()
    await drawer.getByRole('button', { name: created.documentNo, exact: true }).click()
    const source = page.getByRole('dialog', { name: '无原单销售退货', exact: true })
    await expect(source.locator('.sourceless-panel h2').first()).toHaveText(created.documentNo)
    await expect(source.getByRole('button', { name: '过账', exact: true })).toHaveCount(0)
    await source.getByRole('button', { name: '关闭', exact: true }).click()
    await submit(drawer)
    await expect(drawer.locator('.document-facts')).toContainText('待审核')
    const pendingRefund = await api<Doc>(page, company, `/refund-requests/${refund.id}`)
    expect(pendingRefund.wfInstanceId).toMatch(/^[1-9]\d*$/)
    const refundReview = await open(reviewer, 'refund-requests', refund.documentNo)
    await refundReview.getByRole('button', { name: /^同意 ·/ }).click()
    await expect(refundReview.locator('.document-facts')).toContainText('待退款')
    await drawer.getByRole('button', { name: '刷新单据', exact: true }).click()
    await drawer.getByRole('button', { name: '登记已退款', exact: true }).click()
    await drawer.getByLabel('实际退款日期', { exact: true }).fill(day)
    await drawer.getByLabel('银行流水号', { exact: true }).fill('E2E-BANK-RETURN-001')
    await drawer.getByRole('button', { name: '确认登记已退款', exact: true }).click()
    await expect(drawer.locator('.document-facts')).toContainText('已退款')
    const final = await api<Doc>(page, company, `/refund-requests/${refund.id}`)
    expect(final.creditSources).toHaveLength(1)
    expect(final.creditSources![0]!.openAmount).toBe('23.90')
    const finalBank = await api<{ balance: string }>(page, company, `/cash-accounts/${bank.id}`)
    expect(finalBank.balance).toBe('990.00')
    await page.screenshot({ path: info.outputPath('信用额退款实际付款.png'), fullPage: true })
    await page.setViewportSize({ width: 390, height: 844 })
    await fits(page, drawer)
    await page.screenshot({ path: info.outputPath('信用额退款窄屏右侧详情.png'), fullPage: true })
    await drawer.getByRole('button', { name: '关闭', exact: true }).click()
    await page.setViewportSize({ width: 1440, height: 1000 })
    await page.goto(`/reports/sales-summary?detail=true&dateFrom=${day}&dateTo=${day}`)
    const salesReportUrl = page.url()
    await page.getByRole('button', { name: created.documentNo, exact: true }).click()
    const reportReturn = page.getByRole('dialog', { name: '无原单销售退货', exact: true })
    await expect(reportReturn.locator('.sourceless-panel h2').first()).toHaveText(created.documentNo)
    await expect(reportReturn.getByRole('button', { name: '过账', exact: true })).toHaveCount(0)
    await fits(page, reportReturn)
    await expect(page).toHaveURL(salesReportUrl)
    await page.screenshot({ path: info.outputPath('销售报表原位退货来源.png'), fullPage: true, animations: 'disabled' })
    await reportReturn.getByRole('button', { name: '关闭', exact: true }).click()
    await expect(page).toHaveURL(salesReportUrl)
    await page.goto(`/reports/cash-journal?dateFrom=${day}&dateTo=${day}`)
    const cashReportUrl = page.url()
    await page.getByRole('button', { name: refund.documentNo, exact: true }).click()
    const reportRefund = page.getByRole('dialog', { name: '退货信用额退款', exact: true })
    await expect(reportRefund.locator('.sourceless-panel h2').first()).toHaveText(refund.documentNo)
    await expect(reportRefund.locator('.financial-summary')).toContainText('10.00')
    await expect(reportRefund.getByRole('button', { name: '登记已退款', exact: true })).toHaveCount(0)
    await expect(page).toHaveURL(cashReportUrl)
    await fits(page, reportRefund)
    await page.screenshot({ path: info.outputPath('资金流水原位退款来源.png'), fullPage: true, animations: 'disabled' })
    await reportRefund.getByRole('button', { name: '关闭', exact: true }).click()
    await expect(page).toHaveURL(cashReportUrl)
    expect(createKeys).toHaveLength(1)
    expect(updateKeys).toHaveLength(1)
    expect(refundUpdateKeys).toHaveLength(1)
    expect(errors).toEqual([])
    await writeFile(
      info.outputPath('无原单浏览器结果.json'),
      JSON.stringify(
        {
          company,
          warehouseId: warehouse.id,
          skuId: sku.id,
          baseUnit: skuDetail.units!.find((unit) => unit.isBase)!.unitId,
          returnId: created.id,
          refundId: refund.id,
          createResponseLost: true,
          updateResponseLost: true,
          refundUpdateResponseLost: true,
          returnedCost: posted.costAmount,
          creditAmount: posted.creditAmount,
          remainingCredit: final.creditSources![0]!.openAmount,
          remainingBankBalance: finalBank.balance,
          refundWorkflowInstanceId: pendingRefund.wfInstanceId,
          listUrlPreserved: true,
          twoRealUsers: true,
          salesReportPositionPreserved: true,
          cashReportPositionPreserved: true,
          reportSourcesReadOnly: true,
          pageErrors: errors,
        },
        null,
        2,
      ),
      'utf8',
    )
  } finally {
    await reviewContext.close()
  }
})
