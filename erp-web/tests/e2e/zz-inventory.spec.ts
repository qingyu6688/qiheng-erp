import { expect, test } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { env } from 'node:process'

const warehouseA = '库存验收甲仓'
const warehouseB = '库存验收乙仓'
const sku = 'E2E-M6-SKU 库存验收商品'

async function choose(page: Page, field: Locator, option: string | RegExp): Promise<void> {
  await page.locator('.el-select').filter({ has: field }).click()
  await expect(field).toHaveAttribute('aria-controls', /.+/)
  const listboxId = await field.getAttribute('aria-controls')
  if (!listboxId) throw new Error('下拉框没有关联选项列表')
  await page.locator('[role="listbox"][id=' + JSON.stringify(listboxId) + ']')
    .getByRole('option', { name: option, exact: typeof option === 'string' }).click()
}
async function selectId(page: Page, id: string, option: string | RegExp): Promise<void> {
  await choose(page, page.locator('#' + id), option)
}
async function selectName(page: Page, name: string, option: string | RegExp): Promise<void> {
  await choose(page, page.getByRole('combobox', { name, exact: true }), option)
}
async function login(page: Page, username: string): Promise<void> {
  if (!env.ERP_E2E_PASSWORD || !/^E2E-[a-f0-9]{12}$/.test(env.ERP_E2E_TENANT_CODE ?? '')) {
    throw new Error('库存浏览器验收须使用项目脚本的随机测试企业和临时口令')
  }
  await page.goto('/login')
  await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE!)
  await page.getByLabel('用户名').fill(username)
  await page.locator('#password').fill(env.ERP_E2E_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
}
async function save(page: Page, kind: string, label: string): Promise<string> {
  await page.getByRole('dialog').getByRole('button', { name: '保存' + label, exact: true }).click()
  await expect(page).toHaveURL(new RegExp('/' + kind + '/[0-9]+$'))
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(/-[0-9]{6}-[0-9]{6}$/)
  return new URL(page.url()).pathname
}
async function command(page: Page, label: string, fill?: (dialog: Locator) => Promise<void>, confirm?: string): Promise<void> {
  await page.getByRole('button', { name: label, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: label, exact: true })
  if (fill) await fill(dialog)
  await dialog.getByRole('button', { name: confirm ?? (label.startsWith('确认') ? label : '确认' + label), exact: true }).click()
  await expect(dialog).toBeHidden()
}
async function status(page: Page, text: string): Promise<void> {
  await expect(page.locator('.sales-detail-header .el-tag').first()).toHaveText(text)
}
async function approveAndPost(page: Page, reviewer: Page, path: string): Promise<void> {
  await reviewer.goto(path)
  await command(reviewer, '审批通过')
  await page.reload()
  await command(page, '过账')
  await status(page, '已过账')
}
async function adjustment(page: Page, reviewer: Page, type: string, qty: string, total?: string): Promise<string> {
  await page.goto('/stock-adjustments')
  await page.getByRole('button', { name: '新建库存调整单', exact: true }).click()
  await selectName(page, '调整类型', type)
  await selectId(page, 'purchase-warehouse', warehouseA)
  await page.locator('#purchase-reason').fill('库存作业浏览器验收')
  await selectName(page, '采购商品1', sku)
  await expect(page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: '采购单位1', exact: true }) })).toContainText('库存验收件')
  await page.getByRole('textbox', { name: '采购数量1', exact: true }).fill(qty)
  if (total) await page.getByRole('textbox', { name: '调整总成本1', exact: true }).fill(total)
  const path = await save(page, 'stock-adjustments', '库存调整单')
  await command(page, '提交')
  await expect(page.getByRole('button', { name: '审批通过', exact: true })).toHaveCount(0)
  await approveAndPost(page, reviewer, path)
  return path
}
async function balance(page: Page, warehouse: string, qty: string, blocked: string): Promise<void> {
  await page.goto('/inventory-balances')
  await page.locator('#inventory-search').fill('E2E-M6-SKU')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  const row = page.getByRole('row').filter({ has: page.getByRole('cell', { name: warehouse, exact: true }) })
  await expect(row.getByRole('cell').nth(3)).toHaveText(qty)
  await expect(row.getByRole('cell').nth(5)).toHaveText(blocked)
}

