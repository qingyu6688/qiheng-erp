import { expect, test, type Page, type Route } from '@playwright/test'

const firstCompany = '101'
const secondCompany = '102'
const timestamp = '2026-11-17T08:00:00Z'

function ok(route: Route, data: unknown): Promise<void> {
  return route.fulfill({ json: { code: 200, data, message: '操作成功' } })
}

async function loginFixture(
  page: Page,
  permissions: string[],
  allWarehouses = false,
): Promise<void> {
  await page.routeWebSocket('**/ws/**', (socket) => socket.close())
  await page.route('**/api/v1/auth/me', (route) =>
    ok(route, {
      id: '3',
      username: 'reviewer',
      displayName: '测试用户',
      mustChangePassword: false,
      permissions,
      companies: [
        { id: firstCompany, code: 'A01', name: '甲公司', allWarehouses, warehouseIds: [] },
        { id: secondCompany, code: 'A02', name: '乙公司', allWarehouses: false, warehouseIds: [] },
      ],
    }),
  )
  await page.route('**/api/v1/workbench/summary', (route) => ok(route, { blocks: [] }))
  await page.route('**/api/v1/notify/unread-count', (route) => ok(route, { total: 0 }))
  await page.route('**/api/v1/notify/important-notices', (route) => ok(route, []))
}

