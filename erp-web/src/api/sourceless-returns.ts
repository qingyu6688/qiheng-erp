import { request } from './http'
import { saveDocument } from './sales'
import type { PageResult } from '../types/api'
import type { SalesDocument } from '../types/sales'
import type {
  RefundCreditItem,
  ReturnCostPreview,
  SourcelessCreditRefundInput,
  SourcelessReturnInput,
} from '../types/sourceless-returns'

export function returnCostPreview(id: string, signal?: AbortSignal): Promise<ReturnCostPreview> {
  return request(
    {
      method: 'GET',
      url: `/sales-returns/${encodeURIComponent(id)}/cost-preview`,
      ...(signal ? { signal } : {}),
    },
    true,
  )
}

export function refundCreditItems(
  customerId: string,
  cursor?: string,
  signal?: AbortSignal,
): Promise<PageResult<RefundCreditItem>> {
  return request(
    {
      method: 'GET',
      url: '/refund-credit-items',
      params: { customerId, cursor, limit: 20 },
      ...(signal ? { signal } : {}),
    },
    true,
  )
}

export function saveSourcelessReturn(
  input: SourcelessReturnInput,
  key: string,
  id?: string,
): Promise<SalesDocument> {
  return saveDocument(
    'sales-returns',
    {
      ...input,
      returnMode: 'sourceless',
      lines: input.lines.map((line) => ({ ...line, shipmentLineId: null })),
    },
    id,
    key,
  )
}

export function saveSourcelessCreditRefund(
  input: SourcelessCreditRefundInput,
  key: string,
  id?: string,
): Promise<SalesDocument> {
  return saveDocument(
    'refund-requests',
    { ...input, refundMode: 'sourceless_credit', originalReceiptId: null },
    id,
    key,
  )
}
