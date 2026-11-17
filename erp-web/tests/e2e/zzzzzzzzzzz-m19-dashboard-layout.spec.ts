import { env } from 'node:process'
import { expect, test } from '@playwright/test'
import type { Locator } from '@playwright/test'

async function selectIndicator(choice: Locator, selected: boolean): Promise<void> {
  if ((await choice.isChecked()) === selected) return
  await choice.locator('xpath=ancestor::label[1]').click()
  await expect(choice).toBeChecked({ checked: selected })
}

test('M19 驾驶舱布局保存后保留顺序及隐藏设置', async ({ page }) => {
  test.setTimeout(90_000)
  const tenant = env.ERP_E2E_TENANT_CODE
  const password = env.ERP_E2E_PASSWORD
  if (!tenant || !/^E2E-[a-f0-9]{12}$/.test(tenant) || !password) {
    throw new Error('驾驶舱浏览器验收须使用独立测试企业和临时口令')
  }

  await page.goto('/login')
  await page.getByLabel('企业编码').fill(tenant)
  await page.getByLabel('用户名').fill('admin')
  await page.locator('#password').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
  await page.goto('/dashboard')
  await expect(page.getByRole('heading', { name: '经营驾驶舱', exact: true })).toBeVisible()
  const configuration = page.getByRole('button', { name: '配置布局', exact: true })

  await configuration.click()
  const dialog = page.getByRole('dialog', { name: '配置驾驶舱布局' })
  const choices = dialog.getByRole('checkbox')
  const indicator = (name: string) => dialog.getByRole('checkbox', { name, exact: true })
  await expect(dialog).toBeVisible()
  await expect(indicator('毛利')).toBeAttached()
  for (const choice of await choices.all()) {
    await selectIndicator(choice, false)
  }
  for (const name of ['销售', '毛利', '库存']) await selectIndicator(indicator(name), true)
  await dialog.getByRole('button', { name: '上移毛利', exact: true }).click()
  await selectIndicator(indicator('库存'), false)
  const saved = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/v1/dashboard/layout') && response.request().method() === 'PUT',
  )
  await dialog.getByRole('button', { name: '保存布局', exact: true }).click()
  const response = await saved
  expect(response.ok()).toBe(true)
  expect(response.request().postDataJSON()).toMatchObject({ metrics: ['gross_margin', 'sales'] })
  await expect(dialog).not.toBeVisible()

  await page.reload()
  await expect(page.getByRole('heading', { name: '毛利', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '销售', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '库存', exact: true })).toHaveCount(0)
  await configuration.click()
  await expect(dialog.getByRole('listitem').first()).toContainText('毛利')
  await expect(dialog.getByRole('listitem').nth(1)).toContainText('销售')
  await expect(dialog.getByRole('checkbox', { name: '库存', exact: true })).not.toBeChecked()
})