function sse(route: Route, events: [string, unknown][]): Promise<void> {
  return route.fulfill({
    contentType: 'text/event-stream; charset=utf-8',
    body: events
      .map(([name, data]) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`)
      .join(''),
  })
}

test('助手流式显示答案、可核对来源和原始工具结果，公司切换后清除旧会话', async ({
  page,
  context,
  baseURL,
}) => {
  await loginFixture(page, ['ai:use'])
  await context.addCookies([{ name: 'XSRF-TOKEN', value: 'ai-fixture', url: baseURL! }])
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  let requestedPreview = false
  let currentHistory: string | undefined
  let feedback = false
  await page.route('**/ai/v1/**', async (route) => {
    const request = route.request()
    const company = request.headers()['x-company-id']
    const path = new URL(request.url()).pathname
    if (path === '/ai/v1/wiki') return ok(route, [])
    if (path === '/ai/v1/assistants') {
      return ok(route, [
        {
          code: 'internal',
          name: '内部知识助手',
          toolCodes: company === firstCompany ? ['list_warehouses'] : [],
        },
      ])
    }
    if (path === '/ai/v1/conversations' && request.method() === 'GET') {
      currentHistory = company
      return ok(route, [])
    }
    if (path === '/ai/v1/conversations' && request.method() === 'POST') {
      expect(request.headers()['x-xsrf-token']).toBe('ai-fixture')
      return ok(route, {
        id: '505',
        assistantCode: 'internal',
        title: '本次测试',
        createdAt: timestamp,
      })
    }
    if (path === '/ai/v1/conversations/505/messages') {
      expect(company).toBe(firstCompany)
      expect(request.headers()['x-xsrf-token']).toBe('ai-fixture')
      const input = request.postDataJSON()
      if (input.toolCode) {
        expect(input.toolCode).toBe('list_warehouses')
        return sse(route, [
          [
            'tool_result',
            {
              code: 'list_warehouses',
              label: '可访问仓库',
              rows: [{ id: '9007199254740993', code: 'W1', name: '甲公司仓库' }],
            },
          ],
          ['token', { text: '查询完成' }],
          ['done', { messageId: '602', refused: false }],
        ])
      }
      expect(input.content).toBe('退货如何退款？')
      return sse(route, [
        [
          'citation',
          {
            number: 1,
            segmentId: 's1',
            title: '用户使用手册',
            heading: '销售退款',
            page: 2,
            wikiSlug: null,
          },
        ],
        ['token', { text: '**核对退货单** [1]\n\n' }],
        ['token', { text: '<img src="/nope" onerror="alert(1)">' }],
        ['done', { messageId: '601', refused: false }],
      ])
    }
    if (path === '/ai/v1/citations/s1') {
      requestedPreview = true
      expect(company).toBe(firstCompany)
      return ok(route, {
        ...{ segmentId: 's1', title: '用户使用手册', heading: '销售退款', page: 2 },
        content: '请先核对退货单。',
        wikiSlug: null,
        sourceIds: ['s1'],
      })
    }
    if (path === '/ai/v1/messages/601/feedback') {
      expect(request.postDataJSON().rating).toBe('up')
      feedback = true
      return ok(route, { id: '601' })
    }
    return route.abort()
  })

  await page.goto('/ai/wiki')
  await expect(page.getByRole('heading', { name: '知识百科' })).toBeVisible()
  await page.getByRole('button', { name: '打开 AI 助手' }).click()
  const drawer = page.getByRole('dialog', { name: 'AI 助手' })
  await drawer.getByRole('textbox', { name: '输入问题' }).fill('退货如何退款？')
  await drawer.getByRole('button', { name: '发送', exact: true }).click()
  await expect(drawer.locator('strong', { hasText: '核对退货单' })).toBeVisible()
  await expect(drawer.locator('.ai-markdown img')).toHaveCount(0)
  await drawer.getByRole('button', { name: '[1] 用户使用手册 · 销售退款 · 第 2 页' }).click()
  const citationDialog = page.getByRole('dialog', { name: '引用原文' })
  await expect(citationDialog.getByText('请先核对退货单。')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(citationDialog).toBeHidden()
  expect(requestedPreview).toBe(true)
  await drawer.getByRole('button', { name: '有帮助' }).click()
  await expect(drawer.getByText('已记录反馈')).toBeVisible()
  expect(feedback).toBe(true)

  await drawer.getByRole('button', { name: '查看可访问仓库' }).click()
  await expect(drawer.getByRole('cell', { name: '9007199254740993' })).toBeVisible()
  await expect(drawer.getByRole('cell', { name: '甲公司仓库' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(drawer).toBeHidden()
  await page.getByRole('combobox', { name: '当前公司' }).focus()
  await page.keyboard.press('Enter')
  await page.getByRole('option', { name: '乙公司' }).click()
  await page.getByRole('button', { name: '打开 AI 助手' }).click()
  await expect(drawer.getByRole('textbox', { name: '输入问题' })).toBeEnabled()
  expect(currentHistory).toBe(secondCompany)
  await expect(drawer.getByText('甲公司仓库')).toHaveCount(0)
  await expect(drawer.getByRole('button', { name: '查看可访问仓库' })).toHaveCount(0)
  expect(errors).toEqual([])
})

test('普通员工进入 Wiki，AI 管理路由拦截且不发起管理请求', async ({ page }) => {
  await loginFixture(page, ['ai:use'])
  let adminCalls = 0
  await page.route('**/ai/v1/admin/**', (route) => {
    adminCalls++
    return route.abort()
  })
  await page.route('**/ai/v1/wiki', (route) =>
    ok(route, [
      {
        id: 'w1',
        slug: 'refund',
        title: '销售退款指引',
        summary: '退款操作步骤',
        sourceIds: ['s1'],
      },
    ]),
  )
  await page.route('**/ai/v1/wiki/refund', (route) =>
    ok(route, {
      id: 'w1',
      slug: 'refund',
      title: '销售退款指引',
      summary: '退款操作步骤',
      content: '核对退款单以及 [[receipt]]，忽略 [外部链接](https://example.com)。',
      sourceIds: ['s1'],
    }),
  )
  await page.goto('/ai/wiki')
  await page.getByRole('link', { name: '销售退款指引' }).click()
  await expect(page.getByRole('heading', { name: '销售退款指引' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'receipt' })).toHaveAttribute(
    'href',
    '/ai/wiki/receipt',
  )
  await expect(page.getByRole('link', { name: '外部链接' })).toHaveCount(0)
  await page.goto('/ai/admin')
  await expect(page.getByRole('heading', { name: '暂无访问权限' })).toBeVisible()
  expect(adminCalls).toBe(0)
})

test('智能查数展示精确金额、候选和安全来源，公司切换后移除结果', async ({ page }) => {
  await loginFixture(page, ['ai:use', 'report:receivable_aging'])
  let sent = 0
  await page.route('**/ai/v1/**', (route) => {
    const request = route.request()
    const company = request.headers()['x-company-id']
    const path = new URL(request.url()).pathname
    if (path === '/ai/v1/wiki') return ok(route, [])
    if (path === '/ai/v1/assistants')
      return ok(route, [
        {
          code: 'internal',
          name: '业务助手',
          toolCodes: company === firstCompany ? ['receivable_balance'] : [],
        },
      ])
    if (path === '/ai/v1/conversations' && request.method() === 'GET') return ok(route, [])
    if (path === '/ai/v1/conversations')
      return ok(route, { id: '800', assistantCode: 'internal', title: null, createdAt: timestamp })
    if (path === '/ai/v1/conversations/800/messages') {
      expect(company).toBe(firstCompany)
      expect(request.postDataJSON().toolCode).toBe('auto')
      const turn = ++sent
      if (turn === 2) expect(request.postDataJSON().content).toContain('C-02')
      return sse(route, [
        ['tool_call', { code: 'receivable_balance', label: '应收余额' }],
        [
          'tool_result',
          turn === 1
            ? {
                code: 'receivable_balance',
                label: '应收余额',
                status: 'ambiguous',
                rows: [],
                candidates: [{ code: 'C-02', name: '华东商贸二部' }],
                asOf: timestamp,
              }
            : {
                code: 'receivable_balance',
                label: '应收余额',
                status: 'ok',
                asOf: timestamp,
                rows: [
                  {
                    partnerName: '华东商贸二部',
                    openAmount: '9007199254740993.01',
                    detailUrl: '//outside.test/report',
                  },
                ],
                detailUrl: '/reports/receivable-aging?partnerId=502&agingDate=2026-11-17',
              },
        ],
        ['done', { messageId: String(900 + turn), refused: false }],
      ])
    }
    return route.abort()
  })
  await page.goto('/ai/wiki')
  await page.getByRole('button', { name: '打开 AI 助手' }).click()
  const drawer = page.getByRole('dialog', { name: 'AI 助手' })
  await drawer.getByText('智能查数', { exact: true }).click()
  await drawer.getByRole('button', { name: '应收余额', exact: true }).click()
  await drawer.getByRole('button', { name: '发送', exact: true }).click()
  await expect(drawer.getByText('找到多个候选，请补充编码后重新查询。')).toBeVisible()
  await drawer.getByRole('button', { name: 'C-02 · 华东商贸二部' }).click()
  await drawer.getByRole('button', { name: '发送', exact: true }).click()
  await expect(drawer.getByRole('cell', { name: '9007199254740993.01' })).toBeVisible()
  await expect(drawer.getByText(/数据时间/).last()).toBeVisible()
  await expect(drawer.getByRole('link', { name: '查看来源' })).toHaveAttribute(
    'href',
    '/reports/receivable-aging?partnerId=502&agingDate=2026-11-17',
  )
  await expect(drawer.getByRole('link', { name: '查看单据' })).toHaveCount(0)
  await page.keyboard.press('Escape')
  await expect(drawer).toBeHidden()
  await page.getByRole('combobox', { name: '当前公司' }).focus()
  await page.keyboard.press('Enter')
  await page.getByRole('option', { name: '乙公司' }).click()
  await page.getByRole('button', { name: '打开 AI 助手' }).click()
  await expect(drawer.getByRole('textbox', { name: '输入问题' })).toBeEnabled()
  await expect(drawer.getByText('智能查数', { exact: true })).toHaveCount(0)
  await expect(drawer.getByText('9007199254740993.01')).toHaveCount(0)
})

test('评测员可用独立报表基准发起查数评测并查看逐条指标', async ({ page }) => {
  await loginFixture(page, ['ai:use', 'ai_admin:evaluate'])
  await page.route('**/ai/v1/admin/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    expect(request.headers()['x-company-id']).toBe(firstCompany)
    const rates = {
      toolSelectionRate: 1,
      parameterRate: 1,
      reportAgreementRate: 1,
      securityRate: 1,
    }
    const run = {
      id: '905',
      setCode: 'erp-data-m15',
      status: 'completed',
      total: 198,
      passed: true,
      ...rates,
    }
    if (path.endsWith('/evals'))
      return ok(route, [{ id: 'erp-data-m15', name: '智能查数', totalCases: 198 }])
    if (path.endsWith('/runs')) return ok(route, [])
    if (path.endsWith('/cases'))
      return ok(route, [
        {
          id: 'stock-1',
          scenarioId: 'stock-sku',
          toolCode: 'available_stock',
          question: '查库存',
          category: 'tool',
          expectedRefusal: false,
        },
      ])
    if (path.endsWith('/run')) {
      expect(request.postDataJSON()).toEqual({
        reportBaseline: { 'stock-sku': { status: 'ok', items: [{ availableQty: '10.000000' }] } },
      })
      return ok(route, { id: run.id, status: 'running' })
    }
    if (path.endsWith('/eval-runs/905'))
      return ok(route, {
        ...run,
        results: [
          {
            caseId: 'stock-1',
            category: 'tool',
            expectedRefusal: false,
            refused: false,
            toolSelected: true,
            parametersCorrect: true,
            reportAgrees: true,
          },
        ],
      })
    return route.abort()
  })
  await page.goto('/ai/evaluations')
  await expect(page.getByRole('heading', { name: 'AI 评测' })).toBeVisible()
  const submit = page.getByRole('button', { name: '运行评测' })
  await expect(submit).toBeDisabled()
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载评测用例' }).click()
  expect((await download).suggestedFilename()).toBe('智能查数评测用例.json')
  await page.getByLabel('报表基准文件').setInputFiles({
    name: '本机报表.json',
    mimeType: 'application/json',
    buffer: Buffer.from(
      JSON.stringify({ 'stock-sku': { status: 'ok', items: [{ availableQty: '10.000000' }] } }),
    ),
  })
  await expect(submit).toBeEnabled()
  page.once('dialog', (dialog) => dialog.accept())
  await submit.click()
  await expect(page.getByText('已达标')).toBeVisible()
  await expect(page.getByRole('columnheader', { name: '报表一致率' })).toBeVisible()
  await expect(page.getByRole('columnheader', { name: '与报表一致' })).toBeVisible()
})

test('问 AI 携带当前销售单 ID，并只显示 ERP 授权的发货原因与来源', async ({ page }) => {
  await loginFixture(page, ['ai:use', 'sales_order:view', 'shipment:view'])
  const orderId = '9007199254740993'
  const shipmentId = '521'
  await page.route('**/api/v1/sales-orders/**', (route) => {
    if (new URL(route.request().url()).pathname.endsWith('/reservations'))
      return ok(route, { items: [] })
    expect(route.request().method()).toBe('GET')
    return ok(route, { id: orderId, documentNo: 'SO-071', status: 'approved', allowedActions: [] })
  })
  await page.route('**/api/v1/shipments/521', (route) => {
    expect(route.request().method()).toBe('GET')
    return ok(route, { id: shipmentId, documentNo: 'SH-521', status: 'draft', allowedActions: [] })
  })
  await page.route((url) => url.pathname === '/api/v1/attachments', (route) => {
    const ownerId = new URL(route.request().url()).searchParams.get('ownerId')
    expect(route.request().method()).toBe('GET')
    expect([orderId, shipmentId]).toContain(ownerId)
    return ok(route, [])
  })
  await page.route('**/ai/v1/assistants', (route) =>
    ok(route, [{ code: 'internal', name: '内部助手', toolCodes: ['document_explain'] }]),
  )
  await page.route('**/ai/v1/conversations', (route) => {
    expect(route.request().method()).toBe('POST')
    return ok(route, { id: '904', assistantCode: 'internal', createdAt: timestamp })
  })
  await page.route('**/ai/v1/conversations/904/messages', (route) => {
    expect(route.request().headers()['x-company-id']).toBe(firstCompany)
    const input = route.request().postDataJSON()
    expect(input.content).toBe('为什么不能发货？')
    expect(input.toolCode).toBe('document_explain')
    if (input.toolArguments.documentType === 'shipment') {
      expect(input.toolArguments).toEqual({ documentType: 'shipment', documentId: shipmentId })
      return sse(route, [['error', { code: 'FORBIDDEN', message: '无权查看此发货单' }]])
    }
    expect(input.toolArguments).toEqual({ documentType: 'sales_order', documentId: orderId })
    return sse(route, [
      ['token', { text: '审核完成后重试。<img src="/invalid" onerror="alert(1)">' }],
      [
        'tool_result',
        {
          code: 'document_explain',
          label: '单据解释',
          detailUrl: `/sales-orders/${orderId}`,
          rows: [
            {
              code: 'CHANGE_PENDING',
              message: '订单变更待审核。',
              links: [
                { label: '关联发货单 SH-521', detailUrl: '/shipments/521' },
                { label: '外部地址', detailUrl: 'https://example.com/secret' },
                { label: '无关后台入口', detailUrl: '/admin/ai' },
              ],
            },
          ],
          summary: {
            documentType: 'sales_order',
            documentStatus: 'approved',
            checkPassed: false,
            allowedActions: [],
          },
        },
      ],
      ['done', { messageId: '905', refused: false }],
    ])
  })
  await page.goto(`/sales-orders/${orderId}`)
  await expect(page.getByRole('heading', { name: 'SO-071' })).toBeVisible()
  await page.getByRole('button', { name: '问 AI', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '单据解释' })
  await expect(drawer.getByText('当前单据 · SO-071')).toBeVisible()
  await drawer.getByRole('button', { name: '为什么不能发货？' }).click()
  await expect(drawer.getByRole('list', { name: '业务校验原因' })).toContainText('订单变更待审核。')
  await expect(drawer.getByText('审核完成后重试。', { exact: false })).toContainText('<img')
  await expect(drawer.locator('img')).toHaveCount(0)
  await expect(drawer.getByRole('link', { name: '外部地址' })).toHaveCount(0)
  await expect(drawer.getByRole('link', { name: '无关后台入口' })).toHaveCount(0)
  await drawer.getByRole('link', { name: '关联发货单 SH-521' }).click()
  await expect(drawer).toBeHidden()
  await expect(page.getByRole('heading', { name: 'SH-521' })).toBeVisible()
  await page.getByRole('button', { name: '问 AI', exact: true }).click()
  await drawer.getByRole('button', { name: '为什么不能发货？' }).click()
  await expect(drawer.getByRole('alert')).toContainText('无权查看此发货单')
  await expect(drawer.getByText('订单变更待审核。')).toHaveCount(0)
})

test('关账解释按来源期间定位，切换公司后清除原因和期间上下文', async ({ page }) => {
  await loginFixture(page, ['ai:use', 'period:view', 'period:close'], true)
  const first = {
    id: '44',
    period: '2026-10',
    status: 'open',
    startDate: '2026-10-01',
    endDate: '2026-10-31',
  }
  const second = {
    id: '45',
    period: '2026-11',
    status: 'open',
    startDate: '2026-11-01',
    endDate: '2026-11-30',
  }
  await page.route('**/api/v1/accounting-periods**', (route) => {
    const inFirstCompany = route.request().headers()['x-company-id'] === firstCompany
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/close-check')) return ok(route, { passed: false, items: [] })
    expect(route.request().method()).toBe('GET')
    if (!inFirstCompany) return ok(route, { items: [], nextCursor: null })
    return url.searchParams.has('cursor')
      ? ok(route, { items: [first], nextCursor: null })
      : ok(route, { items: [second], nextCursor: '50' })
  })
  await page.route('**/ai/v1/assistants', (route) =>
    ok(route, [{ code: 'internal', name: '内部助手', toolCodes: ['document_explain'] }]),
  )
  await page.route('**/ai/v1/conversations', (route) =>
    ok(route, { id: '906', assistantCode: 'internal', createdAt: timestamp }),
  )
  await page.route('**/ai/v1/conversations/906/messages', (route) => {
    expect(route.request().postDataJSON()).toEqual({
      content: '关账为什么不通过？',
      toolCode: 'document_explain',
      toolArguments: { documentType: 'accounting_period', documentId: second.id },
    })
    return sse(route, [
      [
        'tool_result',
        {
          code: 'document_explain',
          label: '单据解释',
          detailUrl: '/accounting-periods?id=45',
          rows: [
            {
              code: 'PREVIOUS_PERIOD_OPEN',
              message: '请先关闭更早的会计期间。',
              detailUrl: '/accounting-periods?id=44',
            },
          ],
          summary: {
            documentType: 'accounting_period',
            documentStatus: 'open',
            checkPassed: false,
            allowedActions: ['close_check'],
          },
        },
      ],
      ['done', { messageId: '907', refused: false }],
    ])
  })
  await page.goto('/accounting-periods?id=45')
  await expect(page.getByRole('heading', { name: '2026-11 · 关账检查' })).toBeVisible()
  await page.getByRole('button', { name: '问 AI' }).click()
  const drawer = page.getByRole('dialog', { name: '单据解释' })
  await drawer.getByRole('button', { name: '关账为什么不通过？' }).click()
  await expect(drawer.getByRole('list', { name: '业务校验原因' })).toContainText(
    '请先关闭更早的会计期间。',
  )
  await drawer.getByRole('link', { name: '查看关联事项' }).click()
  await expect(page).toHaveURL(/accounting-periods\?id=44$/)
  await expect(page.getByRole('heading', { name: '2026-10 · 关账检查' })).toBeVisible()
  await page.getByRole('button', { name: '问 AI' }).click()
  await expect(drawer.getByText('当前单据 · 2026-10')).toBeVisible()
  await expect(drawer.getByText('请先关闭更早的会计期间。')).toHaveCount(0)
  await page.keyboard.press('Escape')
  await page.getByRole('combobox', { name: '当前公司' }).focus()
  await page.keyboard.press('Enter')
  await page.getByRole('option', { name: '乙公司' }).click()
  await expect(drawer).toBeHidden()
  await expect(page.getByRole('button', { name: '问 AI' })).toHaveCount(0)
  await expect(page.getByText('当前公司没有可查看的指定期间。')).toBeVisible()
})

test('评测员上传单据解释基准并查看原因和来源评分', async ({ page }) => {
  await loginFixture(page, ['ai_admin:evaluate'])
  const setId = 'erp-explain-m15'
  const documentBaseline = {
    'shipment-change': {
      documentId: '8',
      documentStatus: 'approved',
      allowedActions: [],
      checkPassed: false,
      blockerCodes: ['CHANGE_PENDING'],
      sourcePaths: ['/sales-orders/8'],
    },
  }
  const run = {
    id: '913',
    status: 'completed',
    total: 22,
    correctRate: 1,
    citationRate: 1,
    refusalRate: 1,
    securityRate: 1,
    passed: true,
  }
  let submitted = false
  await page.route('**/ai/v1/admin/**', (route) => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/evals')) return ok(route, [{ id: setId, name: '单据解释', totalCases: 22 }])
    if (path.endsWith('/runs')) return ok(route, submitted ? [run] : [])
    if (path.endsWith('/run')) {
      expect(route.request().postDataJSON()).toEqual({ documentBaseline })
      submitted = true
      return ok(route, run)
    }
    if (path.endsWith('/eval-runs/913'))
      return ok(route, {
        ...run,
        results: [
          {
            caseId: 'explain-shipment-change',
            category: 'shipping',
            correct: true,
            citationCorrect: true,
            expectedRefusal: false,
            refused: false,
          },
        ],
      })
    return route.abort()
  })
  await page.goto('/ai/evaluations')
  const runButton = page.getByRole('button', { name: '运行评测' })
  await expect(runButton).toBeDisabled()
  await page.getByLabel('单据解释基准文件').setInputFiles({
    name: '单据解释基准.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(documentBaseline)),
  })
  await expect(runButton).toBeEnabled()
  page.once('dialog', (dialog) => dialog.accept())
  await runButton.click()
  await expect(page.getByRole('columnheader', { name: '来源一致率' })).toBeVisible()
  await expect(page.getByText('已达标')).toBeVisible()
  await expect(page.getByRole('cell', { name: 'explain-shipment-change' })).toBeVisible()
})

test('Wiki 审核保留编辑后预览的正文和待审稿版本，审核权限不开放模型管理', async ({ page }) => {
  await loginFixture(page, ['ai_admin:review'])
  const item = {
    id: 'w1',
    slug: 'refund',
    title: '销售退款指引',
    summary: '退款操作步骤',
    status: 'pending_review',
    stale: false,
    currentRevision: { id: 'r1', content: '当前版本正文', sourceIds: ['s1'] },
    pendingRevision: { id: 'r2', content: '待审核正文 [1]', sourceIds: ['s1'] },
  }
  let published = false
  await page.route('**/ai/v1/admin/wiki/**', (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/ai/v1/admin/wiki/review') return ok(route, [item])
    if (path === '/ai/v1/admin/wiki/lint') return ok(route, [])
    if (path === '/ai/v1/admin/wiki/enrich-next') return ok(route, { status: 'idle' })
    if (path === '/ai/v1/admin/wiki/w1/review') return ok(route, item)
    if (path === '/ai/v1/admin/wiki/w1/publish') {
      expect(route.request().postDataJSON()).toEqual({
        revisionId: 'r2',
        content: '修订后的正文 [1]',
      })
      published = true
      return ok(route, item)
    }
    return route.abort()
  })
  await page.goto('/ai/wiki-review')
  await expect(page.getByRole('button', { name: '打开 AI 助手' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '导入初始资料' })).toHaveCount(0)
  await page.getByRole('button', { name: '查看稿件' }).click()
  await expect(page.getByText('当前版本正文')).toBeVisible()
  await page.getByRole('button', { name: '编辑新稿' }).click()
  await page.getByRole('textbox', { name: '修订正文' }).fill('修订后的正文 [1]')
  await page.getByRole('button', { name: '预览修订' }).click()
  await expect(page.getByText('修订后的正文 [1]')).toBeVisible()
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: '发布', exact: true }).click()
  await expect(page.getByText('已发布页面。')).toBeVisible()
  expect(published).toBe(true)
})

test('知识库只读管理员可检查分段和召回，不能上传或改动资料', async ({ page }) => {
  await loginFixture(page, ['ai_admin:view'])
  const evidence = {
    segmentId: 's1',
    title: '操作手册',
    heading: '退款',
    content: '核对销售退货单。',
    page: 3,
    wikiSlug: null,
    sourceIds: ['s1'],
  }
  let testedRecall = false
  await page.route('**/ai/v1/admin/**', (route) => {
    const request = route.request()
    const url = new URL(request.url())
    expect(request.headers()['x-company-id']).toBe(firstCompany)
    if (url.pathname === '/ai/v1/admin/libraries')
      return ok(route, [
        {
          id: 'lib1',
          name: '业务手册',
          description: '标准流程',
          visibility: 'all',
          targetIds: [],
          status: 'active',
        },
      ])
    if (url.pathname === '/ai/v1/admin/documents') {
      expect(request.method()).toBe('GET')
      expect(url.searchParams.get('libraryId')).toBe('lib1')
      return ok(route, [
        {
          id: 'doc1',
          libraryId: 'lib1',
          title: '操作手册',
          versionNo: 1,
          status: 'indexed',
          updatedAt: timestamp,
        },
      ])
    }
    if (url.pathname === '/ai/v1/admin/documents/doc1/segments')
      return ok(route, [
        {
          id: 's1',
          documentId: 'doc1',
          seq: 1,
          heading: '退款',
          pageFrom: 3,
          pageTo: 3,
          charCount: 8,
          status: 'indexed',
          content: evidence.content,
        },
      ])
    if (url.pathname === '/ai/v1/admin/libraries/search') {
      expect(request.postDataJSON()).toEqual({ query: '退款', libraryIds: ['lib1'] })
      testedRecall = true
      return ok(route, [{ evidence, score: 0.95, selected: true }])
    }
    return route.abort()
  })
  await page.goto('/ai/knowledge')
  await expect(page.getByRole('heading', { name: '知识库管理' })).toBeVisible()
  await expect(page.getByRole('button', { name: '新建知识库' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '上传资料' })).toHaveCount(0)
  await page.getByRole('button', { name: '预览分段' }).click()
  await expect(page.getByText(evidence.content)).toBeVisible()
  await expect(page.getByRole('button', { name: '确认调整' })).toHaveCount(0)
  await page.getByRole('textbox', { name: '召回测试问题' }).fill('退款')
  await page.getByRole('button', { name: '测试召回' }).click()
  await expect(page.getByText('分数 0.95 · 已选入上下文')).toBeVisible()
  expect(testedRecall).toBe(true)
})
