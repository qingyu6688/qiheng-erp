import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { env } from 'node:process'
import { createHash } from 'node:crypto'

interface OnlineSessionsPage {
  items: { id: string }[]
  nextCursor: string | null
}

async function findOnlineSession(page: Page, publicId: string): Promise<void> {
  const loaded = () => page.waitForResponse((response) =>
    new URL(response.url()).pathname === '/api/v1/sys/online-users' && response.request().method() === 'GET',
  )
  let pending = loaded()
  await page.goto('/sys/online-users')
  const visited = new Set<string>()
  for (;;) {
    const response = await pending
    expect(response.status(), '在线用户查询失败').toBe(200)
    const result = (await response.json()).data as OnlineSessionsPage
    if (result.items.some((row) => row.id === publicId) || !result.nextCursor) return
    expect(visited.has(result.nextCursor), '在线用户分页游标不能重复').toBe(false)
    visited.add(result.nextCursor)
    pending = loaded()
    await page.getByRole('button', { name: '下一页', exact: true }).click()
  }
}

async function login(page: Page): Promise<void> {
  if (!env.ERP_E2E_PASSWORD || !env.ERP_E2E_TENANT_CODE?.startsWith('E2E-'))
    throw new Error('请使用隔离企业的浏览器演练命令')
  await page.goto('/login')
  await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE)
  await page.getByLabel('用户名').fill('admin')
  await page.locator('#password').fill(env.ERP_E2E_PASSWORD)
  let response = page.waitForResponse(
    (value) => value.url().endsWith('/api/v1/auth/login') && value.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '登录', exact: true }).click()
  if ((await response).status() === 429) {
    // 连续业务回归共享同一测试账号，按服务端限流等待，不放宽生产规则。
    await expect(page.getByRole('alert')).toContainText('登录操作过于频繁')
    await page.waitForTimeout(60000)
    response = page.waitForResponse(
      (value) => value.url().endsWith('/api/v1/auth/login') && value.request().method() === 'POST',
    )
    await page.getByRole('button', { name: '登录', exact: true }).click()
  }
  expect((await response).status()).toBe(200)
  await expect(page.getByRole('heading', { name: '工作台', exact: true })).toBeVisible()
}