test('调拨在途差异、盘点保留期间收发、库存调整冲销及关账重开', async ({ page, browser }, testInfo) => {
  test.setTimeout(240_000)
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  const context = await browser.newContext({ baseURL: 'http://127.0.0.1:15173', locale: 'zh-CN' })
  const reviewer = await context.newPage()
  reviewer.on('pageerror', error => errors.push(error.message))
  try {
    await login(page, 'admin')
    await login(reviewer, 'reviewer')
    await adjustment(page, reviewer, '其他入库', '10', '100')

    await page.goto('/stock-transfers')
    await page.getByRole('button', { name: '新建调拨单', exact: true }).click()
    await selectId(page, 'stock-from-warehouse', warehouseA)
    await selectId(page, 'stock-to-warehouse', warehouseB)
    await selectName(page, '库存商品1', sku)
    await expect(page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: '调拨单位1', exact: true }) })).toContainText('库存验收件')
    await page.getByRole('textbox', { name: '调拨数量1', exact: true }).fill('4')
    const transferPath = await save(page, 'stock-transfers', '调拨单')
    await command(page, '提交')
    await command(page, '确认调出')
    await status(page, '在途')
    await page.goto(transferPath)
    await status(page, '在途')
    await command(page, '登记调入', async dialog => {
      await dialog.getByRole('textbox', { name: '调入实收1', exact: true }).fill('3')
      await expect(dialog.getByRole('checkbox')).toBeChecked()
    })
    await status(page, '调入差异待处理')
    await command(page, '处理调拨差异', async dialog => {
      await selectName(page, '调拨差异处理1', '确认运输损失')
      await dialog.locator('#stock-command-reason').fill('运输途中短少一件')
    })
    await status(page, '已调入')
    await balance(page, warehouseA, '6.000000', '0.000000')
    await balance(page, warehouseB, '3.000000', '0.000000')

    await page.goto('/stock-counts')
    await page.getByRole('button', { name: '新建盘点单', exact: true }).click()
    await selectId(page, 'stock-warehouse', warehouseA)
    await selectName(page, '库存商品1', sku)
    const countPath = await save(page, 'stock-counts', '盘点单')
    await command(page, '开始盘点')
    await status(page, '盘点中')
    await expect(page.getByRole('cell', { name: '6.000000', exact: true })).toBeVisible()
    await adjustment(page, reviewer, '其他入库', '2', '20')
    await page.goto(countPath)
    await command(page, '录入实盘', async dialog => {
      await dialog.getByRole('textbox', { name: '实盘数量1', exact: true }).fill('5')
    }, '保存实盘')
    await command(page, '提交')
    await approveAndPost(page, reviewer, countPath)
    await balance(page, warehouseA, '7.000000', '0.000000')

    await adjustment(page, reviewer, '冻结', '2')
    await balance(page, warehouseA, '7.000000', '2.000000')
    await adjustment(page, reviewer, '解冻', '1')
    await balance(page, warehouseA, '7.000000', '1.000000')
    await adjustment(page, reviewer, '报废', '1')
    await balance(page, warehouseA, '6.000000', '0.000000')

    const mistakenPath = await adjustment(page, reviewer, '其他入库', '1', '10')
    const mistakenNo = await page.getByRole('heading', { level: 1 }).innerText()
    await page.goto('/document-reversals')
    await page.getByRole('button', { name: '新建冲销单', exact: true }).click()
    await selectId(page, 'stock-source-type', '库存调整单')
    await selectId(page, 'stock-source', new RegExp(mistakenNo))
    await page.locator('#stock-reason').fill('验收误录一件，整单冲销')
    const reversalPath = await save(page, 'document-reversals', '冲销单')
    await expect(page.getByRole('button', { name: '审批通过', exact: true })).toHaveCount(0)
    await approveAndPost(page, reviewer, reversalPath)
    await page.goto(mistakenPath)
    await status(page, '已冲销')
    await balance(page, warehouseA, '6.000000', '0.000000')
    await balance(page, warehouseB, '3.000000', '0.000000')

    await page.goto('/accounting-periods')
    const month = new Intl.DateTimeFormat('sv-SE', { year: 'numeric', month: '2-digit', timeZone: 'Asia/Shanghai' }).format(new Date()).replace('-', '')
    const periodRow = page.getByRole('row').filter({ has: page.getByRole('cell', { name: month, exact: true }) })
    await periodRow.getByRole('button', { name: '关账预检查', exact: true }).click()
    const inspection = page.getByRole('dialog', { name: '关账预检查', exact: true })
    await expect(inspection.locator('.el-table__body .el-tag')).toHaveText(['通过', '通过', '通过', '通过', '通过'])
    await inspection.getByRole('button', { name: '关闭', exact: true }).click()
    await periodRow.getByRole('button', { name: '关账', exact: true }).click()
    const closing = page.getByRole('dialog', { name: '关闭会计期间', exact: true })
    await expect(closing.getByRole('button', { name: '确认关账', exact: true })).toBeEnabled()
    await closing.getByRole('button', { name: '确认关账', exact: true }).click()
    await expect(closing).toBeHidden()
    await expect(periodRow.getByRole('button', { name: '重新打开', exact: true })).toBeVisible()
    await periodRow.getByRole('button', { name: '重新打开', exact: true }).click()
    const reopening = page.getByRole('dialog', { name: '重新打开期间', exact: true })
    await reopening.locator('#period-reopen-reason').fill('验收后恢复开放期间')
    await reopening.getByRole('button', { name: '确认重新打开', exact: true }).click()
    await expect(reopening).toBeHidden()
    await expect(periodRow.getByRole('button', { name: '关账', exact: true })).toBeVisible()
    await page.goto(reversalPath)
    await status(page, '已过账')
    await expect(page.locator('.sales-detail-header .el-tag').first()).toHaveCSS('transform', 'none')
    await page.screenshot({ path: testInfo.outputPath('inventory-completed.png'), fullPage: true })
    expect(errors).toEqual([])
  } finally {
    await context.close()
  }
})
