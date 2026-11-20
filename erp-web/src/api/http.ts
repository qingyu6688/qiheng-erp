import axios from 'axios'
import type { AxiosAdapter, AxiosRequestConfig } from 'axios'
import type { ApiErrorBody, ApiResponse } from '../types/api'

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly details?: unknown,
  ) {
    super(message)
  }
}

/** 独立演示入口使用内存数据源，正式入口仍使用默认 HTTP 传输。 */
export function installRequestAdapter(adapter: AxiosAdapter): void {
  client.defaults.adapter = adapter
}

const client = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
})

let companyId: string | null = null
let companyRequests = new AbortController()
let sessionRequests = new AbortController()
let sessionRevision = 0
let sessionActive = true
let replacingSession = false
const publicAuthPaths = [
  '/auth/login',
  '/auth/methods',
  '/auth/code/send',
  '/auth/code/login',
  '/auth/wechat/start',
  '/auth/ldap/login',
  '/auth/mfa/status',
  '/auth/mfa/setup',
  '/auth/mfa/verify',
  '/auth/mfa/activate',
]

export function renewSessionScope(active = true): void {
  sessionRevision++
  sessionActive = active
  sessionRequests.abort()
  sessionRequests = new AbortController()
}

/** 主动轮换会话时，丢弃仍可能携带旧 Cookie 的在途响应。 */
export async function replaceSession<T>(perform: () => Promise<T>): Promise<T> {
  if (!sessionActive) throw new axios.CanceledError('会话已退出')
  if (replacingSession) throw new ApiError(409, 'SESSION_TRANSITION', '安全状态正在更新，请稍后重试。')
  replacingSession = true
  renewSessionScope()
  const revision = sessionRevision
  try {
    return await perform()
  } finally {
    if (revision === sessionRevision) renewSessionScope()
    replacingSession = false
  }
}

export function setCompanyScope(next: string | null): void {
  if (companyId === next) return
  companyRequests.abort()
  companyRequests = new AbortController()
  companyId = next
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}

export function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : '操作未完成，请重试。'
}

export function isCancelled(error: unknown): boolean {
  return axios.isCancel(error) || (error instanceof DOMException && error.name === 'AbortError')
}

export function companyStreamScope(signal: AbortSignal): { company: string; signal: AbortSignal } {
  if (!sessionActive) throw new axios.CanceledError('会话已退出')
  if (!companyId) throw new ApiError(403, 'COMPANY_NOT_ALLOWED', '请选择公司。')
  return {
    company: companyId,
    signal: AbortSignal.any([companyRequests.signal, sessionRequests.signal, signal]),
  }
}

export function notifySessionFailure(status: number, code?: string, authCheck = false): void {
  if (status !== 401 || replacingSession) return
  if (code === 'SESSION_REVOKED') window.dispatchEvent(new Event('erp:session-kicked'))
  if (!authCheck && (code === 'UNAUTHENTICATED' || code === 'AI_LOGIN_REQUIRED'))
    window.dispatchEvent(new Event('erp:session-expired'))
}

export async function requireServerVersion(): Promise<void> {
  // 登录和退出会更新会话范围，版本检查使用独立请求。
  const major = await client
    .request<ApiResponse<{ apiMajor: number }>>({ url: '/system/version', method: 'GET' })
    .then((response) => response.data?.data?.apiMajor)
    .catch((cause: unknown) => {
      if (axios.isAxiosError(cause) && [401, 403, 404].includes(cause.response?.status ?? 0)) return undefined
      throw new Error('暂时无法检查服务端版本，请稍后重试。')
    })
  if (major !== 2) throw new Error('当前服务端版本与页面不兼容，请升级到柒衡 ERP 2.0。')
}

export async function request<T>(config: AxiosRequestConfig, companyScoped = false): Promise<T> {
  const scope = companyRequests
  const revision = sessionRevision
  const publicAuth = publicAuthPaths.includes(config.url ?? '')
  if (!sessionActive && !publicAuth && config.url !== '/auth/logout') {
    throw new axios.CanceledError('会话已退出')
  }
  if (companyScoped && !companyId) throw new ApiError(403, 'COMPANY_NOT_ALLOWED', '请选择公司。')
  const { headers, ...rest } = config
  try {
    const response = await client.request<ApiResponse<T>>({
      ...rest,
      headers: { ...headers, ...(companyScoped ? { 'X-Company-Id': companyId } : {}) },
      signal:
        companyScoped && config.signal
          ? AbortSignal.any([scope.signal, config.signal as AbortSignal])
          : companyScoped
            ? scope.signal
            : config.signal,
    })
    if (revision !== sessionRevision) throw new axios.CanceledError('会话已变更')
    if (companyScoped && scope.signal.aborted) throw new axios.CanceledError('公司已切换')
    if (config.responseType === 'blob') return response.data as unknown as T
    return response.data.data
  } catch (cause: unknown) {
    if (revision !== sessionRevision) throw new axios.CanceledError('会话已变更')
    if (!axios.isAxiosError<ApiErrorBody>(cause) || axios.isCancel(cause)) throw cause
    let body = cause.response?.data
    if (body instanceof Blob) {
      try {
        body = JSON.parse(await body.text()) as ApiErrorBody
      } catch {
        body = undefined
      }
      if (revision !== sessionRevision) throw new axios.CanceledError('会话已变更')
    }
    const status = cause.response?.status ?? 0
    if (!publicAuth) notifySessionFailure(status, body?.errorCode, config.url === '/auth/me')
    throw new ApiError(
      status,
      body?.errorCode ?? (status ? 'HTTP_ERROR' : 'NETWORK_ERROR'),
      body?.message ??
        (status ? '服务暂时不可用，请稍后重试。' : '连接失败或请求超时，请检查网络后重试。'),
      body?.details,
    )
  }
}
