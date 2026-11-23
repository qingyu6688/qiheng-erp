import { request } from './http'
import type { PageResult } from '../types/api'
import type {
  AllocationMethod,
  PurchaseFee,
  PurchaseFeeAction,
  PurchaseFeeCommand,
  PurchaseFeeInput,
  PurchaseFeePreview,
  PurchaseFeeSource,
  PurchaseFeeSummary,
} from '../types/purchase-fees'

const resource = '/purchase-fee-documents'
export function purchaseFees(
  params: { status?: string; partnerId?: string; cursor?: string; limit?: number },
  signal?: AbortSignal,
): Promise<PageResult<PurchaseFeeSummary>> {
  return request({ method: 'GET', url: resource, params, signal }, true)
}
export function purchaseFee(id: string, signal?: AbortSignal): Promise<PurchaseFee> {
  return request({ method: 'GET', url: `${resource}/${id}`, signal }, true)
}
export function savePurchaseFee(
  input: PurchaseFeeInput,
  key: string,
  id?: string,
  signal?: AbortSignal,
): Promise<PurchaseFee> {
  return request(
    {
      method: id ? 'PUT' : 'POST',
      url: resource + (id ? `/${id}` : ''),
      data: input,
      headers: { 'Idempotency-Key': key },
      signal,
    },
    true,
  )
}
export function commandPurchaseFee(
  id: string,
  action: PurchaseFeeAction,
  input: PurchaseFeeCommand,
  key: string,
  signal?: AbortSignal,
): Promise<PurchaseFee> {
  return request(
    {
      method: 'POST',
      url: `${resource}/${id}/${action}`,
      data: input,
      headers: { 'Idempotency-Key': key },
      signal,
    },
    true,
  )
}
export function purchaseFeeMetadata(signal?: AbortSignal): Promise<{
  partners: { id: string; name: string }[]
  costEnabled: boolean
  allocationMethod: AllocationMethod
}> {
  return request({ method: 'GET', url: `${resource}/metadata`, signal }, true)
}
export function previewPurchaseFee(id: string, signal?: AbortSignal): Promise<PurchaseFeePreview> {
  return request({ method: 'POST', url: `${resource}/${id}/preview`, signal }, true)
}
export function purchaseFeeSources(
  cursor?: string,
  signal?: AbortSignal,
): Promise<PageResult<PurchaseFeeSource>> {
  return request(
    {
      method: 'GET',
      url: '/goods-receipt-lines',
      params: { returnable: true, cursor, limit: 200 },
      signal,
    },
    true,
  )
}
