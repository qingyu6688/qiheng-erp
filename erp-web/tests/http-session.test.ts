import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import axios from 'axios'
import { request, renewSessionScope, setCompanyScope } from '../src/api/http'

const { clientRequest } = vi.hoisted(() => ({ clientRequest: vi.fn() }))
vi.mock('axios', async importOriginal => {
  const actual = await importOriginal<typeof import('axios')>()
  return { ...actual, default: { ...actual.default, create: () => ({ request: clientRequest }) } }
})
const expired = vi.fn()
beforeEach(() => {
  renewSessionScope()
  clientRequest.mockReset()
  expired.mockReset()
  window.addEventListener('erp:session-expired', expired)
})
afterEach(() => window.removeEventListener('erp:session-expired', expired))

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
