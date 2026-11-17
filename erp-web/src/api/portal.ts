import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'
import type { ApiResponse, ApiErrorBody } from '../types/api'
import { ApiError } from './http'

const portalClient = axios.create({
  baseURL: '/api/v1/portal',
  timeout: 15000,
  withCredentials: true,
  xsrfCookieName: 'PORTAL-XSRF-TOKEN',
  xsrfHeaderName: 'X-PORTAL-XSRF-TOKEN',
})

async function portalRequest<T>(config: AxiosRequestConfig): Promise<T> {
  try {
    const response = await portalClient.request<ApiResponse<T>>(config)
    return config.responseType === 'blob' ? (response.data as unknown as T) : response.data.data
  } catch (cause: unknown) {
    if (!axios.isAxiosError<ApiErrorBody>(cause) || axios.isCancel(cause)) throw cause
    const body = cause.response?.data
    throw new ApiError(
      cause.response?.status ?? 0,
      body?.errorCode ?? 'PORTAL_ERROR',
      body?.message ?? '门户暂时无法完成操作，请稍后重试。',
    )
  }
}

export function portalRead<T>(
  path: string,
  params?: Record<string, unknown>,
  signal?: AbortSignal,
): Promise<T> {
  return portalRequest<T>({ method: 'GET', url: path, params, signal })
}

export function portalWrite<T>(path: string, data?: unknown, signal?: AbortSignal): Promise<T> {
  return portalRequest<T>({ method: 'POST', url: path, data, signal })
}

export function portalFile(
  path: string,
  params?: Record<string, unknown>,
  signal?: AbortSignal,
): Promise<Blob> {
  return portalRequest<Blob>({ method: 'GET', url: path, params, signal, responseType: 'blob' })
}
