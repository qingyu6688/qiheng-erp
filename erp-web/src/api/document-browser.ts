import { request } from './http'
import type { SalesDocument } from '../types/sales'

export interface DocumentBrowserPage {
  items: SalesDocument[]
  nextCursor: string | null
  total: number
  statusCounts: Record<string, number>
}

export function browseDocuments(
  kind: string,
  params: Record<string, unknown>,
  signal?: AbortSignal,
): Promise<DocumentBrowserPage> {
  return request({ method: 'GET', url: '/document-browser/' + kind, params, signal }, true)
}
