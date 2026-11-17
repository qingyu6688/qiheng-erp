import { expect, test } from '@playwright/test'
import type { Page, Locator } from '@playwright/test'
import { env } from 'node:process'

async function login(page: Page, username: string): Promise<void> {
  if (!env.ERP_E2E_PASSWORD || !env.ERP_E2E_TENANT_CODE?.startsWith('E2E-'))
    throw new Error('请使用隔离企业的浏览器演练命令')
  await page.addLocatorHandler(
    page.getByRole('button', { name: '我已阅读并确认', exact: true }),
    async (button) => {
      await button.click()
    },
  )
  await page.goto('/login')
  await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE)
  await page.getByLabel('用户名').fill(username)
  await page.locator('#password').fill(env.ERP_E2E_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
}
function field(scope: Locator, name: string): Locator {
  return scope.locator('.el-form-item').filter({
    has: scope.page().locator('.el-form-item__label', { hasText: new RegExp('^' + name + '$') }),
  })
}
async function select(page: Page, control: Locator, option: string): Promise<void> {
  await expect(control.getByRole('combobox')).toBeEnabled()
  await control.click()
  await expect(control.getByRole('combobox')).toHaveAttribute('aria-expanded', 'true')
  await page.getByRole('option', { name: option, exact: true }).click()
}

