import { request } from './http'
import type { DataRow } from '../types/admin'
export interface ArMetadata {
  customers: { id: string; code: string; name: string }[]
  users: { id: string; name: string }[]
}
export interface AgingConfig {
  version: number
  bounds: number[]
  rates: string[]
}
export interface CollectionDetail extends DataRow {
  followups: {
    id: string
    content: string
    createdAt: string
    promisedDate?: string
    promisedAmount?: string
  }[]
}
export function arMetadata(): Promise<ArMetadata> {
  return request({ method: 'GET', url: '/ar/metadata' }, true)
}
export function arRows(path: string): Promise<DataRow[]> {
  return request({ method: 'GET', url: '/ar/' + path }, true)
}
export function arRead<T>(path: string, params?: Record<string, unknown>): Promise<T> {
  return request({ method: 'GET', url: '/ar/' + path, params }, true)
}
export function arCommand<T>(
  path: string,
  data: unknown,
  key: string,
  method: 'POST' | 'PUT' = 'POST',
): Promise<T> {
  return request({ method, url: '/ar/' + path, data, headers: { 'Idempotency-Key': key } }, true)
}
