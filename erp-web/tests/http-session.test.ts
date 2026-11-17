import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import axios from 'axios'
import { request, renewSessionScope, replaceSession, requireServerVersion, setCompanyScope } from '../src/api/http'

const { clientRequest } = vi.hoisted(() => ({ clientRequest: vi.fn() }))
vi.mock('axios', async importOriginal => {
  const actual = await importOriginal<typeof import('axios')>()
  return { ...actual, default: { ...actual.default, create: () => ({ request: clientRequest }) } }
})
const expired = vi.fn()

function deferred(): { promise: Promise<unknown>; resolve: (value: unknown) => void; reject: (error: unknown) => void } {
  let resolve!: (value: unknown) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<unknown>((success, failure) => {
    resolve = success
    reject = failure
  })
  return { promise, resolve, reject }
}

beforeEach(() => {
  renewSessionScope()
  clientRequest.mockReset()
  expired.mockReset()
  window.addEventListener('erp:session-expired', expired)
})
afterEach(() => window.removeEventListener('erp:session-expired', expired))

it('2.0 页面在旧 API 缺少版本探测端点时要求升级', async () => {
  clientRequest.mockRejectedValueOnce({ isAxiosError: true, response: { status: 404, data: {} } })
  await expect(requireServerVersion()).rejects.toThrow('请升级到柒衡 ERP 2.0')
  clientRequest.mockResolvedValueOnce({ data: { data: { apiMajor: 2, version: '2.0.0' } } })
  await expect(requireServerVersion()).resolves.toBeUndefined()
})

it('版本探测期间会话轮换仍可正常打开登录页', async () => {
  const reply = deferred()
  clientRequest.mockReturnValueOnce(reply.promise)
  const pending = requireServerVersion()
  renewSessionScope()
  reply.resolve({ data: { data: { apiMajor: 2 } } })
  await expect(pending).resolves.toBeUndefined()
  expect(expired).not.toHaveBeenCalled()
})

it('退出时丢弃旧的租户级401，保留新登录页面', async () => {
  let reject: (error: unknown) => void = () => undefined
  clientRequest.mockReturnValueOnce(new Promise((_resolve, failed) => { reject = failed }))
  const pending = request({ method: 'GET', url: '/users' })
  renewSessionScope()
  reject({ isAxiosError: true, response: { status: 401, data: { errorCode: 'UNAUTHENTICATED' } } })
  await expect(pending).rejects.toSatisfy(axios.isCancel)
  expect(expired).not.toHaveBeenCalled()
})

it('重新登录后旧用户资料响应无法覆盖当前会话', async () => {
  let resolve: (value: unknown) => void = () => undefined
  clientRequest.mockReturnValueOnce(new Promise(success => { resolve = success }))
  const pending = request({ method: 'GET', url: '/auth/me' })
  renewSessionScope()
  resolve({ data: { data: { username: 'old-admin' } } })
  await expect(pending).rejects.toSatisfy(axios.isCancel)
})

it('切换会话代次后新请求可正常执行', async () => {
  renewSessionScope()
  clientRequest.mockResolvedValueOnce({ data: { data: { username: 'new-user' } } })
  await expect(request({ method: 'GET', url: '/auth/me' })).resolves.toEqual({ username: 'new-user' })
})

it('MFA 轮换期间发出的通知请求返回旧会话 401 时保留新会话', async () => {
  const rotation = deferred()
  const unreadCount = deferred()
  clientRequest.mockReturnValueOnce(rotation.promise).mockReturnValueOnce(unreadCount.promise)
  const activation = replaceSession(() => request({ method: 'POST', url: '/account/mfa/activate' }))
  const pending = request({ method: 'GET', url: '/notify/unread-count' })
  const rejected = expect(pending).rejects.toSatisfy(axios.isCancel)

  rotation.resolve({ data: { data: { enabled: true } } })
  await expect(activation).resolves.toEqual({ enabled: true })
  unreadCount.reject({ isAxiosError: true, response: { status: 401, data: { errorCode: 'UNAUTHENTICATED' } } })
  await rejected
  expect(expired).not.toHaveBeenCalled()

  clientRequest.mockResolvedValueOnce({ data: { data: { enabled: true } } })
  await expect(request({ method: 'GET', url: '/account/mfa' })).resolves.toEqual({ enabled: true })
})

it('退出后结束 MFA 轮换时仍保持退出状态', async () => {
  const rotation = deferred()
  clientRequest.mockReturnValueOnce(rotation.promise)
  const activation = replaceSession(() => request({ method: 'POST', url: '/account/mfa/activate' }))
  const rejected = expect(activation).rejects.toSatisfy(axios.isCancel)

  renewSessionScope(false)
  rotation.resolve({ data: { data: { enabled: true } } })
  await rejected
  await expect(request({ method: 'GET', url: '/account/mfa' })).rejects.toSatisfy(axios.isCancel)
  expect(clientRequest).toHaveBeenCalledTimes(1)
})

it('已退出时旧保存流程后续发起的业务查询直接取消', async () => {
  renewSessionScope(false)
  await expect(request({ method: 'GET', url: '/users' })).rejects.toSatisfy(axios.isCancel)
  await expect(request({ method: 'GET', url: '/auth/me' })).rejects.toSatisfy(axios.isCancel)
  expect(clientRequest).not.toHaveBeenCalled()
  expect(expired).not.toHaveBeenCalled()
})

it('退出失败恢复会话后业务请求仍可执行', async () => {
  renewSessionScope(false)
  renewSessionScope()
  clientRequest.mockResolvedValueOnce({ data: { data: { items: [] } } })
  await expect(request({ method: 'GET', url: '/users' })).resolves.toEqual({ items: [] })
})

it('退出后备用登录仍可发起，验证码失败不触发旧会话过期跳转', async () => {
  renewSessionScope(false)
  clientRequest.mockResolvedValueOnce({ data: { data: { sms: true } } })
  await expect(request({ method: 'GET', url: '/auth/methods' })).resolves.toEqual({ sms: true })
  clientRequest.mockRejectedValueOnce({
    isAxiosError: true, response: { status: 401, data: { errorCode: 'UNAUTHENTICATED' } },
  })
  await expect(request({ method: 'POST', url: '/auth/code/login' })).rejects.toMatchObject({ status: 401 })
  expect(expired).not.toHaveBeenCalled()
})

it('二进制下载仍携带公司范围并返回原始文件', async () => {
  setCompanyScope('9007199254740993')
  const blob = new Blob(['\uFEFF金额\r\n12345678901234567890.12'], { type: 'text/csv' })
  clientRequest.mockResolvedValueOnce({ data: blob })
  await expect(request<Blob>({ method: 'GET', url: '/export-jobs/1/download', responseType: 'blob' }, true)).resolves.toBe(blob)
  expect(clientRequest).toHaveBeenCalledWith(expect.objectContaining({ headers: { 'X-Company-Id': '9007199254740993' } }))
})

it('页面取消信号与公司切换信号合并，下载离开页面后中止', async () => {
  setCompanyScope('1'); const page = new AbortController()
  clientRequest.mockResolvedValueOnce({ data: { data: {} } })
  await request({ method: 'GET', url: '/import-jobs', signal: page.signal }, true)
  const signal = clientRequest.mock.calls[0][0].signal as AbortSignal
  page.abort(); expect(signal.aborted).toBe(true)
})
