import { newIdempotencyKey, request } from './http'
import type { Id, PageResult, Quantity } from '../types/api'

type State = 'active' | 'disabled'
export type ZoneType = 'storage' | 'receiving' | 'shipping' | 'quarantine' | 'return'

interface MasterRow {
  id: Id
  code: string
  name: string
  status: State
  version: number
}

export interface Zone extends MasterRow {
  type: ZoneType
}
export interface Slot extends MasterRow {
  zoneId: Id
  aisle: string | null
  shelf: string | null
  layer: string | null
  position: string | null
  capacityQty: Quantity | null
  mixedSkuAllowed: boolean
  mixedBatchAllowed: boolean
  pickSequence: number
}
export interface WarehouseLayout {
  warehouse: MasterRow & {
    locationManaged: boolean
    defaultReceivingLocationId: Id | null
    defaultShippingLocationId: Id | null
  }
  zones: Zone[]
  locations: Slot[]
}
export type ZoneInput = Omit<Zone, 'id' | 'status' | 'version'> & {
  id?: Id
  enabled: boolean
  version?: number
}
export type SlotInput = Omit<Slot, 'id' | 'status' | 'version'> & {
  id?: Id
  enabled: boolean
  version?: number
}
export interface SlotBalance {
  id: Id
  skuCode: string
  skuName: string
  locationCode: string
  batchNo: string | null
  expiryDate: string | null
  onHandQty: Quantity
  reservedQty: Quantity
  blockedQty: Quantity
  availableQty: Quantity
}
export interface SlotLabel {
  id: Id
  warehouse: string
  code: string
  name: string
}
export interface Brand extends MasterRow {
  sortNo: number
}
export type BrandInput = Omit<Brand, 'id' | 'status' | 'version'> & {
  id?: Id
  enabled: boolean
  version?: number
}
export interface SkuTrackingInput {
  weightKg: string | null
  brandId: Id | null
  batchManaged: boolean
  serialManaged: boolean
  shelfLifeDays: number
  nearExpiryDays: number
  issueStrategy: 'fifo' | 'fefo' | 'manual'
  version: number
}
export interface SkuTracking extends SkuTrackingInput {
  id: Id
  code: string
  name: string
}

const locations = (warehouse: Id): string => '/warehouses/' + warehouse + '/locations'
export const warehouseLayout = (warehouse: Id): Promise<WarehouseLayout> =>
  request({ url: locations(warehouse) }, true)
export const saveZone = (warehouse: Id, data: ZoneInput): Promise<Zone> =>
  request({ method: 'PUT', url: locations(warehouse) + '/zones', data }, true)
export const saveSlot = (warehouse: Id, data: SlotInput): Promise<Slot> =>
  request({ method: 'PUT', url: locations(warehouse), data }, true)
export const generateSlots = (
  warehouse: Id,
  data: {
    zoneId: Id
    prefix: string
    aisles: number
    shelves: number
    layers: number
    positions: number
  },
): Promise<{ count: number }> =>
  request({ method: 'POST', url: locations(warehouse) + '/generate', data }, true)
export const saveWarehouseLayout = (
  warehouse: Id,
  data: { receivingId: Id; shippingId: Id; version: number },
): Promise<void> => request({ method: 'PUT', url: locations(warehouse) + '/settings', data }, true)
export const slotBalances = (
  warehouse: Id,
  params: { locationId?: Id; cursor?: Id; limit: number },
): Promise<SlotBalance[]> => request({ url: locations(warehouse) + '/balances', params }, true)
export const slotLabel = (warehouse: Id, id: Id): Promise<SlotLabel> =>
  request({ url: locations(warehouse) + '/' + id + '/label' }, true)
export const slotBarcode = (warehouse: Id, id: Id): Promise<Blob> =>
  request({ url: locations(warehouse) + '/' + id + '/barcode.png', responseType: 'blob' }, true)

export const brands = (keyword?: string, activeOnly?: boolean): Promise<Brand[]> =>
  request({ url: '/brands', params: { keyword, activeOnly } })
export const saveBrand = (data: BrandInput): Promise<Brand> =>
  request({ method: 'PUT', url: '/brands', data })
export const skuTracking = (id: Id): Promise<SkuTracking> =>
  request({ url: '/skus/' + id + '/tracking' })
export const saveSkuTracking = (id: Id, data: SkuTrackingInput): Promise<SkuTracking> =>
  request({ method: 'PUT', url: '/skus/' + id + '/tracking', data })

export type SupplyCheckType = 'batch_balance' | 'stock_blocks' | 'location_balance'
export interface ReconciliationRun {
  id: Id
  checkType: string
  status: 'running' | 'passed' | 'failed' | 'error'
  diffCount: number
  startedAt: string
  finishedAt: string | null
  errorMessage: string | null
}
export interface ReconciliationDifference {
  id: Id
  objectKey: string
  expectedValue: Quantity
  actualValue: Quantity
}
export const reconciliationRuns = (cursor?: Id): Promise<PageResult<ReconciliationRun>> =>
  request({ url: '/reconciliation-runs', params: { cursor, limit: 30 } }, true)
export const runReconciliation = (checkType: SupplyCheckType): Promise<{ id: Id }> =>
  request(
    {
      method: 'POST',
      url: '/reconciliation-runs',
      data: { checkType },
      headers: { 'Idempotency-Key': newIdempotencyKey() },
    },
    true,
  )
export const reconciliationDifferences = (
  id: Id,
  cursor?: Id,
): Promise<PageResult<ReconciliationDifference>> =>
  request({ url: '/reconciliation-runs/' + id + '/diffs', params: { cursor, limit: 30 } }, true)
