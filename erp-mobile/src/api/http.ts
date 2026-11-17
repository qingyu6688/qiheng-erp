import type { ApiResponse, Id, Query } from '../types/api'

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE'
type Data = Record<string, unknown>

interface RequestInput {
  method?: Method
  params?: Query
  data?: Data
  company?: boolean
  key?: string
  public?: boolean
}

interface Context {
  origin: string
  token?: string
  company?: Id
}

let context: Context = { origin: '' }
let onExpired: (() => void) | undefined

export class ApiFailure extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message)
  }
}

export function replaceContext(value: Context): void {
  context = { ...value }
}

export function onSessionExpired(handler: () => void): void {
  onExpired = handler
}

export async function requireServerVersion(): Promise<void> {
  const result = await api<{ apiMajor: number }>('/system/version', { public: true }).catch(
    (cause: unknown) => {
      if (cause instanceof ApiFailure && [401, 403, 404].includes(cause.status)) return undefined
      throw cause
    },
  )
  if (result?.apiMajor !== 2) {
    throw new ApiFailure(426, 'ERP_VERSION_INCOMPATIBLE', '当前服务端版本与移动端不兼容，请升级到柒衡 ERP 2.0。')
  }
}

export function validOrigin(value: string): string {
  if (typeof URL !== 'function') {
    // 原生 JS 引擎缺少 URL 时，只接受明确的 HTTPS 主机和可选端口。
    const parsed = /^https:\/\/([a-z0-9][a-z0-9.-]*|\[[a-f0-9:.]+\])(?::(\d{1,5}))?\/?$/i.exec(value.trim())
    const port = parsed?.[2] ? Number(parsed[2]) : 443
    if (!parsed || port < 1 || port > 65535) throw new Error('请输入有效的 ERP HTTPS 地址')
    return 'https://' + parsed[1]!.toLowerCase() + (port === 443 ? '' : ':' + port)
  }
  try {
    const url = new URL(value.trim())
    if (
      url.protocol === 'https:' &&
      !url.username &&
      !url.password &&
      url.pathname === '/' &&
      !url.search &&
      !url.hash
    ) return url.origin
  } catch {
    throw new Error('请输入有效的 ERP HTTPS 地址')
  }
  throw new Error('请输入不包含路径、参数或凭据的 ERP HTTPS 地址')
}

function csrfToken(): string | undefined {
  if (typeof document === 'undefined') return undefined
  const cookie = document.cookie.split('; ').find((part) => part.startsWith('XSRF-TOKEN='))
  return cookie ? decodeURIComponent(cookie.slice('XSRF-TOKEN='.length)) : undefined
}

function requestUrl(origin: string, path: string, params: Query = {}): string {
  if (!/^\/[a-zA-Z0-9/_-]+$/.test(path)) throw new Error('ERP 请求地址无效')
  const entries = Object.entries(params)
    .filter(([, value]) => value !== undefined && value !== '')
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
  return origin + '/api/v1' + path + (entries.length ? '?' + entries.join('&') : '')
}

function send<T>(path: string, input: RequestInput, binary: boolean): Promise<T> {
  const scope = context
  const method = input.method ?? 'GET'
  const headers: Record<string, string> = {}
  if (input.company) {
    if (!scope.company) return Promise.reject(new ApiFailure(400, 'COMPANY_REQUIRED', '请选择当前公司'))
    headers['X-Company-Id'] = scope.company
  }
  if (scope.token && !input.public) headers.Authorization = `Bearer ${scope.token}`
  else if (method !== 'GET') {
    const csrf = csrfToken()
    if (csrf) headers['X-XSRF-TOKEN'] = csrf
  }
  if (input.key) headers['Idempotency-Key'] = input.key
  if (input.data) headers['Content-Type'] = 'application/json'
  return new Promise((resolve, reject) => {
    uni.request({
      url: requestUrl(scope.origin, path, input.params),
      method,
      data: input.data,
      header: headers,
      withCredentials: !scope.token && scope.origin === '',
      timeout: 15_000,
      responseType: binary ? 'arraybuffer' : 'text',
      dataType: 'json',
      success(response) {
        if (scope !== context) {
          reject(new ApiFailure(409, 'CONTEXT_CHANGED', '当前公司或会话已变更'))
          return
        }
        let payload = response.data as ApiResponse<T>
        if (typeof response.data === 'string') {
          try {
            payload = JSON.parse(response.data) as ApiResponse<T>
          } catch {
            payload = {} as ApiResponse<T>
          }
        }
        if (binary && response.statusCode >= 200 && response.statusCode < 300) {
          resolve(response.data as T)
          return
        }
        if (binary && response.data instanceof ArrayBuffer && typeof TextDecoder !== 'undefined') {
          try {
            payload = JSON.parse(new TextDecoder().decode(response.data)) as ApiResponse<T>
          } catch {
            payload = {} as ApiResponse<T>
          }
        }
        if (response.statusCode >= 200 && response.statusCode < 300 && payload && 'data' in Object(payload)) {
          resolve(payload.data)
          return
        }
        if (response.statusCode === 401 && !input.public) onExpired?.()
        reject(new ApiFailure(response.statusCode, payload?.errorCode ?? '', payload?.message ?? 'ERP 暂时无法处理此请求'))
      },
      fail() {
        reject(new ApiFailure(0, 'NETWORK', '连接异常，检查网络后可用原请求重试'))
      },
    })
  })
}

export function api<T>(path: string, input: RequestInput = {}): Promise<T> {
  return send(path, input, false)
}

export function binaryApi(path: string, input: RequestInput): Promise<ArrayBuffer> {
  return send(path, input, true)
}

export function errorText(error: unknown): string {
  return error instanceof Error ? error.message : '操作失败，请重试'
}
