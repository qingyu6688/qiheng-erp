import { request } from './http'
import type { PageResult } from '../types/api'

export type OrderResource = 'sales-orders' | 'purchase-orders'
export interface OrderSettlementSummary {
  allocatedCashAmount: string | null
  openAmount: string | null
  scope: 'company' | 'visible'
}

export function orderSettlementSummary(
  kind: OrderResource,
  id: string,
  signal: AbortSignal,
): Promise<OrderSettlementSummary> {
  return request(
    { method: 'GET', url: `/${kind}/${encodeURIComponent(id)}/settlement-summary`, signal },
    true,
  )
}

export type RelatedResource =
  | 'shipments'
  | 'sales-returns'
  | 'goods-receipts'
  | 'purchase-returns'
  | 'receipts'
  | 'payments'
  | 'settlement-allocations'

export interface OrderRelatedRow {
  id: string
  documentId: string
  documentNo: string
  status: string
  businessDate: string
  amount?: string | null
}

export function orderRelated(
  kind: OrderResource,
  id: string,
  resource: RelatedResource,
  cursor: string | null,
  signal: AbortSignal,
): Promise<PageResult<OrderRelatedRow>> {
  return request(
    {
      method: 'GET',
      url: `/${kind}/${encodeURIComponent(id)}/related/${resource}`,
      params: { limit: 20, cursor: cursor ?? undefined },
      signal,
    },
    true,
  )
}
