import { request } from './http'
import type { PageResult } from '../types/api'
import type { PriceAdjustmentAction, PriceAdjustmentCommand } from '../types/price-adjustments'
import type {
  SalesPriceAdjustment,
  SalesPriceAdjustmentInput,
  SalesPriceMetadata,
  SalesPricePreview,
  SalesPriceSource,
} from '../types/sales-price-adjustments'

const resource = '/sales-price-adjustments'

export function salesPriceAdjustments(
  params: { status?: string; partnerId?: string; cursor?: string; limit?: number },
  signal?: AbortSignal,
): Promise<PageResult<SalesPriceAdjustment>> {
  return request({ method: 'GET', url: resource, params, signal }, true)
}

export function salesPriceAdjustment(
  id: string,
  signal?: AbortSignal,
): Promise<SalesPriceAdjustment> {
  return request({ method: 'GET', url: `${resource}/${id}`, signal }, true)
}

export function saveSalesPriceAdjustment(
  input: SalesPriceAdjustmentInput,
  key: string,
  id?: string,
  signal?: AbortSignal,
): Promise<SalesPriceAdjustment> {
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

export function commandSalesPriceAdjustment(
  id: string,
  action: PriceAdjustmentAction,
  input: PriceAdjustmentCommand,
  key: string,
  signal?: AbortSignal,
): Promise<SalesPriceAdjustment> {
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

export function salesPriceAdjustmentMetadata(signal?: AbortSignal): Promise<SalesPriceMetadata> {
  return request({ method: 'GET', url: `${resource}/metadata`, signal }, true)
}

export function salesPriceAdjustmentSources(
  partnerId: string,
  warehouseId: string,
  cursor?: string,
  signal?: AbortSignal,
): Promise<PageResult<SalesPriceSource>> {
  return request(
    {
      method: 'GET',
      url: `${resource}/sources`,
      params: { partnerId, warehouseId, cursor, limit: 100 },
      signal,
    },
    true,
  )
}

export function previewSalesPriceAdjustment(
  id: string,
  signal?: AbortSignal,
): Promise<SalesPricePreview> {
  return request({ method: 'POST', url: `${resource}/${id}/preview`, signal }, true)
}