test('SIMPLE 设计发布绑定、双用户审批、单据进度和我的申请使用真实服务', async ({
  page,
  browser,
}, testInfo) => {
  test.setTimeout(180_000)
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('dialog', (dialog) => void dialog.accept())
  const reviewerContext = await browser.newContext({ baseURL: 'http://127.0.0.1:15173' }),
    reviewer = await reviewerContext.newPage()
  const suffix = Date.now().toString().slice(-8),
    name = '销售复核' + suffix
  let bindingId: string | undefined,
    company = '',
    modelId: string | undefined
  try {
    await login(page, 'admin')
    await login(reviewer, 'reviewer')
    const profile = (await (await page.request.get('/api/v1/auth/me')).json()).data as {
      companies: { id: string }[]
    }
    company = profile.companies[0]!.id
    await page.goto('/workflow/models')
    await page.getByRole('button', { name: '新建流程', exact: true }).click()
    const editor = page.getByRole('dialog', { name: '新建流程', exact: true })
    await field(editor, '流程编码')
      .getByRole('textbox')
      .fill('E2E' + suffix)
    await field(editor, '流程名称').getByRole('textbox').fill(name)
    await editor.locator('.flow-node').filter({ hasText: '人工审批' }).click()
    const settings = page.getByRole('dialog', { name: '人工审批设置', exact: true })
    await field(settings, '节点名称').getByRole('textbox').fill('业务复核')
    await select(page, field(settings, '指定用户').locator('.el-select'), '验收复核员')
    await settings.getByRole('button', { name: '完成配置', exact: true }).click()
    await expect(settings).toBeHidden()
    await page.screenshot({ path: testInfo.outputPath('simple-designer.png'), fullPage: true })
    await editor.getByRole('button', { name: '保存草稿', exact: true }).click()
    await expect(editor).toBeHidden()
    const model = page.getByRole('row').filter({ hasText: name })
    await model.getByRole('button', { name: '发布新版本', exact: true }).click()
    await expect(model).toContainText('已发布')
    const models = (
      await (
        await page.request.get('/api/v1/workflow/models', { headers: { 'X-Company-Id': company } })
      ).json()
    ).data as { id: string; name: string }[]
    modelId = models.find((item) => item.name === name)!.id
    await page.getByRole('button', { name: '新增绑定', exact: true }).click()
    const binding = page.getByRole('dialog', { name: '单据流程绑定', exact: true })
    await select(page, field(binding, '流程模型').locator('.el-select'), name)
    await binding.getByRole('button', { name: '保存绑定', exact: true }).click()
    await expect(binding).toBeHidden()
    const bindings = (
      await (
        await page.request.get('/api/v1/workflow/bindings', {
          headers: { 'X-Company-Id': company },
        })
      ).json()
    ).data as { id: string; modelId: string }[]
    bindingId = bindings.find((item) => item.modelId === modelId)!.id
    await page.goto('/sales-orders/new')
    await select(
      page,
      page.locator('.el-select').filter({ has: page.locator('#order-customer') }),
      '验收销售客户',
    )
    await select(
      page,
      page
        .locator('.el-select')
        .filter({ has: page.getByRole('combobox', { name: '商品1', exact: true }) }),
      'E2E-SKU 验收商品',
    )
    await select(
      page,
      page
        .locator('.el-select')
        .filter({ has: page.getByRole('combobox', { name: '仓库1', exact: true }) }),
      '验收销售仓',
    )
    await page.getByRole('textbox', { name: '数量1', exact: true }).fill('1')
    await page.getByRole('textbox', { name: '单价1', exact: true }).fill('100')
    await page.getByRole('button', { name: '保存订单', exact: true }).click()
    await expect(page).toHaveURL(/\/sales-orders\/[0-9]+$/)
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(/^SO-.*-[0-9]{6}-[0-9]{6}$/)
    const orderPath = new URL(page.url()).pathname,
      orderNo = await page.getByRole('heading', { level: 1 }).innerText()
    await page.getByRole('button', { name: '提交', exact: true }).click()
    const submit = page.getByRole('dialog', { name: '提交', exact: true })
    await submit.getByRole('button', { name: '确认提交', exact: true }).click()
    await expect(submit).toBeHidden()
    await expect(page.getByRole('heading', { name: '审批记录', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '审批通过', exact: true })).toHaveCount(0)
    await reviewer.goto('/workflow/todo')
    const todo = reviewer.getByRole('row').filter({ hasText: orderNo })
    await expect(todo).toBeVisible()
    await todo.getByRole('button', { name: '查看并处理', exact: true }).click()
    await reviewer
      .locator('.flow-task-action')
      .getByRole('button', { name: '同意', exact: true })
      .click()
    const decision = reviewer.getByRole('dialog', { name: '同意', exact: true })
    await decision.getByRole('textbox').fill('浏览器人工复核通过')
    await decision.getByRole('button', { name: '确认同意', exact: true }).click()
    await expect(decision).toBeHidden()
    await expect(reviewer.locator('.flow-history')).toContainText('业务审批完成')
    await page.goto(orderPath)
    await expect(page.locator('.sales-detail-header .el-tag').first()).toHaveText('已批准')
    await expect(page.locator('.flow-progress')).toContainText('浏览器人工复核通过')
    await page.screenshot({ path: testInfo.outputPath('workflow-approved.png'), fullPage: true })
    await page.goto('/workflow/mine')
    await expect(page.getByRole('row').filter({ hasText: orderNo })).toContainText('已通过')
    expect(errors).toEqual([])
  } finally {
    if (bindingId) {
      const csrf =
        (await page.context().cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')?.value ?? ''
      const response = await page.request.put('/api/v1/workflow/bindings/' + bindingId, {
        headers: {
          'X-Company-Id': company,
          'X-XSRF-TOKEN': csrf,
          'Idempotency-Key': crypto.randomUUID(),
        },
        data: {
          companyId: company,
          businessType: 'sales_order',
          modelId,
          priority: 0,
          enabled: false,
          version: 0,
        },
      })
      expect(response.status()).toBe(200)
    }
    await reviewerContext.close()
  }
})

test('M14 通用申请使用 BPMN 画布与审批节点字段权限', async ({ page, browser }, testInfo) => {
  test.setTimeout(120_000)
  const reviewerContext = await browser.newContext({ baseURL: 'http://127.0.0.1:15173' })
  const reviewer = await reviewerContext.newPage()
  const suffix = Date.now().toString().slice(-8)
  const modelName = '费用审批流程' + suffix,
    formName = '费用申请表' + suffix
  try {
    await login(page, 'admin')
    await login(reviewer, 'reviewer')
    const profile = (await (await page.request.get('/api/v1/auth/me')).json()).data as {
      companies: { id: string }[]
    }
    const company = profile.companies[0]!.id
    const csrf =
      (await page.context().cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')?.value ?? ''
    const metadata = await page.request.get('/api/v1/workflow/metadata', {
      headers: { 'X-Company-Id': company },
    })
    expect(metadata.ok()).toBeTruthy()
    const users = (await metadata.json()).data.users as { id: string; name: string }[]
    const reviewerId = users.find((item) => item.name === '验收复核员')!.id
    async function post(path: string, data: unknown): Promise<{ id: string; version: number }> {
      const response = await page.request.post('/api/v1/workflow' + path, {
        headers: {
          'X-Company-Id': company,
          'X-XSRF-TOKEN': csrf,
          'Idempotency-Key': crypto.randomUUID(),
        },
        data,
      })
      expect(response.status(), await response.text()).toBe(200)
      return (await response.json()).data as { id: string; version: number }
    }
    const model = await post('/models', {
      companyId: company,
      code: 'WF14_' + suffix,
      name: modelName,
      businessType: 'wf_generic_request',
      version: 0,
      simple: {
        id: 'root',
        type: 'start',
        name: '发起人',
        child: {
          id: 'review',
          type: 'approver',
          name: '费用复核',
          multi: 'and',
          assignee: { strategy: 'users', userIds: [reviewerId], emptyHandler: 'reject' },
          fieldAccess: { amount: 'write', memo: 'hidden' },
        },
      },
    })
    await post('/models/' + model.id + '/publish', { version: model.version })
    await post('/forms', {
      code: 'expense' + suffix,
      name: formName,
      modelId: model.id,
      enabled: true,
      version: 0,
      fields: [
        { key: 'amount', label: '费用金额', type: 'number', required: true },
        { key: 'memo', label: '受限说明', type: 'text', required: false },
      ],
    })

    await page.goto('/workflow/models')
    await page
      .getByRole('row')
      .filter({ hasText: modelName })
      .getByRole('button', { name: '设计与历史', exact: true })
      .click()
    await page.locator('.flow-designer .el-switch__core').click()
    await expect(page.getByRole('switch', { name: '切换 BPMN 画布' })).toHaveAttribute(
      'aria-checked',
      'true',
    )
    const canvas = page.locator('.workflow-bpmn-canvas')
    await expect(canvas.locator('.bjs-powered-by')).toBeVisible()
    await expect(canvas.locator('[data-element-id="review"]').first()).toBeVisible()
    await canvas.screenshot({ path: testInfo.outputPath('bpmn-phase-two.png') })

    await page.goto('/workflow/start')
    await select(page, field(page.locator('main'), '申请表单').locator('.el-select'), formName)
    await page
      .getByRole('textbox', { name: '申请标题', exact: true })
      .fill('浏览器费用申请' + suffix)
    await page.getByRole('textbox', { name: '费用金额', exact: true }).fill('10.20')
    await page.getByRole('textbox', { name: '受限说明', exact: true }).fill('仅发起人可见')
    await page.getByRole('button', { name: '提交申请', exact: true }).click()
    await expect(page.getByRole('heading', { name: '申请已提交', exact: true })).toBeVisible()
    await reviewer.goto('/workflow/todo')
    await reviewer
      .getByRole('row')
      .filter({ hasText: '浏览器费用申请' + suffix })
      .getByRole('button', { name: '查看并处理', exact: true })
      .click()
    const summary = reviewer.locator('.workflow-application-detail')
    await expect(summary).toContainText('10.20')
    await expect(summary).not.toContainText('仅发起人可见')
    await reviewer
      .locator('.flow-task-action')
      .getByRole('button', { name: '同意', exact: true })
      .click()
    const decision = reviewer.getByRole('dialog', { name: '同意', exact: true })
    await decision.getByRole('textbox', { name: '费用金额', exact: true }).fill('19.05')
    await decision.getByRole('button', { name: '确认同意', exact: true }).click()
    await expect(decision).toBeHidden()
    await page.getByRole('link', { name: '查看审批进度', exact: true }).click()
    await expect(page.locator('.workflow-application-detail')).toContainText('19.05')
    await expect(page.locator('.workflow-application-detail')).toContainText('仅发起人可见')
  } finally {
    await reviewerContext.close()
  }
})
