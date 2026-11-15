import { expect, test } from '@playwright/test'
import { env } from 'node:process'

test('管理员保存渠道设置，关闭的渠道不出现在登录页和绑定入口', async ({ page }) => {
  const tenant = env.ERP_E2E_TENANT_CODE
  const password = env.ERP_E2E_PASSWORD
  if (!password || !tenant || !/^E2E-[a-f0-9]{12}$/.test(tenant)) {
    throw new Error('登录配置验收须使用随机测试企业和临时口令')
  }

  await page.goto('/login')
  await page.getByLabel('企业编码').fill(tenant)
  await page.getByLabel('用户名').fill('admin')
  await page.locator('#password').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '工作台', exact: true })).toBeVisible()

  await page.getByText('系统管理', { exact: true }).click()
  await page.getByRole('link', { name: '登录方式设置', exact: true }).click()
  await expect(page.getByRole('heading', { name: '登录方式设置', exact: true })).toBeVisible()
  await expect(page.getByRole('switch', { name: '启用手机短信登录' })).toHaveAttribute('aria-checked', 'false')
  await expect(page.getByRole('switch', { name: '启用邮箱登录' })).toHaveAttribute('aria-checked', 'false')
  await expect(page.getByRole('switch', { name: '启用微信登录' })).toHaveAttribute('aria-checked', 'false')
  const signature = '登录配置测试'
  await page.getByLabel('短信签名').fill(signature)
  const saved = page.waitForResponse(response =>
    response.url().endsWith('/api/v1/login-settings') && response.request().method() === 'PUT')
  await page.getByRole('button', { name: '保存设置' }).click()
  expect((await saved).ok()).toBe(true)
  await page.reload()
  await expect(page.getByLabel('短信签名')).toHaveValue(signature)
  await expect(page.getByLabel('访问密钥 Secret')).toHaveValue('')

  await page.locator('summary[aria-label$="账户菜单"]').click()
  await page.getByRole('link', { name: '我的登录方式' }).click()
  await expect(page.getByRole('heading', { name: '我的登录方式' })).toBeVisible()
  const bindings = page.getByRole('region', { name: '账户绑定' })
  await expect(bindings.getByText(/企业未启用/)).toHaveCount(3)
  await expect(bindings.getByRole('button')).toHaveCount(0)

  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  const tenantInput = page.getByLabel('企业编码')
  await tenantInput.clear()
  const methods = page.waitForResponse(response => {
    const url = new URL(response.url())
    return url.pathname === '/api/v1/auth/methods' && url.searchParams.get('tenantCode') === tenant
  })
  await tenantInput.fill(tenant)
  expect((await methods).ok()).toBe(true)
  await expect(page.getByRole('group', { name: '登录方式' })).toHaveCount(0)
  await expect(page.getByLabel('用户名')).toBeVisible()
})
