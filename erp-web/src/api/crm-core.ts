import type { AxiosRequestConfig } from 'axios'
import type { CrmEmployee } from '../types/crm-core'
import { request } from './http'

export function readCrmCore<T>(
  path: string,
  params?: AxiosRequestConfig['params'],
  signal?: AbortSignal,
): Promise<T> {
  return request<T>({ method: 'GET', url: '/crm' + path, params, signal }, true)
}

export function writeCrmCore<T>(
  path: string,
  data?: unknown,
  method: 'POST' | 'PUT' | 'DELETE' = 'POST',
  params?: AxiosRequestConfig['params'],
): Promise<T> {
  return request<T>({ method, url: '/crm' + path, data, params }, true)
}

export function crmEmployees(keyword = '', signal?: AbortSignal): Promise<CrmEmployee[]> {
  return readCrmCore<CrmEmployee[]>('/employee-options', { keyword }, signal)
}

export function importCrmClues(file: File): Promise<{ imported: number }> {
  const body = new FormData()
  body.append('file', file)
  return request({ method: 'POST', url: '/crm/clues/import', data: body, timeout: 60_000 }, true)
}
