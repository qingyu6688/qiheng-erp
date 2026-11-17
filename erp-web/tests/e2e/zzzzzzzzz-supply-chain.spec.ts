import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { env } from 'node:process'

async function login(page: Page): Promise<void> {
  if (!env.ERP_E2E_PASSWORD || !/^E2E-[a-f0-9]{12}$/.test(env.ERP_E2E_TENANT_CODE ?? '')) {
    throw new Error('供应链浏览器验收须使用项目脚本的随机企业与临时口令')
  }
  await page.goto('/login')
  await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE!)
  await page.getByLabel('用户名').fill('supply_tester')
  await page.locator('#password').fill(env.ERP_E2E_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
}

test('库区与库位可维护，库位标签从当前公司加载', async ({ page }) => {
  await login(page)
  await page.goto('/warehouses')
  await page.getByRole('button', { name: '新建仓库' }).click()
  const warehouse = page.getByRole('dialog')
  await warehouse.getByLabel('仓库编码').fill('E2E-M14-BIN')
  await warehouse.getByLabel('仓库名称').fill('库位验收仓')
  await warehouse.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: 'E2E-M14-BIN' })).toBeVisible()
  await page.getByRole('link', { name: '库区库位', exact: true }).last().click()
  await page
    .locator('.el-select')
    .filter({ has: page.locator('#slot-warehouse') })
    .click()
  await page.getByRole('option', { name: 'E2E-M14-BIN 库位验收仓' }).click()
  await page.getByRole('button', { name: '新增库区' }).click()
  await page.getByLabel('库区编码').fill('RC')
  await page.getByLabel('库区名称').fill('收货区')
  await page.getByRole('dialog').getByRole('button', { name: '保存', exact: true }).click()
  await page.getByRole('button', { name: '批量生成', exact: true }).click()
  await page.getByLabel('编码前缀').fill('RC')
  await page.getByRole('dialog').getByRole('button', { name: '生成', exact: true }).click()
  const slot = page.getByRole('row').filter({ hasText: 'RC-01-01-01-01' })
  await expect(slot).toBeVisible()
  await slot.getByRole('link', { name: '标签' }).click()
  await expect(page.getByRole('img', { name: '库位条码 RC-01-01-01-01' })).toBeVisible()
  await expect(page.getByRole('button', { name: '打印标签' })).toBeEnabled()
})

test('数电发票可查看开具记录、进项台账和服务商启用状态', async ({ page }) => {
  await login(page)
  await page.goto('/e-invoices')
  await expect(page.getByRole('heading', { name: '数电发票' })).toBeVisible()
  await expect(page.getByText('暂无开具申请')).toBeVisible()
  await page.getByRole('button', { name: '新建开具申请' }).click()
  const dialog = page.getByRole('dialog', { name: '新建开具申请' })
  await expect(dialog.getByRole('button', { name: '提交申请' })).toBeDisabled()
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await page.getByRole('tab', { name: '进项台账' }).click()
  await expect(page.getByText('暂无进项发票', { exact: true })).toBeVisible()
  await page.getByRole('tab', { name: '服务商配置' }).click()
  await expect(page.getByRole('button', { name: '测试已保存的连接' })).toBeDisabled()
})
