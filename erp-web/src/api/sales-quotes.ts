import { request } from './http'
import type { QuoteMetadata, QuotePricing, QuoteUnit, SalesQuote, SalesQuoteAction, SalesQuoteCommand, SalesQuoteInput, SalesQuotePage } from '../types/sales-quotes'

const resource = '/sales-quotes'
export function salesQuotes(params: { status?: string; cursor?: string; limit?: number }, signal?: AbortSignal): Promise<SalesQuotePage> {
  return request({ method: 'GET', url: resource, params, signal }, true)
}
export function salesQuote(id: string, signal?: AbortSignal): Promise<SalesQuote> {
  return request({ method: 'GET', url: `${resource}/${id}`, signal }, true)
}
export function quoteMetadata(search?: string, customerId?: string, signal?: AbortSignal): Promise<QuoteMetadata> {
  return request({ method: 'GET', url: `${resource}/metadata`, params: { search, customerId }, signal }, true)
}
export function quoteUnits(id: string, signal?: AbortSignal): Promise<QuoteUnit[]> {
  return request({ method: 'GET', url: `${resource}/sku-units/${id}`, signal }, true)
}
export function quotePreview(input: SalesQuoteInput, signal?: AbortSignal): Promise<{ pricing: QuotePricing }> {
  return request({ method: 'POST', url: `${resource}/preview`, data: input, signal }, true)
}
export function saveSalesQuote(input: SalesQuoteInput, key: string, id?: string, signal?: AbortSignal): Promise<SalesQuote> {
  return request({ method: id ? 'PUT' : 'POST', url: resource + (id ? `/${id}` : ''), data: input, headers: { 'Idempotency-Key': key }, signal }, true)
}
export function commandSalesQuote(id: string, action: SalesQuoteAction, input: SalesQuoteCommand, key: string, signal?: AbortSignal): Promise<SalesQuote> {
  return request({ method: 'POST', url: `${resource}/${id}/${action}`, data: input, headers: { 'Idempotency-Key': key }, signal }, true)
}
export function quoteCommandResult(operation: string, key: string): Promise<{ status: string; response: SalesQuote | null }> {
  return request({ method: 'GET', url: '/command-results', params: { operation, key } }, true)
}