test('M11 平台资料、任务与消息中心，两会话实时提醒和强制下线', async ({
  page,
  browser,
}, testInfo) => {
  test.setTimeout(150000)
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('dialog', (dialog) => void dialog.accept())
  const suffix = Date.now().toString().slice(-7),
    code = 'e2e_source_' + suffix,
    title = '平台公告' + suffix
  await login(page)
  await page.goto('/sys/dict-types')
  await page.getByRole('button', { name: '新增', exact: true }).click()
  await page.getByLabel('字典编码', { exact: true }).fill(code)
  await page.getByLabel('字典名称', { exact: true }).fill('测试来源' + suffix)
  await page.getByRole('button', { name: '保存', exact: true }).click()
  const type = page.locator('.el-table__body-wrapper').getByRole('row').filter({ hasText: code })
  await expect(type).toBeVisible()
  await type.getByRole('button', { name: '字典项', exact: true }).click()
  await page.getByRole('button', { name: '新增', exact: true }).click()
  await page.getByLabel('字典值', { exact: true }).fill('expo')
  await page.getByLabel('显示名称', { exact: true }).fill('测试展会')
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(
    page.locator('.el-table__body-wrapper').getByRole('row').filter({ hasText: '测试展会' }),
  ).toBeVisible()
  await page.goto('/sys/configs')
  await page.getByRole('button', { name: '新增', exact: true }).click()
  await page.getByLabel('参数编码', { exact: true }).fill('e2e.page_size_' + suffix)
  await page.getByLabel('说明', { exact: true }).fill('浏览器验收参数')
  await page.getByLabel('参数值（敏感参数需重新填写）', { exact: true }).fill('20')
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(
    page
      .locator('.el-table__body-wrapper')
      .getByRole('row')
      .filter({ hasText: 'e2e.page_size_' + suffix }),
  ).toContainText('20')
  await page.goto('/sys/jobs')
  const task = page
    .locator('.el-table__body-wrapper')
    .getByRole('row')
    .filter({ hasText: 'platform.cleanup' })
  await expect(task).toBeVisible()
  await task.getByRole('button', { name: '执行一次', exact: true }).click()
  await expect(page.getByText('任务已排队，可查看执行记录')).toBeVisible()
  await task.getByRole('button', { name: '执行记录', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '任务执行记录' })).toContainText(
    /成功|执行中|等待处理/,
  )
  await page.getByRole('dialog', { name: '任务执行记录' }).locator('.el-dialog__headerbtn').click()
  const secondContext = await browser.newContext({
      baseURL: 'http://127.0.0.1:15173',
      locale: 'zh-CN',
    }),
    second = await secondContext.newPage()
  second.on('pageerror', (error) => errors.push(error.message))
  try {
    await login(second)
    await second.goto('/messages')
    await expect(second.getByRole('heading', { name: '消息中心', exact: true })).toBeVisible()
    await page.goto('/sys/notices')
    await page.getByRole('button', { name: '新增', exact: true }).click()
    await page.getByLabel('公告标题', { exact: true }).fill(title)
    await page.getByLabel('公告正文', { exact: true }).fill('本地演练公告，不向外部渠道发送。')
    await page
      .locator('.el-select')
      .filter({ has: page.locator('#field-level') })
      .click()
    await page.getByRole('option', { name: '重要公告，登录后确认', exact: true }).click()
    await page.getByRole('button', { name: '保存', exact: true }).click()
    const notice = page
      .locator('.el-table__body-wrapper')
      .getByRole('row')
      .filter({ hasText: title })
    await expect(notice).toBeVisible()
    await notice.getByRole('button', { name: '发布', exact: true }).click()
    await expect(second.getByRole('dialog', { name: title, exact: true })).toBeVisible({
      timeout: 2000,
    })
    await expect(page.getByRole('dialog', { name: title, exact: true })).toBeVisible()
    await second.screenshot({
      path: testInfo.outputPath('消息中心实时公告.png'),
      fullPage: true,
      animations: 'disabled',
    })
    await second.getByRole('button', { name: '我已阅读并确认', exact: true }).click()
    await expect(second.getByRole('dialog', { name: title, exact: true })).toHaveCount(0)
    await expect(page.getByRole('dialog', { name: title, exact: true })).toHaveCount(0)
    await expect(second.getByRole('alert')).toHaveCount(0)
    await notice.getByRole('button', { name: '撤回', exact: true }).click()
    await expect(notice.getByText('已撤回', { exact: true })).toBeVisible()
    for (const [path, heading] of [
      ['notify-templates', '消息模板'],
      ['notify-send-logs', '消息发送日志'],
      ['notify-robot', '企业微信机器人'],
      ['login-logs', '登录日志'],
      ['api-access-logs', 'API 访问日志'],
      ['api-error-logs', 'API 异常日志'],
    ] as const) {
      await page.goto('/sys/' + path)
      await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible()
      await expect(page.getByRole('alert').filter({ hasText: /失败|没有|无权/ })).toHaveCount(0)
    }
    const sessionCookie = (await secondContext.cookies()).find(
      (cookie) => cookie.name === 'ERPSESSION',
    )
    if (!sessionCookie) throw new Error('第二会话未创建')
    const publicId = createHash('sha256')
      .update(Buffer.from(sessionCookie.value, 'base64').toString('utf8'))
      .digest('hex')
    await findOnlineSession(page, publicId)
    const rows = page
      .locator('.el-table__body-wrapper')
      .getByRole('row')
      .filter({ hasText: publicId })
    await expect(rows).toHaveCount(1)
    expect(await rows.boundingBox().then((box) => box?.height ?? 0)).toBeLessThan(100)
    await page.screenshot({
      path: testInfo.outputPath('在线会话管理.png'),
      fullPage: true,
      animations: 'disabled',
    })
    await rows.first().getByRole('button', { name: '强制下线', exact: true }).click()
    await expect(second).toHaveURL(/\/login(?:\?|$)/, { timeout: 10000 })
    await expect(page).toHaveURL(/\/sys\/online-users$/)
    expect(errors).toEqual([])
  } finally {
    await secondContext.close()
  }
})
