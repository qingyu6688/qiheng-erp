import { request } from './http'
import type { InventorySnapshot } from '../types/inventory'

export function inventoryBalances(
  params: Record<string, unknown>,
  signal?: AbortSignal,
): Promise<InventorySnapshot> {
  return request({ method: 'GET', url: '/inventory-balances', params, signal }, true)
}
