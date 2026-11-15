import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { env } from 'node:process'
import { readFile } from 'node:fs/promises'
import { reportSpecs } from '../../src/types/reporting'

async function login(page: Page): Promise<void> {
  if (!env.ERP_E2E_PASSWORD || !/^E2E-[a-f0-9]{12}$/.test(env.ERP_E2E_TENANT_CODE ?? '')) throw new Error('报表浏览器验收须使用随机测试企业和临时口令')
  await page.goto('/login'); await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE!)
  await page.getByLabel('用户名').fill('admin'); await page.locator('#password').fill(env.ERP_E2E_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click(); await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
}

test('十类实时报表、单据穿透、收发存明细与后台CSV导出下载', async ({ page }, testInfo) => {
  test.setTimeout(100_000)
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message))
  await login(page)
  for (const spec of reportSpecs) {
    const requested = page.waitForResponse(response => response.url().includes('/api/v1/reports/' + spec.type + '?') && response.request().method() === 'GET')
    await page.goto('/reports/' + spec.type); expect((await requested).status()).toBe(200)
    await expect(page.getByRole('heading', { name: spec.title, exact: true })).toBeVisible()
    await expect(page.getByText(/数据时间 /)).toBeVisible()
    await expect(page.getByRole('alert').filter({ hasText: /失败|无权|没有/ })).toHaveCount(0)
  }
  await page.goto('/reports/sales-summary')
  await page.locator('.el-table__body-wrapper').getByRole('button', { name: '单据明细', exact: true }).first().click()
  await expect(page).toHaveURL(/\/reports\/sales-summary\?.*detail=true/)
  await expect(page.getByRole('heading', { name: '销售报表明细', exact: true })).toBeVisible()
  const source = page.locator('.el-table__body-wrapper').getByRole('link', { name: '查看原单', exact: true }).first()
  await expect(source).toBeVisible(); const path = await source.getAttribute('href'); expect(path).toMatch(/^\/(shipments|sales-returns|document-reversals)\/[0-9]+$/)
  await source.click(); await expect(page).toHaveURL(new RegExp(path! + '$')); await expect(page.locator('.sales-detail-header')).toBeVisible()

  await page.goto('/reports/inventory-summary')
  await page.locator('.el-table__body-wrapper').getByRole('button', { name: '库存流水', exact: true }).first().click()
  await expect(page).toHaveURL(/\/reports\/inventory-flow\?.*warehouseId=.*skuId=/)
  await expect(page.getByRole('heading', { name: '库存收发存', exact: true })).toBeVisible()
  await page.locator('.el-table__body-wrapper').getByRole('button', { name: '库存流水', exact: true }).first().click()
  await expect(page).toHaveURL(/detail=true/); await expect(page.getByRole('heading', { name: '库存收发存明细', exact: true })).toBeVisible()

  await page.goto('/reports/inventory-summary')
  const submitted = page.waitForResponse(response => response.url().endsWith('/api/v1/export-jobs') && response.request().method() === 'POST')
  await page.getByRole('button', { name: '导出报表', exact: true }).click(); const response = await submitted; expect(response.status()).toBe(202)
  const body = await response.json() as { data: { taskId: string } }; await expect(page).toHaveURL(new RegExp('/file-jobs\\?tab=export&jobId=' + body.data.taskId + '$'))
  const row = page.locator('.el-table__body-wrapper').getByRole('row').filter({ hasText: '库存汇总' }).first()
  await expect(row).toContainText('已完成', { timeout: 30_000 })
  const downloadEvent = page.waitForEvent('download'); await row.getByRole('button', { name: '下载文件', exact: true }).click()
  const download = await downloadEvent; expect(download.suggestedFilename()).toBe('库存汇总.csv')
  const local = await download.path(); if (!local) throw new Error('导出下载没有生成文件')
  const bytes = await readFile(local); expect([...bytes.subarray(0, 3)]).toEqual([239, 187, 191])
  const csv = bytes.toString('utf8'); expect(csv).toContain('在库数量'); expect(csv).toContain('库存验收甲仓'); expect(csv).toContain('E2E-M6-SKU'); expect(csv).not.toContain('sourceId'); expect(csv).not.toContain('detailUrl')
  await page.screenshot({ path: testInfo.outputPath('report-export-completed.png'), fullPage: true })
  expect(errors).toEqual([])
})
