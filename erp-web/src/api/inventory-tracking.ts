import { request } from './http'
import type { PageResult } from '../types/api'
import type {
  BatchTrace, SerialTrace, StockMovementTrace, TrackedBatch, TrackingOptions,
} from '../types/inventory-tracking'

export function trackingOptions(warehouseId: string, skuId: string, signal?: AbortSignal): Promise<TrackingOptions> {
  return request({ method: 'GET', url: '/inventory-tracking/options', params: { warehouseId, skuId }, signal }, true)
}

export function trackedBatches(params: {
  warehouseId?: string; skuId?: string; keyword?: string; expiringWithinDays?: number; cursor?: string
}): Promise<PageResult<TrackedBatch>> {
  return request({ method: 'GET', url: '/batches', params }, true)
}

export function batchTrace(id: string, cursor?: string): Promise<BatchTrace> {
  return request({ method: 'GET', url: '/batches/' + encodeURIComponent(id) + '/trace', params: { cursor } }, true)
}

export function serialTrace(skuId: string, serialNo: string, cursor?: string): Promise<SerialTrace> {
  return request({ method: 'GET', url: '/serials/trace', params: { skuId, serialNo, cursor } }, true)
}

export function customerTrace(partnerId: string, skuId?: string, cursor?: string): Promise<PageResult<StockMovementTrace>> {
  return request({ method: 'GET', url: '/trace/customer', params: { partnerId, skuId, cursor } }, true)
}
