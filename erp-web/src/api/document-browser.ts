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

export function exportSelectedDocuments(
  kind: string,
  ids: string[],
  signal: AbortSignal,
): Promise<Blob> {
  return request({
    method: 'POST',
    url: '/document-browser/' + kind + '/selected.csv',
    data: { ids },
    responseType: 'blob',
    signal,
  }, true)
}
