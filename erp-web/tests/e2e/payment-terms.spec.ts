import { expect, test } from '@playwright/test'
import { env } from 'node:process'

test('账期设置保存、公司默认值与到期日报表可在页面使用', async ({ page }, testInfo) => {
  test.setTimeout(100_000)
  if (!env.ERP_E2E_PASSWORD || !/^E2E-[a-f0-9]{12}$/.test(env.ERP_E2E_TENANT_CODE ?? '')) {
    throw new Error('账期验收须使用独立的随机测试企业和临时口令')
  }
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/login')
  await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE!)
  await page.getByLabel('用户名').fill('admin')
  await page.locator('#password').fill(env.ERP_E2E_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()

  await page.goto('/partners')
  await page.getByRole('button', { name: '新建往来单位', exact: true }).click()
  await page.getByLabel('编码', { exact: true }).fill('TERM-' + Date.now())
  await page.getByLabel('名称', { exact: true }).fill('账期验收单位')
  await page.locator('.el-select').filter({ has: page.locator('#field-roles') }).click()
  await page.getByRole('option', { name: '客户', exact: true }).click()
  await page.keyboard.press('Escape')
  await page.getByLabel('客户账期', { exact: true }).selectOption('days')
  await page.getByLabel('客户账期天数', { exact: true }).fill('30')
  const savedPartner = page.waitForResponse(response => response.url().endsWith('/api/v1/partners') && response.request().method() === 'POST')
  await page.getByRole('button', { name: '保存', exact: true }).click()
  const response = await savedPartner
  expect(response.status()).toBe(201)
  expect((await response.json()).data.paymentTerms.customer).toEqual({ type: 'days', value: 30 })
  await expect(page.getByRole('heading', { name: '新建往来单位', exact: true })).toHaveCount(0)

  await page.goto('/companies')
  await page.getByRole('button', { name: '业务参数', exact: true }).first().click()
  await page.getByLabel('默认账期', { exact: true }).selectOption('monthly')
  await page.getByLabel('默认账期次月几日', { exact: true }).fill('28')
  const savedPolicy = page.waitForResponse(result => result.url().endsWith('/policy') && result.request().method() === 'PUT')
  await page.getByRole('button', { name: '保存业务参数', exact: true }).click()
  const policy = await savedPolicy
  expect(policy.status()).toBe(200)
  expect((await policy.json()).data.defaultPaymentTerms).toEqual({ type: 'monthly', value: 28 })
  await page.screenshot({ path: testInfo.outputPath('公司默认账期.png'), fullPage: true })

  await page.goto('/reports/receivable-aging')
  await page.locator('#report-aging-basis').selectOption('due')
  const queried = page.waitForResponse(result => result.url().includes('/api/v1/reports/receivable-aging?') && result.url().includes('agingBasis=due'))
  await page.getByRole('button', { name: '查询', exact: true }).click()
  expect((await queried).status()).toBe(200)
  await expect(page.getByRole('columnheader', { name: '到期日', exact: true })).toBeVisible()
  await expect(page.getByRole('columnheader', { name: '逾期天数', exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('到期日账龄报表.png'), fullPage: true })
  expect(errors).toEqual([])
})
