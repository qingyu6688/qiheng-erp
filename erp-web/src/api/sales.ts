import { request } from './http'
import type { PageResult } from '../types/api'
import type { SalesDocument, SalesKind } from '../types/sales'
export function documents(kind: SalesKind, params: Record<string, unknown> = {}): Promise<PageResult<SalesDocument>> {
  return request({ method: 'GET', url: '/' + kind, params }, true)
}
export function document(kind: SalesKind, id: string): Promise<SalesDocument> {
  return request({ method: 'GET', url: '/' + kind + '/' + id }, true)
}
export function saveDocument(kind: SalesKind, data: Record<string, unknown>, id?: string, key?: string): Promise<SalesDocument> {
  return request({ method: id ? 'PUT' : 'POST', url: '/' + kind + (id ? '/' + id : ''), data,
    headers: key ? { 'Idempotency-Key': key } : undefined }, true)
}
export function salesCommand(kind: SalesKind, id: string, action: string, data: Record<string, unknown>, key: string): Promise<SalesDocument> {
  return request({ method: action === 'delete' ? 'DELETE' : 'POST', url: '/' + kind + '/' + id + (action === 'delete' ? '' : '/' + action.replaceAll('_', '-')), data,
    params: action === 'delete' ? data : undefined, headers: { 'Idempotency-Key': key } }, true)
}
export function pricePreview(data: Record<string, unknown>): Promise<SalesDocument> {
  return request({ method: 'POST', url: '/pricing/preview', data: { documentType: 'sales_order', ...data } }, true)
}
export function commandResult(operation: string, key: string): Promise<{ status: string; response: SalesDocument }> {
  return request({ method: 'GET', url: '/command-results', params: { operation, key } }, true)
}
export function candidates(path: string, params: Record<string, unknown> = {}): Promise<PageResult<Record<string, unknown>>> {
  return request({ method: 'GET', url: '/' + path, params: { limit: 200, ...params } }, true)
}
export function financeWrite(path: string, data: Record<string, unknown>, key: string): Promise<Record<string, unknown>> {
  return request({ method: 'POST', url: '/' + path, data, headers: { 'Idempotency-Key': key } }, true)
}
