import { expect, test } from '@playwright/test'
import { env } from 'node:process'
import type { Page } from '@playwright/test'

async function choose(page: Page, field: string, label: string): Promise<void> {
  await page
    .locator('.el-select')
    .filter({ has: page.locator('#field-' + field) })
    .click()
  await page.getByRole('option', { name: label, exact: true }).click()
}

test('维护基础资料、商品单位条码和角色权限', async ({ page }, testInfo) => {
  test.setTimeout(90_000)
  const browserErrors: string[] = []
  page.on('pageerror', (error) => browserErrors.push(error.message))
  const password = env.ERP_DEV_ADMIN_PASSWORD
  if (!password) throw new Error('请使用项目根目录的 project.ps1 e2e 运行测试')
  const suffix = Date.now().toString().slice(-7)
  const unit = '件' + suffix
  const category = '分类' + suffix
  const product = '商品' + suffix
  const sku = '规格' + suffix
  await page.goto('/login')
  await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE ?? 'DEMO')
  await page.getByLabel('用户名').fill('admin')
  await page.locator('#password').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '工作台', exact: true })).toBeVisible()

  await page.goto('/units')
  await page.getByRole('button', { name: '新建计量单位' }).click()
  await page.getByLabel('名称', { exact: true }).fill(unit)
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: unit })).toBeVisible()

  await page.goto('/product-categories')
  await page.getByRole('button', { name: '新建商品分类' }).click()
  await page.getByLabel('编码', { exact: true }).fill('CAT' + suffix)
  await page.getByLabel('名称', { exact: true }).fill(category)
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: category })).toBeVisible()

  await page.goto('/products')
  await page.getByRole('button', { name: '新建商品', exact: true }).click()
  await page.getByLabel('编码', { exact: true }).fill('P' + suffix)
  await page.getByLabel('名称', { exact: true }).fill(product)
  await choose(page, 'categoryId', category)
  await choose(page, 'baseUnitId', unit)
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: product })).toBeVisible()

  await page.goto('/skus')
  await page.getByRole('button', { name: '新建SKU', exact: true }).click()
  await choose(page, 'productId', product)
  await page.getByLabel('编码', { exact: true }).fill('SKU' + suffix)
  await page.getByLabel('名称', { exact: true }).fill(sku)
  await page.getByRole('button', { name: '保存', exact: true }).click()
  const row = page.getByRole('row').filter({ hasText: sku })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: '单位与条码' }).click()
  const skuPanel = page.getByRole('region', { name: '单位与条码 · ' + sku })
  await skuPanel.getByRole('tab', { name: /^条码/ }).click()
  await skuPanel.getByRole('button', { name: '添加条码' }).click()
  await page.getByRole('textbox', { name: '条码', exact: true }).fill('EAN' + suffix)
  await page
    .locator('.el-select')
    .filter({ has: page.getByRole('combobox', { name: '条码单位', exact: true }) })
    .click()
  await page.getByRole('option', { name: unit, exact: true }).click()
  await page.screenshot({ path: testInfo.outputPath('sku-barcode-panel.png'), fullPage: true })
  await page.getByRole('button', { name: '保存条码' }).click()
  await expect(page.getByText('设置已保存')).toBeVisible()
  await page.getByRole('button', { name: '关闭', exact: true }).last().click()

  await page.goto('/partners')
  await page.getByRole('button', { name: '新建往来单位' }).click()
  await page.getByLabel('编码', { exact: true }).fill('CUS' + suffix)
  await page.getByLabel('名称', { exact: true }).fill('客户' + suffix)
  await choose(page, 'roles', '客户')
  await page
    .getByRole('region', { name: '新建往来单位', exact: true })
    .getByRole('button', { name: '保存', exact: true })
    .click()
  await expect(page.getByRole('row').filter({ hasText: '客户' + suffix })).toBeVisible()

  await page.goto('/roles')
  await page.getByRole('button', { name: '新建角色' }).click()
  await page.getByLabel('编码', { exact: true }).fill('ROLE' + suffix)
  await page.getByLabel('名称', { exact: true }).fill('业务员' + suffix)
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await page
    .getByRole('row')
    .filter({ hasText: '业务员' + suffix })
    .getByRole('button', { name: '权限设置' })
    .click()
  const warehousePermission = page.getByRole('checkbox', { name: /warehouse:view/ })
  await page.locator('label.el-checkbox').filter({ has: warehousePermission }).click()
  await expect(warehousePermission).toBeChecked()
  await expect(page.getByRole('table', { name: '角色权限矩阵' })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('role-permission-matrix.png'), fullPage: true })
  await page.getByRole('button', { name: '保存权限' }).click()
  await expect(page.getByText('设置已保存')).toBeVisible()
  await page.getByRole('button', { name: '关闭', exact: true }).last().click()

  await page.goto('/cash-accounts')
  await page.getByRole('button', { name: '新建结算账户' }).click()
  await page.getByLabel('编码', { exact: true }).fill('BANK' + suffix)
  await page.getByLabel('名称', { exact: true }).fill('银行' + suffix)
  await page.getByRole('button', { name: '保存', exact: true }).click()
  const cash = page.getByRole('row').filter({ hasText: '银行' + suffix })
  await expect(cash).toBeVisible()
  await expect(cash).toContainText('0.00')

  await page.goto('/accounting-periods')
  await page.getByRole('button', { name: '开启会计期间' }).click()
  const response = await page.request.get('/api/v1/auth/me')
  const current = (await response.json()).data.companies.find(
    (c: { name: string }) => c.name === '演示一公司',
  )
  const periods = await page.request.get('/api/v1/accounting-periods', {
    headers: { 'X-Company-Id': current.id },
    params: { limit: 200 },
  })
  const existing: { period: string }[] = (await periods.json()).data.items
  const last = existing
    .map((p) => p.period)
    .sort()
    .at(-1)
  const date = last ? new Date(Number(last.slice(0, 4)), Number(last.slice(4))) : new Date()
  const period = String(date.getFullYear()) + String(date.getMonth() + 1).padStart(2, '0')
  await page.getByLabel('期间（如 202611）').fill(period)
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: period })).toBeVisible()

  await page.goto('/users')
  await page.getByRole('button', { name: '新建用户' }).click()
  await page.getByLabel('用户名', { exact: true }).fill('sales' + suffix)
  await page.getByLabel('姓名', { exact: true }).fill('业务用户' + suffix)
  await page.getByLabel('初始密码', { exact: true }).fill('Temporary!2026')
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await page
    .getByRole('row')
    .filter({ hasText: '业务用户' + suffix })
    .getByRole('button', { name: '权限设置' })
    .click()
  const userPanel = page.getByRole('region', { name: '用户权限设置 · 业务用户' + suffix })
  await userPanel
    .locator('.relation-section')
    .filter({ has: page.getByRole('heading', { name: '角色', exact: true }) })
    .locator('.el-select')
    .click()
  await page.getByRole('option', { name: '业务员' + suffix, exact: true }).click()
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: '保存角色', exact: true }).click()
  await expect(page.getByText('设置已保存')).toBeVisible()
  await page.getByRole('button', { name: '添加范围' }).click()
  await userPanel.locator('.relation-row').last().locator('.el-select').first().click()
  await page.getByRole('option', { name: '演示一公司', exact: true }).click()
  await page.getByRole('button', { name: '保存数据范围' }).click()
  await expect(page.getByText('设置已保存')).toBeVisible()
  await userPanel.locator('input[type="password"]').fill('ResetPass!2026')
  await page.getByRole('button', { name: '重置密码', exact: true }).click()
  await expect(page.getByText('设置已保存')).toBeVisible()
  await page.getByRole('button', { name: '关闭', exact: true }).last().click()
  await page.getByRole('button', { name: '退出登录' }).click()
  await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE ?? 'DEMO')
  await page.getByLabel('用户名').fill('sales' + suffix)
  await page.locator('#password').fill('ResetPass!2026')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '修改密码' })).toBeVisible()
  await page.getByLabel('原密码', { exact: true }).fill('ResetPass!2026')
  await page.getByLabel('新密码', { exact: true }).fill('SalesPass!2026')
  await page.getByLabel('再次输入新密码', { exact: true }).fill('SalesPass!2026')
  await page.getByRole('button', { name: '保存新密码' }).click()
  await expect(page.getByText('密码已修改。')).toBeVisible()
  await page.getByRole('button', { name: '返回工作台' }).click()
  await expect(page.getByRole('heading', { name: '工作台', exact: true })).toBeVisible()
  expect(browserErrors).toEqual([])
})
