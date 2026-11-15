import { expect, test } from '@playwright/test'
import { env } from 'node:process'
test('失败事件重放完成并按操作代码查到变更审计', async ({ page }, testInfo) => {
  test.setTimeout(60_000)
  if (!env.ERP_E2E_PASSWORD || !/^E2E-[a-f0-9]{12}$/.test(env.ERP_E2E_TENANT_CODE ?? '')) throw new Error('审计验收须使用随机测试企业和临时口令')
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message))
  await page.goto('/login'); await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE!); await page.getByLabel('用户名').fill('admin'); await page.locator('#password').fill(env.ERP_E2E_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click(); await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
  await page.goto('/audit-logs'); await page.locator('.el-radio-button').filter({ hasText: '后台事件' }).click()
  await expect(page.getByRole('heading', { name: '异常事件处理', exact: true })).toBeVisible()
  const row = page.getByRole('row').filter({ hasText: '浏览器验收待重放' }); await expect(row).toHaveCount(1)
  const eventId = (await row.locator('td').first().innerText()).trim()
  await row.getByRole('button', { name: '重放事件', exact: true }).click()
  const replay = page.waitForResponse(response => response.url().endsWith('/' + eventId + '/replay') && response.request().method() === 'POST')
  await page.getByRole('button', { name: '重放', exact: true }).click(); expect((await replay).status()).toBe(200)
  const select = page.locator('#event-status'); await page.locator('.el-select').filter({ has: select }).click(); const list = await select.getAttribute('aria-controls'); if (!list) throw new Error('事件状态选项没有关联列表')
  await page.locator('[role="listbox"][id=' + JSON.stringify(list) + ']').getByRole('option', { name: '已完成', exact: true }).click()
  async function queryEvents(label: string): Promise<void> {
    const response = page.waitForResponse(item => item.url().includes('/api/v1/admin/outbox-events?') && item.request().method() === 'GET')
    await page.getByRole('button', { name: label, exact: true }).click(); expect((await response).status()).toBe(200)
    await expect(page.getByRole('button', { name: '查询', exact: true })).toBeEnabled()
  }
  await expect.poll(async () => {
    await queryEvents('查询')
    for (let index = 0; index < 10; index++) {
      if (await page.getByRole('row').filter({ hasText: eventId }).count()) return 1
      if (await page.getByRole('button', { name: '下一页', exact: true }).isDisabled()) break
      await queryEvents('下一页')
    }
    return 0
  }, { timeout: 20_000 }).toBe(1)
  await page.locator('.el-radio-button').filter({ hasText: '操作审计' }).click(); await page.locator('#audit-action').fill('outbox.replay'); await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: '后台事件 · 重放' })).toHaveCount(1)
  await page.getByRole('button', { name: '查看变更', exact: true }).click(); await expect(page.getByRole('dialog')).toContainText('pending'); await expect(page.getByRole('dialog')).toContainText(eventId)
  await page.screenshot({ path: testInfo.outputPath('audit-replay.png'), fullPage: true }); expect(errors).toEqual([])
})
