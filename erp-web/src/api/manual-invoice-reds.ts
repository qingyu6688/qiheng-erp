import { request } from './http'
import type { PageResult } from '../types/api'
import type {
  InvoiceRegistration,
  ManualInvoiceRed,
  ManualInvoiceRedInput,
} from '../types/manual-invoice-reds'

export function invoiceRegistration(
  id: string,
  signal?: AbortSignal,
): Promise<InvoiceRegistration> {
  return request({ method: 'GET', url: `/invoice-registrations/${id}`, signal }, true)
}

export function manualInvoiceReds(
  invoiceId: string,
  signal?: AbortSignal,
  cursor?: string,
): Promise<PageResult<ManualInvoiceRed>> {
  return searchManualInvoiceReds({ invoiceId, cursor, limit: 100 }, signal)
}

export function searchManualInvoiceReds(
  params: {
    invoiceId?: string
    status?: ManualInvoiceRed['status']
    cursor?: string
    limit?: number
  },
  signal?: AbortSignal,
): Promise<PageResult<ManualInvoiceRed>> {
  return request({ method: 'GET', url: '/manual-invoice-reds', params, signal }, true)
}

export function createManualInvoiceRed(
  input: ManualInvoiceRedInput,
  key: string,
  signal?: AbortSignal,
): Promise<ManualInvoiceRed> {
  return request(
    {
      method: 'POST',
      url: '/manual-invoice-reds',
      data: input,
      headers: { 'Idempotency-Key': key },
      signal,
    },
    true,
  )
}

export function commandManualInvoiceRed(
  id: string,
  action: 'confirm' | 'cancel',
  version: number,
  key: string,
  signal?: AbortSignal,
): Promise<ManualInvoiceRed> {
  return request(
    {
      method: 'POST',
      url: `/manual-invoice-reds/${id}/${action}`,
      data: { version },
      headers: { 'Idempotency-Key': key },
      signal,
    },
    true,
  )
}
