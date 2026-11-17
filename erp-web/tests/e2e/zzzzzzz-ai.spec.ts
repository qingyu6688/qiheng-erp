import { expect, test, type Page, type Route } from '@playwright/test'

const firstCompany = '101'
const secondCompany = '102'
const timestamp = '2026-11-17T08:00:00Z'

function ok(route: Route, data: unknown): Promise<void> {
  return route.fulfill({ json: { code: 200, data, message: '操作成功' } })
}

async function loginFixture(page: Page, permissions: string[]): Promise<void> {
  await page.routeWebSocket('**/ws/**', (socket) => socket.close())
  await page.route('**/api/v1/auth/me', (route) =>
    ok(route, {
      id: '3',
      username: 'reviewer',
      displayName: '测试用户',
      mustChangePassword: false,
      permissions,
      companies: [
        { id: firstCompany, code: 'A01', name: '甲公司', allWarehouses: false, warehouseIds: [] },
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
