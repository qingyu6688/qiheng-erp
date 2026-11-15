import { expect, test } from '@playwright/test'
import { env } from 'node:process'

test('管理员登录、维护仓库并切换公司', async ({ page }) => {
  const password = env.ERP_DEV_ADMIN_PASSWORD
  if (!password) throw new Error('请在项目根目录使用 project.ps1 e2e 运行浏览器测试')
  const code = 'WEB-' + crypto.randomUUID().slice(0, 12)
  await page.goto('/')
  await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE ?? 'DEMO')
  await page.getByLabel('用户名').fill('admin')
  await page.locator('#password').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '工作台', exact: true })).toBeVisible()
  await page.goto('/warehouses')

  await page.getByRole('button', { name: '新建仓库' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('仓库编码').fill(code)
  await dialog.getByLabel('仓库名称').fill('浏览器验收仓库')
  await dialog.getByRole('button', { name: '保存' }).click()
  const row = page.getByRole('row').filter({ hasText: code })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: '编辑' }).click()
  await dialog.getByLabel('仓库名称').fill('验收周转仓')
  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(row.getByRole('button', { name: '验收周转仓' })).toBeVisible()
  await row.getByRole('button', { name: '停用', exact: true }).click()
  await dialog.getByRole('button', { name: '确认停用' }).click()
  await expect(row.getByRole('button', { name: '启用', exact: true })).toBeVisible()

  await page.locator('.company-select').click()
  await page.getByRole('option', { name: '演示二公司' }).click()
  await expect(row).toHaveCount(0)
  await page.locator('.company-select').click()
  await page.getByRole('option', { name: '演示一公司' }).click()
  await row.getByRole('button', { name: '启用', exact: true }).click()
  await dialog.getByRole('button', { name: '确认启用' }).click()
  await expect(row.getByRole('button', { name: '停用', exact: true })).toBeVisible()

  const account = await page.request.get('/api/v1/auth/me')
  const companies: { id: string; code: string }[] = (await account.json()).data.companies
  const headers = { 'X-Company-Id': companies[0].id }
  const query = await page.request.get('/api/v1/warehouses', { headers, params: { keyword: code } })
  const warehouse: { id: string; version: number } = (await query.json()).data.items[0]
  const outside = await page.request.get('/api/v1/warehouses/' + warehouse.id, {
    headers: { 'X-Company-Id': companies[1].id },
  })
  expect(outside.status()).toBe(404)
  const token = (await page.context().cookies()).find(cookie => cookie.name === 'XSRF-TOKEN')
  const editHeaders = { ...headers, 'X-XSRF-TOKEN': token?.value ?? '' }
  const editUrl = '/api/v1/warehouses/' + warehouse.id
  const invalid = await page.request.put(editUrl, {
    headers: editHeaders, data: { code, name: '', version: warehouse.version },
  })
  expect(invalid.status()).toBe(400)
  expect((await invalid.json()).details).toContainEqual({ field: 'name', message: '请输入仓库名称' })
  const stale = await page.request.put(editUrl, {
    headers: editHeaders, data: { code, name: '验收周转仓', version: warehouse.version - 1 },
  })
  expect(stale.status()).toBe(409)
  expect((await stale.json()).details.currentVersion).toBe(warehouse.version)

  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible()
})
