import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'
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

const client = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
})

let companyId: string | null = null
let companyRequests = new AbortController()
let sessionRevision = 0
let sessionActive = true

export function renewSessionScope(active = true): void {
  sessionRevision++
  sessionActive = active
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
  return axios.isCancel(error)
}

export async function request<T>(config: AxiosRequestConfig, companyScoped = false): Promise<T> {
  const scope = companyRequests
  const revision = sessionRevision
  if (!sessionActive && !['/auth/login', '/auth/logout'].includes(config.url ?? '')) {
    throw new axios.CanceledError('会话已退出')
  }
  if (companyScoped && !companyId) throw new ApiError(403, 'COMPANY_NOT_ALLOWED', '请选择公司。')
  const { headers, ...rest } = config
  try {
    const response = await client.request<ApiResponse<T>>({
      ...rest,
      headers: { ...headers, ...(companyScoped ? { 'X-Company-Id': companyId } : {}) },
      signal: companyScoped ? scope.signal : config.signal,
    })
    if (revision !== sessionRevision) throw new axios.CanceledError('会话已变更')
    if (companyScoped && scope.signal.aborted) throw new axios.CanceledError('公司已切换')
    return response.data.data
  } catch (cause: unknown) {
    if (revision !== sessionRevision) throw new axios.CanceledError('会话已变更')
    if (!axios.isAxiosError<ApiErrorBody>(cause) || axios.isCancel(cause)) throw cause
    const body = cause.response?.data
    const status = cause.response?.status ?? 0
    if (
      status === 401 &&
      config.url !== '/auth/login' &&
      config.url !== '/auth/me' &&
      body?.errorCode === 'UNAUTHENTICATED'
    ) {
      window.dispatchEvent(new Event('erp:session-expired'))
    }
    throw new ApiError(
      status,
      body?.errorCode ?? (status ? 'HTTP_ERROR' : 'NETWORK_ERROR'),
      body?.message ??
        (status ? '服务暂时不可用，请稍后重试。' : '连接失败或请求超时，请检查网络后重试。'),
      body?.details,
    )
  }
}
