import { request } from './http'
import type { PageResult } from '../types/api'
import type { ReversalSource, StockDocument, StockKind } from '../types/stock-operations'

export function stockList(kind: StockKind, params: Record<string, unknown> = {}): Promise<PageResult<StockDocument>> { return request({ method: 'GET', url: '/' + kind, params }, true) }
export function stockDocument(kind: StockKind, id: string): Promise<StockDocument> { return request({ method: 'GET', url: '/' + kind + '/' + id }, true) }
export function saveStock(kind: StockKind, data: Record<string, unknown>, id?: string, key?: string): Promise<StockDocument> { return request({ method: id ? 'PUT' : 'POST', url: '/' + kind + (id ? '/' + id : ''), data, headers: key ? { 'Idempotency-Key': key } : undefined }, true) }
export function stockCommand(kind: StockKind, id: string, action: string, data: Record<string, unknown>, key: string): Promise<StockDocument> { return request({ method: 'POST', url: '/' + kind + '/' + id + '/' + action.replaceAll('_', '-'), data, headers: { 'Idempotency-Key': key } }, true) }
export function recordStockCounts(id: string, data: Record<string, unknown>): Promise<StockDocument> { return request({ method: 'PUT', url: '/stock-counts/' + id + '/counts', data }, true) }
export function reversalSources(params: Record<string, unknown>): Promise<PageResult<ReversalSource>> { return request({ method: 'GET', url: '/document-reversal-sources', params }, true) }
export function stockResult(operation: string, key: string): Promise<{ status: string; response: StockDocument }> { return request({ method: 'GET', url: '/command-results', params: { operation, key } }, true) }
