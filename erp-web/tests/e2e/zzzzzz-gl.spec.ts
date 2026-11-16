import { expect, test } from '@playwright/test'
import type { Page, Locator } from '@playwright/test'
import { env } from 'node:process'

async function login(page: Page, user: string): Promise<void> {
  if (!env.ERP_E2E_PASSWORD || !/^E2E-[a-f0-9]{12}$/.test(env.ERP_E2E_TENANT_CODE ?? ''))
    throw new Error('财务演练必须使用独立测试企业')
  await page.addLocatorHandler(
    page.getByRole('button', { name: '我已阅读并确认', exact: true }),
    async (button) => {
      await button.click()
    },
  )
  await page.goto('/login')
  await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE + '-GL')
  await page.getByLabel('用户名').fill(user)
  await page.locator('#password').fill(env.ERP_E2E_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
}
async function select(page: Page, control: Locator, name: string): Promise<void> {
  await control.click()
  const combo = control.getByRole('combobox')
  await expect(combo).toHaveAttribute('aria-expanded', 'true')
  const list = await combo.getAttribute('aria-controls')
  if (!list) throw new Error('下拉框缺少选项列表关联')
  await page
    .locator('[id="' + list + '"]')
    .getByRole('option', { name, exact: true })
    .click()
}
async function confirm(page: Page): Promise<void> {
  const box = page.locator('.el-message-box')
  await box.getByRole('textbox').fill('浏览器独立财务复核')
  await box.getByRole('button', { name: /确定|确认/, exact: false }).click()
  await expect(box).toBeHidden()
}
test('独立账套、手工凭证双人复核、报表、打印与后台导出使用真实服务', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(180_000)
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  const context = await browser.newContext({ baseURL: 'http://127.0.0.1:15173' }),
    reviewer = await context.newPage()
  try {
    await login(page, 'glmaker')
    await login(reviewer, 'glreviewer')
    await page.goto('/gl/book')
    await select(
      page,
      page.locator('.el-form-item').filter({ hasText: '启用期间' }).locator('.el-select'),
      '202610',
    )
    await page.getByRole('button', { name: '创建并复制模板', exact: true }).click()
    await expect(page.getByRole('button', { name: '确认当前财务口径', exact: true })).toBeVisible()
    await page.getByRole('button', { name: '确认当前财务口径', exact: true }).click()
    await confirm(page)
    await expect(page.getByText('当前配置已确认', { exact: false })).toBeVisible()
    await page.getByRole('button', { name: '启用账套', exact: true }).click()
    await expect(
      page.getByRole('heading', { name: '企业会计准则 · 已启用', exact: false }),
    ).toBeVisible()
    await page.goto('/gl/vouchers/new')
    await page.getByLabel('凭证日期', { exact: true }).fill('2026-10-15')
    const rows = page.locator('.el-table__body-wrapper tr')
    await rows.nth(0).getByRole('textbox').nth(0).fill('浏览器费用计提')
    await select(page, rows.nth(0).locator('.el-select').nth(0), '6602 管理费用')
    await rows.nth(0).locator('input[inputmode="decimal"]').fill('25.00')
    await rows.nth(1).getByRole('textbox').nth(0).fill('浏览器其他应付')
    await select(page, rows.nth(1).locator('.el-select').nth(0), '2241 其他应付款')
    await rows.nth(1).locator('input[inputmode="decimal"]').fill('25.00')
    await page.getByRole('button', { name: '保存草稿', exact: true }).click()
    await expect(page).toHaveURL(/\/gl\/vouchers\/[0-9]+$/)
    const id = page.url().split('/').at(-1)!
    await page.goto('/gl/vouchers')
    let row = page
      .getByRole('row')
      .filter({ has: page.locator('a[href="/gl/vouchers/' + id + '"]') })
    await row.getByRole('button', { name: '提交', exact: true }).click()
    await confirm(page)
    await reviewer.goto('/gl/vouchers')
    const reviewRow = reviewer
      .getByRole('row')
      .filter({ has: reviewer.locator('a[href="/gl/vouchers/' + id + '"]') })
    await reviewRow.getByRole('button', { name: '复核', exact: true }).click()
    await confirm(reviewer)
    await page.reload()
    row = page.getByRole('row').filter({ has: page.locator('a[href="/gl/vouchers/' + id + '"]') })
    await row.getByRole('button', { name: '记账', exact: true }).click()
    await confirm(page)
    await expect(row).toContainText('已记账')
    await page.goto('/gl/vouchers/' + id)
    const popup = page.waitForEvent('popup')
    await page.getByRole('button', { name: '打印预览', exact: true }).click()
    const paper = await popup
    await expect(paper.locator('.financial-paper')).toContainText('浏览器费用计提')
    await expect(paper.locator('footer')).toContainText('财务复核员')
    await paper.close()
    await page.goto('/gl/reports')
    await select(page, page.locator('.report-filters .el-select').nth(1), '202610')
    await page.getByRole('button', { name: '查看报表', exact: true }).click()
    await expect(page.locator('.report-checks')).toContainText('差额 0.00')
    await page.screenshot({ path: info.outputPath('gl-balance-sheet.png'), fullPage: true })
    await page.getByRole('button', { name: '后台导出', exact: true }).click()
    await expect(page).toHaveURL(/\/file-jobs\?tab=export&jobId=/)
    await expect(page.getByRole('row').filter({ hasText: '资产负债表' })).toContainText('已完成', {
      timeout: 30_000,
    })
    const fileReady = page.waitForEvent('download')
    await page
      .getByRole('row')
      .filter({ hasText: '资产负债表' })
      .getByRole('button', { name: '下载文件', exact: true })
      .click()
    const file = await fileReady
    expect(file.suggestedFilename()).toMatch(/\.xlsx$/)
    await file.saveAs(info.outputPath('gl-balance-sheet.xlsx'))
    expect(errors).toEqual([])
  } finally {
    await context.close()
  }
})
