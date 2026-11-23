import { request } from './http'
import type { PageResult } from '../types/api'
import type {
  PriceAdjustment,
  PriceAdjustmentAction,
  PriceAdjustmentCommand,
  PriceAdjustmentInput,
  PricePreview,
  PriceSource,
} from '../types/price-adjustments'

const resource = '/purchase-price-adjustments'

export function priceAdjustments(
  params: { status?: string; partnerId?: string; cursor?: string; limit?: number },
  signal?: AbortSignal,
): Promise<PageResult<PriceAdjustment>> {
  return request({ method: 'GET', url: resource, params, signal }, true)
}

export function priceAdjustment(id: string, signal?: AbortSignal): Promise<PriceAdjustment> {
  return request({ method: 'GET', url: `${resource}/${id}`, signal }, true)
}

export function savePriceAdjustment(
  input: PriceAdjustmentInput,
  key: string,
  id?: string,
  signal?: AbortSignal,
): Promise<PriceAdjustment> {
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

export function commandPriceAdjustment(
  id: string,
  action: PriceAdjustmentAction,
  input: PriceAdjustmentCommand,
  key: string,
  signal?: AbortSignal,
): Promise<PriceAdjustment> {
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

export function priceAdjustmentMetadata(
  signal?: AbortSignal,
): Promise<{
  partners: { id: string; name: string }[]
  warehouses: { id: string; name: string }[]
}> {
  return request({ method: 'GET', url: `${resource}/metadata`, signal }, true)
}

export function previewPriceAdjustment(id: string, signal?: AbortSignal): Promise<PricePreview> {
  return request({ method: 'POST', url: `${resource}/${id}/preview`, signal }, true)
}

export function priceAdjustmentSources(
  partnerId: string,
  warehouseId: string,
  cursor?: string,
  signal?: AbortSignal,
): Promise<PageResult<PriceSource>> {
  return request(
    {
      method: 'GET',
      url: '/goods-receipt-lines',
      params: { supplierId: partnerId, warehouseId, returnable: true, cursor, limit: 200 },
      signal,
    },
    true,
  )
}
