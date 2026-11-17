import { afterEach, expect, it, vi } from 'vitest'
import { api, ApiFailure, onSessionExpired, replaceContext, validOrigin } from '../src/api/http'

type Response = { statusCode: number; data: unknown }
type Request = { url: string; header: Record<string, string>; withCredentials: boolean; success: (response: Response) => void }

afterEach(() => {
  vi.unstubAllGlobals()
  onSessionExpired(() => undefined)
  replaceContext({ origin: '' })
})

it('设备只向已配置的 HTTPS 域名发 Bearer 并继续携带公司范围', async () => {
  const request = vi.fn((options: Request) => options.success({ statusCode: 200, data: { data: 'ok' } }))
  vi.stubGlobal('uni', { request })
  replaceContext({ origin: validOrigin('https://erp.example.com'), token: 'device-opaque-id', company: '9007199254740993' })
  await expect(api('/wms/tasks', { company: true, method: 'POST', key: 'request-id' })).resolves.toBe('ok')
  expect(request).toHaveBeenCalledWith(expect.objectContaining({
    url: 'https://erp.example.com/api/v1/wms/tasks',
    withCredentials: false,
    header: { Authorization: 'Bearer device-opaque-id', 'X-Company-Id': '9007199254740993', 'Idempotency-Key': 'request-id' },
  }))
  for (const origin of ['http://erp.example.com', 'https://user@erp.example.com', 'https://erp.example.com/work', 'https://erp.example.com/?next=example']) {
    expect(() => validOrigin(origin)).toThrow()
  }
})

it('H5 沿用 Cookie 和 CSRF，设备配对请求不携带已有 Bearer', async () => {
  const request = vi.fn((options: Request) => options.success({ statusCode: 200, data: '{"data":{"id":"1"}}' }))
  vi.stubGlobal('uni', { request })
  vi.stubGlobal('document', { cookie: 'theme=1; XSRF-TOKEN=csrf-valid' })
  replaceContext({ origin: '', company: '3' })
  await api('/workflow/tasks/1/approve', { company: true, method: 'POST', data: { version: 1 } })
  expect(request).toHaveBeenLastCalledWith(expect.objectContaining({
    withCredentials: true,
    header: expect.objectContaining({ 'X-XSRF-TOKEN': 'csrf-valid', 'X-Company-Id': '3' }),
  }))
  replaceContext({ origin: 'https://erp.example.com', token: 'stale-device' })
  await api('/auth/devices/activate', { public: true, method: 'POST', data: { pairingCode: '1234' } })
  expect(request.mock.lastCall?.[0].header).not.toHaveProperty('Authorization')
})

it('强制下线后通知清空会话，公司切换期间不读取旧公司响应', async () => {
  const requests: Request[] = []
  vi.stubGlobal('uni', { request: (input: Request) => { requests.push(input) } })
  const expired = vi.fn()
  onSessionExpired(expired)
  replaceContext({ origin: '', company: '1' })
  const stale = api('/wms/tasks', { company: true })
  replaceContext({ origin: '', company: '2' })
  requests.shift()?.success({ statusCode: 200, data: { data: ['旧公司任务'] } })
  await expect(stale).rejects.toMatchObject({ code: 'CONTEXT_CHANGED' })
  const current = api('/wms/tasks', { company: true })
  requests.shift()?.success({ statusCode: 401, data: { errorCode: 'SESSION_REVOKED', message: '会话已失效' } })
  await expect(current).rejects.toBeInstanceOf(ApiFailure)
  expect(expired).toHaveBeenCalledOnce()
})
