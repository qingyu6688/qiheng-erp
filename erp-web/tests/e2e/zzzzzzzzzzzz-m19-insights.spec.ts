import { env } from 'node:process'
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import type { ApiResponse } from '../../src/types/api'
import type { UserSession } from '../../src/types/auth'

async function signIn(page: Page, user: string): Promise<void> {
  const tenant = env.ERP_E2E_TENANT_CODE
  const password = env.ERP_E2E_PASSWORD
  if (!tenant || !/^E2E-[a-f0-9]{12}$/.test(tenant) || !password) {
    throw new Error('M19 浏览器验收需要随机测试企业及临时口令')
  }
  await page.goto('/login')
  await page.getByLabel('企业编码').fill(tenant)
  await page.getByLabel('用户名').fill(user)
  await page.locator('#password').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
}

const entries = [
  { route: '/dashboard', label: '经营驾驶舱', api: '/api/v1/dashboard/metrics' },
  { route: '/print-templates', label: '打印模板', api: '/api/v1/print-templates' },
  { route: '/alerts', label: '预警中心', api: '/api/v1/alerts/unhandled-count' },
] as const

test('M19 报表导航、打印模板与预警按当前账户权限访问', async ({ page, browser, baseURL }) => {
  test.setTimeout(100_000)
  await signIn(page, 'reviewer')
  const current = (await (
    await page.request.get('/api/v1/auth/me')
  ).json()) as ApiResponse<UserSession>
  const company = current.data.companies[0]
  if (!company) throw new Error('验收复核员缺少公司范围')
  const headers = { 'X-Company-Id': company.id }
  const reportGroup = page.locator('.nav-group').filter({
    has: page.locator('summary').getByText('报表与数据', { exact: true }),
  })
  for (const entry of entries) {
    const link = reportGroup.getByRole('link', { name: entry.label, exact: true })
    if (!(await link.isVisible())) await reportGroup.locator('summary').click()
    await link.click()
    await expect(page).toHaveURL(new RegExp(`${entry.route}(?:\\?.*)?$`))
    expect(
      (await page.request.get(entry.api, { headers })).ok(),
      `${entry.label}接口读取失败`,
    ).toBe(true)
    const foreign = await page.request.get(entry.api, {
      headers: { 'X-Company-Id': '9223372036854775807' },
    })
    expect([403, 404]).toContain(foreign.status())
    if (entry.route === '/print-templates') {
      await page.getByRole('button', { name: '新建模板', exact: true }).click()
      await page.getByRole('combobox', { name: '模板单据类型', exact: true }).press('Enter')
      await page.getByRole('option', { name: '销售发货单', exact: true }).click()
      await page.getByRole('combobox', { name: '纸型', exact: true }).press('Enter')
      await page.getByRole('option', { name: '241 × 93 毫米三等分', exact: true }).click()
      await page.getByRole('button', { name: '预览', exact: true }).click()
      const preview = page.getByRole('dialog', { name: '模板预览' })
      await expect(preview.locator('.print-design-sheet')).toHaveCount(1)
      await expect(preview.getByText(/SAMPLE-001/)).toBeVisible()
      await expect(preview.getByText(/第 1 \/ 1 页/)).toBeVisible()
      await page.keyboard.press('Escape')
      await expect(preview).not.toBeVisible()
    }
  }

  const restricted = await browser.newContext({ baseURL })
  try {
    const employee = await restricted.newPage()
    await signIn(employee, 'supply_tester')
    const profile = (await (
      await employee.request.get('/api/v1/auth/me')
    ).json()) as ApiResponse<UserSession>
    if (!profile.data.companies[0]) throw new Error('供应链验收员缺少公司范围')
    const scope = { 'X-Company-Id': profile.data.companies[0].id }
    for (const entry of entries) {
      await employee.goto(entry.route)
      await expect(employee).toHaveURL(/\/403(?:\?|$)/)
      expect((await employee.request.get(entry.api, { headers: scope })).status()).toBe(403)
    }
  } finally {
    await restricted.close()
  }
})
