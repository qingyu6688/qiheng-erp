import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { env } from 'node:process'
import { readFile } from 'node:fs/promises'

async function login(page: Page): Promise<void> {
  if (!env.ERP_E2E_PASSWORD || !/^E2E-[a-f0-9]{12}$/.test(env.ERP_E2E_TENANT_CODE ?? '')) throw new Error('导入浏览器验收须使用随机测试企业和临时口令')
  await page.goto('/login'); await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE!)
  await page.getByLabel('用户名').fill('admin'); await page.locator('#password').fill(env.ERP_E2E_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click(); await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
}
async function choose(page: Page, id: string, label: string): Promise<void> {
  const field = page.locator('#' + id); await page.locator('.el-select').filter({ has: field }).click()
  await expect(field).toHaveAttribute('aria-controls', /.+/); const list = await field.getAttribute('aria-controls')
  if (!list) throw new Error('下拉框没有关联选项列表')
  await page.locator('[role="listbox"][id=' + JSON.stringify(list) + ']').getByRole('option', { name: label, exact: true }).click()
}
async function submit(page: Page, content: string, filename: string): Promise<string> {
  await page.locator('#import-file').setInputFiles({ name: filename, mimeType: 'text/csv', buffer: Buffer.from(content, 'utf8') })
  const response = page.waitForResponse(item => item.url().endsWith('/api/v1/import-jobs') && item.request().method() === 'POST')
  await page.getByRole('button', { name: '开始导入', exact: true }).click(); const result = await response
  expect(result.status()).toBe(202); const body = await result.json() as { data: { id: string } }; return body.data.id
}

test('模板下载、部分成功错误清单、同文件去重和库存期初草稿跳转', async ({ page }, testInfo) => {
  test.setTimeout(100_000)
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message))
  await login(page); await page.goto('/file-jobs')
  await expect(page.getByRole('heading', { name: '导入与导出', exact: true })).toBeVisible()
  await expect(page.locator('.el-radio-button__inner').filter({ hasText: '数据导入' })).toBeVisible()
  const templateEvent = page.waitForEvent('download'); await page.getByRole('button', { name: '下载模板', exact: true }).click()
  const template = await templateEvent; const templatePath = await template.path(); if (!templatePath) throw new Error('模板下载没有生成文件')
  const bytes = await readFile(templatePath); expect([...bytes.subarray(0, 3)]).toEqual([239, 187, 191])
  expect(bytes.toString('utf8')).toContain('productCode,code,name,spec,defaultTaxRate,purchasePrice,salePrice,status')

  const skuCode = 'E2E-M7-IMPORT-SKU'
  const content = '\uFEFFproductCode,code,name,spec,defaultTaxRate,purchasePrice,salePrice,status\r\nE2E-M6-PROD,' + skuCode + ',导入验收商品,规格,0,10,20,active\r\nMISSING,E2E-M7-INVALID,错误商品,,0,,20,active\r\n'
  const first = await submit(page, content, '商品导入.csv')
  const detail = page.locator('section.panel').filter({ has: page.getByRole('heading', { level: 2, name: '处理结果 · 商品 SKU' }) })
  await expect(detail.getByText('部分成功', { exact: true })).toBeVisible({ timeout: 30_000 })
  await expect(detail.getByText('所属商品编码不存在', { exact: true })).toBeVisible()
  const errorsEvent = page.waitForEvent('download'); await page.getByRole('button', { name: '下载错误清单', exact: true }).click()
  const errorFile = await errorsEvent; const errorPath = await errorFile.path(); if (!errorPath) throw new Error('错误清单下载没有生成文件')
  expect((await readFile(errorPath, 'utf8'))).toContain('所属商品编码不存在')
  const duplicate = await submit(page, content, '商品导入.csv'); expect(duplicate).toBe(first)
  await page.goto('/skus'); await page.locator('#resource-search').fill(skuCode); await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.locator('.el-table__body-wrapper').getByRole('row').filter({ hasText: skuCode })).toHaveCount(1)

  await page.goto('/file-jobs'); await choose(page, 'import-type', '库存期初'); await choose(page, 'import-warehouse', '库存验收甲仓')
  await page.locator('#import-date').fill(new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date()))
  await submit(page, '\uFEFFskuCode,unitName,qty,totalCost,reason\r\nE2E-M6-SKU,库存验收件,1,10,导入验收期初\r\n', '期初导入.csv')
  await expect(page.getByRole('link', { name: '第 2 行 · 查看期初草稿', exact: true })).toBeVisible({ timeout: 30_000 })
  await page.getByRole('link', { name: '第 2 行 · 查看期初草稿', exact: true }).click()
  await expect(page).toHaveURL(/\/stock-adjustments\/[0-9]+$/)
  await expect(page.locator('.sales-detail-header .el-tag').first()).toHaveText('草稿')
  await expect(page.locator('.sales-detail-header')).toContainText('导入验收期初')
  await page.screenshot({ path: testInfo.outputPath('import-opening-draft.png'), fullPage: true })
  expect(errors).toEqual([])
})
