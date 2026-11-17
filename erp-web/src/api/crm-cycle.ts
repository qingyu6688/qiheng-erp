import type { AxiosRequestConfig } from 'axios'
import type { PageResult } from '../types/api'
import type { CrmCycleRow } from '../types/crm-cycle'
import { request } from './http'

export function crmCycleRead<T>(path: string, params?: AxiosRequestConfig['params']): Promise<T> {
  return request<T>({ method: 'GET', url: '/crm/' + path, params }, true)
}

export function crmCyclePage(
  path: string,
  params?: AxiosRequestConfig['params'],
): Promise<PageResult<CrmCycleRow>> {
  return crmCycleRead(path, params)
}

export function crmCycleSave(
  path: string,
  data: unknown,
  method: 'POST' | 'PUT' = 'POST',
  key?: string,
): Promise<CrmCycleRow> {
  return request<CrmCycleRow>(
    {
      method,
      url: '/crm/' + path,
      data,
      headers: key ? { 'Idempotency-Key': key } : undefined,
    },
    true,
  )
}
