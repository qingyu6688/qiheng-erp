import { expect, test } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { env } from 'node:process'
import { readFile } from 'node:fs/promises'

async function login(page: Page, username: string): Promise<void> {
  if (!env.ERP_E2E_PASSWORD || !/^E2E-[a-f0-9]{12}$/.test(env.ERP_E2E_TENANT_CODE ?? '')) throw new Error('附件打印验收须使用随机测试企业和临时口令')
  await page.goto('/login'); await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE!)
  await page.getByLabel('用户名').fill(username); await page.locator('#password').fill(env.ERP_E2E_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click(); await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
}
async function choose(page: Page, field: Locator, name: string): Promise<void> {
  await page.locator('.el-select').filter({ has: field }).click()
  const id = await field.getAttribute('aria-controls'); if (!id) throw new Error('下拉框缺少关联选项')
  await page.locator('[role="listbox"][id=' + JSON.stringify(id) + ']').getByRole('option', { name, exact: true }).click()
}
async function command(page: Page, label: string): Promise<void> {
  await page.getByRole('button', { name: label, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: label, exact: true })
  await dialog.getByRole('button', { name: '确认' + label, exact: true }).click(); await expect(dialog).toBeHidden()
}

test('已过账单据附件上传下载删除、私有权限与A4打印预览', async ({ page, browser }, testInfo) => {
  test.setTimeout(100_000)
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message))
  const reviewerContext = await browser.newContext({ baseURL: 'http://127.0.0.1:15173', locale: 'zh-CN' })
  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:15173', locale: 'zh-CN' })
  const reviewer = await reviewerContext.newPage()
  try {
    await login(page, 'admin'); await login(reviewer, 'reviewer')
    await page.goto('/stock-adjustments'); await page.getByRole('button', { name: '新建库存调整单', exact: true }).click()
    await choose(page, page.getByRole('combobox', { name: '调整类型', exact: true }), '其他入库')
    await choose(page, page.locator('#purchase-warehouse'), '库存验收甲仓')
    await page.locator('#purchase-reason').fill('附件与打印浏览器验收')
    await choose(page, page.getByRole('combobox', { name: '采购商品1', exact: true }), 'E2E-M6-SKU 库存验收商品')
    await page.getByRole('textbox', { name: '采购数量1', exact: true }).fill('1')
    await page.getByRole('textbox', { name: '调整总成本1', exact: true }).fill('10')
    await page.getByRole('dialog').getByRole('button', { name: '保存库存调整单', exact: true }).click()
    await expect(page).toHaveURL(/\/stock-adjustments\/[0-9]+$/)
    const documentPath = new URL(page.url()).pathname, id = documentPath.split('/').at(-1)!
    await command(page, '提交'); await reviewer.goto(documentPath); await command(reviewer, '审批通过'); await page.reload(); await command(page, '过账')
    await expect(page.locator('.sales-detail-header .status-tag').first()).toHaveText('已过账')

    const panel = page.locator('.document-files'); await expect(panel.getByText('暂无附件', { exact: true })).toBeVisible()
    const pdf = Buffer.from('%PDF-1.7\n附件浏览器验收\n%%EOF', 'utf8')
    await panel.getByLabel('添加附件').setInputFiles({ name: '验收附件.pdf', mimeType: 'application/pdf', buffer: pdf })
    const uploaded = page.waitForResponse(response => response.url().endsWith('/api/v1/attachments') && response.request().method() === 'POST')
    await panel.getByRole('button', { name: '上传附件', exact: true }).click(); expect((await uploaded).status()).toBe(201)
    const row = panel.locator('li').filter({ hasText: '验收附件.pdf' }); await expect(row).toBeVisible()
    const downloadEvent = page.waitForEvent('download'); await row.getByRole('button', { name: '下载', exact: true }).click()
    const download = await downloadEvent; expect(download.suggestedFilename()).toBe('验收附件.pdf')
    const downloadedPath = await download.path(); if (!downloadedPath) throw new Error('附件没有生成下载文件')
    expect(await readFile(downloadedPath)).toEqual(pdf)
    await expect(panel.locator('input[type=text]')).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath('document-attachment-uploaded.png'), fullPage: true })

    expect((await anonymous.request.get('/api/v1/print-data/stock_adjustment/' + id)).status()).toBe(401)
    await panel.getByRole('link', { name: '打印预览', exact: true }).click(); await expect(page).toHaveURL(new RegExp('/print/stock_adjustment/' + id + '$'))
    const sheet = page.locator('.print-sheet'); await expect(sheet).toBeVisible(); await expect(sheet).toContainText('库存调整单')
    await expect(sheet).toContainText('库存验收商品'); await expect(sheet).toContainText('10.00'); await expect(sheet).toContainText('成本合计')
    await expect(sheet.locator('.print-signatures')).toContainText('审核：验收复核员'); await expect(sheet.locator('.print-signatures')).toContainText('复核 / 经办')
    expect(await sheet.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    await sheet.screenshot({ path: testInfo.outputPath('document-print-preview.png') })
    await page.emulateMedia({ media: 'print' }); await expect(page.locator('.sidebar')).toBeHidden(); await expect(page.locator('.print-controls').first()).toBeHidden()
    await page.setViewportSize({ width: 794, height: 1123 })
    expect(await sheet.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath('document-print-a4.png'), fullPage: true })
    await page.pdf({ path: testInfo.outputPath('document-print-a4.pdf'), format: 'A4', printBackground: true })
    await page.emulateMedia({ media: 'screen' }); await page.setViewportSize({ width: 1280, height: 720 })

    await page.goto(documentPath); const removePanel = page.locator('.document-files')
    await removePanel.locator('li').filter({ hasText: '验收附件.pdf' }).getByRole('button', { name: '删除', exact: true }).click()
    await page.getByRole('tooltip').getByRole('button', { name: '删除', exact: true }).click()
    await expect(removePanel.getByText('暂无附件', { exact: true })).toBeVisible()
    expect(errors).toEqual([])
  } finally { await reviewerContext.close(); await anonymous.close() }
})
