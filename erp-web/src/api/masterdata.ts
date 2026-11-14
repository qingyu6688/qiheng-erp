import type { PageResult } from '../types/api'
import type {
  Warehouse,
  WarehouseInput,
  WarehouseCommand,
  WarehouseQuery,
} from '../types/warehouse'
import { request } from './http'

export function warehouses(params: WarehouseQuery): Promise<PageResult<Warehouse>> {
  return request({ method: 'GET', url: '/warehouses', params }, true)
}

export function warehouseDetail(id: string): Promise<Warehouse> {
  return request({ method: 'GET', url: '/warehouses/' + encodeURIComponent(id) }, true)
}

export function createWarehouse(data: WarehouseInput, key: string): Promise<Warehouse> {
  return request(
    { method: 'POST', url: '/warehouses', data, headers: { 'Idempotency-Key': key } },
    true,
  )
}

export function updateWarehouse(
  id: string,
  data: WarehouseInput,
  version: number,
): Promise<Warehouse> {
  return request(
    { method: 'PUT', url: '/warehouses/' + encodeURIComponent(id), data: { ...data, version } },
    true,
  )
}

export function changeWarehouseStatus(
  id: string,
  action: WarehouseCommand,
  version: number,
  key: string,
): Promise<Warehouse> {
  return request(
    {
      method: 'POST',
      url: '/warehouses/' + encodeURIComponent(id) + '/' + action,
      data: { version },
      headers: { 'Idempotency-Key': key },
    },
    true,
  )
}

export function commandResult(
  operation: string,
  key: string,
): Promise<{ status: 'succeeded' | 'processing'; response?: Warehouse }> {
  return request({ method: 'GET', url: '/command-results', params: { operation, key } }, true)
}
