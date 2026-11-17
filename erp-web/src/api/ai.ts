import type { AxiosRequestConfig } from 'axios'
import type { ApiErrorBody, Id } from '../types/api'
import type { AiChatEvent } from '../types/ai'
import { ApiError, companyStreamScope, notifySessionFailure, request } from './http'

const prefix = '/ai/v1'

export function aiRead<T>(url: string, params?: AxiosRequestConfig['params']): Promise<T> {
  return request<T>({ baseURL: prefix, method: 'GET', url, params }, true)
}

export function aiWrite<T>(
  url: string,
  data?: unknown,
  method: 'POST' | 'PUT' | 'DELETE' = 'POST',
  timeout?: number,
): Promise<T> {
  return request<T>({ baseURL: prefix, method, url, data, ...(timeout ? { timeout } : {}) }, true)
}

const eventTypes = new Set(['token', 'citation', 'tool_call', 'tool_result', 'done', 'error'])

export async function readAiEvents(
  body: ReadableStream<Uint8Array>,
  signal: AbortSignal,
  onEvent: (event: AiChatEvent) => void,
): Promise<void> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let eventType = ''
  let data: string[] = []
  const cancel = (): void => {
    void reader.cancel().catch(() => undefined)
  }
  signal.addEventListener('abort', cancel, { once: true })
  try {
    for (;;) {
      signal.throwIfAborted()
      const chunk = await reader.read()
      signal.throwIfAborted()
      buffer += decoder.decode(chunk.value, { stream: !chunk.done })
      let lineStart = 0
      for (const eol of buffer.matchAll(/\r\n|\r|\n/g)) {
        const end = eol.index + eol[0].length
        if (eol[0] === '\r' && end === buffer.length && !chunk.done) break
        const line = buffer.slice(lineStart, eol.index)
        lineStart = end
        if (line.startsWith('event:')) eventType = line.slice(6).trim()
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
        else if (!line) {
          if (eventTypes.has(eventType) && data.length) {
            let event: AiChatEvent
            try {
              event = { type: eventType, data: JSON.parse(data.join('\n')) } as AiChatEvent
            } catch {
              throw new ApiError(502, 'AI_STREAM_INVALID', '助手返回的数据格式异常，请重试。')
            }
            signal.throwIfAborted()
            onEvent(event)
            if (event.type === 'done' || event.type === 'error') return
          }
          eventType = ''
          data = []
        }
      }
      if (chunk.done) throw new ApiError(502, 'AI_STREAM_CLOSED', '连接提前结束，请重试。')
      buffer = buffer.slice(lineStart)
    }
  } finally {
    signal.removeEventListener('abort', cancel)
    cancel()
    reader.releaseLock()
  }
}

export async function sendAiMessage(
  conversationId: Id,
  content: string,
  signal: AbortSignal,
  onEvent: (event: AiChatEvent) => void,
  toolCode?: string,
): Promise<void> {
  const scope = companyStreamScope(signal)
  const headers = new Headers({
    Accept: 'text/event-stream',
    'Content-Type': 'application/json',
    'X-Company-Id': scope.company,
  })
  const token = document.cookie.split('; ').find((cookie) => cookie.startsWith('XSRF-TOKEN='))
  if (token) headers.set('X-XSRF-TOKEN', decodeURIComponent(token.slice('XSRF-TOKEN='.length)))

  let response: Response
  try {
    response = await fetch(
      `${prefix}/conversations/${encodeURIComponent(conversationId)}/messages`,
      {
        method: 'POST',
        headers,
        credentials: 'same-origin',
        signal: scope.signal,
        body: JSON.stringify({ content, toolCode }),
      },
    )
  } catch (cause: unknown) {
    scope.signal.throwIfAborted()
    if (!(cause instanceof TypeError)) throw cause
    throw new ApiError(0, 'NETWORK_ERROR', '无法连接 AI 助手，请稍后重试。')
  }
  scope.signal.throwIfAborted()
  if (!response.ok) {
    const error = (await response.json().catch(() => undefined)) as ApiErrorBody | undefined
    scope.signal.throwIfAborted()
    notifySessionFailure(response.status, error?.errorCode)
    throw new ApiError(
      response.status,
      error?.errorCode ?? 'HTTP_ERROR',
      error?.message ?? 'AI 助手暂时不可用，请稍后重试。',
    )
  }
  if (!response.body || !response.headers.get('Content-Type')?.includes('text/event-stream')) {
    throw new ApiError(502, 'AI_STREAM_INVALID', 'AI 助手未返回有效的回答，请稍后重试。')
  }
  await readAiEvents(response.body, scope.signal, (event) => {
    if (event.type === 'error' && event.data.code === 'AI_LOGIN_REQUIRED') {
      notifySessionFailure(401, event.data.code)
      scope.signal.throwIfAborted()
    }
    onEvent(event)
  })
}
