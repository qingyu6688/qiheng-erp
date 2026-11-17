import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { readAiEvents, sendAiMessage } from '../src/api/ai'
import { isCancelled, renewSessionScope, setCompanyScope } from '../src/api/http'
import { renderAiMarkdown } from '../src/utils/ai-markdown'

const encoded = new TextEncoder()
const current = '9007199254740993'
let controller: AbortController

beforeEach(() => {
  renewSessionScope()
  setCompanyScope(current)
  controller = new AbortController()
})

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
  setCompanyScope(null)
  vi.unstubAllGlobals()
})

function chunks(...values: Uint8Array[]): ReadableStream<Uint8Array> {
  return new ReadableStream({
    start(stream) {
      values.forEach((value) => stream.enqueue(value))
      stream.close()
    },
  })
}

it('处理多字节字符、拆包回车换行、多行数据和终止事件', async () => {
  const frames = encoded.encode(
    ': 心跳\r\nevent: token\r\ndata: {\r\ndata: "text":"退货"}\r\n\r\n' +
      'event: done\ndata: {"messageId":"123","refused":false}\n\n' +
      'event: token\ndata: {"text":"忽略"}\n\n',
  )
  const onEvent = vi.fn()
  await readAiEvents(
    chunks(...Array.from(frames, (byte) => new Uint8Array([byte]))),
    controller.signal,
    onEvent,
  )
  expect(onEvent.mock.calls).toEqual([
    [{ type: 'token', data: { text: '退货' } }],
    [{ type: 'done', data: { messageId: '123', refused: false } }],
  ])
})

it('连接提前结束时报告失败，不把半截回答当作成功', async () => {
  const onEvent = vi.fn()
  await expect(
    readAiEvents(
      chunks(encoded.encode('event: token\ndata: {"text":"未完成"}\n\n')),
      controller.signal,
      onEvent,
    ),
  ).rejects.toMatchObject({ code: 'AI_STREAM_CLOSED' })
  expect(onEvent).toHaveBeenCalledOnce()
})

it('拒绝无法解析的事件数据', async () => {
  const onEvent = vi.fn()
  await expect(
    readAiEvents(
      chunks(encoded.encode('event: token\ndata: 错误数据\n\n')),
      controller.signal,
      onEvent,
    ),
  ).rejects.toMatchObject({ code: 'AI_STREAM_INVALID' })
  expect(onEvent).not.toHaveBeenCalled()
})

it('附带当前公司、同源会话和 CSRF，并保留所选只读工具', async () => {
  document.cookie = 'XSRF-TOKEN=csrf%2Bvalue; Path=/'
  const fetcher = vi.fn().mockResolvedValue(
    new Response('event: done\ndata: {"messageId":"123","refused":false}\n\n', {
      headers: { 'Content-Type': 'text/event-stream' },
    }),
  )
  vi.stubGlobal('fetch', fetcher)
  await sendAiMessage('45', '查看可访问仓库', controller.signal, vi.fn(), 'list_warehouses')
  const [path, options] = fetcher.mock.calls[0]!
  expect(path).toBe('/ai/v1/conversations/45/messages')
  expect(options.credentials).toBe('same-origin')
  expect(options.headers.get('X-Company-Id')).toBe(current)
  expect(options.headers.get('X-XSRF-TOKEN')).toBe('csrf+value')
  expect(JSON.parse(options.body)).toEqual({
    content: '查看可访问仓库',
    toolCode: 'list_warehouses',
  })
})

it.each([
  ['切换公司', () => setCompanyScope('2')],
  ['结束会话', () => renewSessionScope(false)],
])('%s时中止流，不把旧范围事件交给界面', async (_description, changeScope) => {
  let stream: ReadableStreamDefaultController<Uint8Array> | undefined
  const cancel = vi.fn()
  const fetcher = vi.fn().mockResolvedValue(
    new Response(
      new ReadableStream({
        start(value) {
          stream = value
        },
        cancel,
      }),
      { headers: { 'Content-Type': 'text/event-stream' } },
    ),
  )
  vi.stubGlobal('fetch', fetcher)
  const onEvent = vi.fn()
  const pending = sendAiMessage('45', '问题', controller.signal, onEvent)
  await Promise.resolve()
  changeScope()
  await expect(pending).rejects.toSatisfy(isCancelled)
  expect(stream).toBeDefined()
  expect(cancel).toHaveBeenCalledOnce()
  expect(onEvent).not.toHaveBeenCalled()
})

it('AI 接口返回登录失效时进入统一的会话过期流程', async () => {
  const onExpired = vi.fn()
  window.addEventListener('erp:session-expired', onExpired, { once: true })
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValue(
        new Response(
          JSON.stringify({ code: 401, errorCode: 'AI_LOGIN_REQUIRED', message: '登录已失效' }),
          { status: 401 },
        ),
      ),
  )
  try {
    await expect(sendAiMessage('45', '问题', controller.signal, vi.fn())).rejects.toMatchObject({
      code: 'AI_LOGIN_REQUIRED',
    })
    expect(onExpired).toHaveBeenCalledOnce()
  } finally {
    window.removeEventListener('erp:session-expired', onExpired)
  }
})

it('渲染 Markdown 标题和表格，同时隔绝模型提供的 HTML、脚本链接和远程图片', () => {
  const output = document.createElement('div')
  output.innerHTML = renderAiMarkdown(
    '# 退款步骤\n\n| 步骤 | 说明 |\n| --- | --- |\n| 一 | 核对 |\n\n' +
      '<svg onload="alert(1)"></svg>\n\n' +
      '[越权](javascript:alert(1)) ![图片](https://example.com/track.png)\n\n' +
      '<https://example.com> [合法语法](https://example.com)',
  )
  expect(output.querySelector('h1')?.textContent).toBe('退款步骤')
  expect(output.querySelector('tbody td')?.textContent).toBe('一')
  expect(output.querySelector('svg, script, [onload], a, img')).toBeNull()
})

it('百科仅为方括号条目标识生成站内链接', () => {
  const output = document.createElement('div')
  output.innerHTML = renderAiMarkdown(
    '[[doc-approved]] [[../escape]] [外站](https://example.com) <a href="/ai/wiki/raw">原始标签</a>',
    true,
  )
  const links = [...output.querySelectorAll('a')]
  expect(links.map((link) => link.getAttribute('href'))).toEqual(['/ai/wiki/doc-approved'])
  expect(links[0]?.textContent).toBe('doc-approved')
})
