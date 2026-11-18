import { env } from 'node:process'
import { expect, test } from '@playwright/test'
import type { Browser, Page } from '@playwright/test'
import type { UserSession } from '../../src/types/auth'
import type { ApiResponse } from '../../src/types/api'

async function login(page: Page, user: string): Promise<void> {
  const tenant = env.ERP_E2E_TENANT_CODE
  const password = env.ERP_E2E_PASSWORD
  if (!tenant || !/^E2E-[a-f0-9]{12}$/.test(tenant) || !password) {
    throw new Error('生产浏览器验收须使用独立测试企业与临时口令')
  }
  await page.goto('/login')
  await page.getByLabel('企业编码').fill(tenant)
  await page.getByLabel('用户名').fill(user)
  await page.locator('#password').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
}

async function isolatedPage(browser: Browser, origin: string) {
  const context = await browser.newContext({ baseURL: origin })
  return { context, page: await context.newPage() }
}

test('M18 生产导航、成本与报表使用真实接口，普通仓储角色无法访问', async ({
  page,
  browser,
  baseURL,
}) => {
  test.setTimeout(90_000)
  await login(page, 'admin')
  await page.getByRole('button', { name: '生产', exact: true }).click()
  const areas = [
    ['物料清单', '/api/v1/mfg/boms', '新建 BOM'],
    ['生产工单', '/api/v1/mfg/work-orders', '新建工单'],
    ['生产领退料', '/api/v1/mfg/issues', '新建领料单'],
    ['完工入库', '/api/v1/mfg/receipts', '新建完工入库单'],
    ['生产成本', '/api/v1/mfg/cost-periods', ''],
    ['生产报表', '/api/v1/mfg/reports/progress', '查询'],
  ] as const
  for (const [title, resource, command] of areas) {
    const list = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === resource && response.request().method() === 'GET',
    )
    await page.getByRole('link', { name: title, exact: true }).click()
    await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
    expect((await list).ok(), `${title}接口读取失败`).toBe(true)
    if (command)
      await expect(page.getByRole('button', { name: command, exact: true })).toBeVisible()
  }
  await expect(page.getByRole('tab', { name: '生产成本明细' })).toBeVisible()

  if (!baseURL) throw new Error('生产浏览器验收缺少站点入口')
  const clerk = await isolatedPage(browser, baseURL)
  try {
    await login(clerk.page, 'supply_tester')
    await expect(
      clerk.page.getByRole('button', { name: '生产', exact: true }),
    ).toHaveCount(0)
    await clerk.page.goto('/mfg/cost-periods')
    await expect(clerk.page).toHaveURL(/\/403(?:\?|$)/)
    const profile = await clerk.page.request.get('/api/v1/auth/me')
    const { data } = (await profile.json()) as ApiResponse<UserSession>
    if (!data.companies[0]) throw new Error('仓储验收员缺少所属公司')
    const options = { headers: { 'X-Company-Id': data.companies[0].id } }
    expect((await clerk.page.request.get('/api/v1/mfg/boms', options)).status()).toBe(403)
    expect(
      (await clerk.page.request.get('/api/v1/mfg/reports/cost-detail', options)).status(),
    ).toBe(403)
  } finally {
    await clerk.context.close()
  }
})
