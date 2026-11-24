import type { PageResult } from './api'

export interface StockSelection {
  batchId?: string
  batchNo?: string
  productionDate?: string
  expiryDate?: string
  locationId?: string
  serials?: string[]
}

export interface StockLocationOption {
  id: string
  code: string
  name: string
  type: 'storage' | 'receiving' | 'shipping' | 'quarantine' | 'return'
}

export interface BatchBalance {
  warehouseId: string
  warehouseName: string
  locationKey: string
  locationCode?: string
  onHandQty: string
  blockedQty: string
  reservedQty: string
  availableQty: string
}

export interface TrackedBatch {
  id: string
  skuId: string
  skuCode: string
  skuName: string
  batchNo: string
  productionDate?: string
  expiryDate?: string
  status: 'normal' | 'expired' | 'frozen'
  expiryState: 'valid' | 'near_expiry' | 'expired'
  balances?: BatchBalance[]
}

export interface TrackingOptions {
  batchManaged: boolean
  serialManaged: boolean
  locationManaged: boolean
  shelfLifeDays: number
  issueStrategy: 'fifo' | 'fefo' | 'manual'
  defaultReceivingLocationId?: string
  defaultShippingLocationId?: string
  locations: StockLocationOption[]
  batches: TrackedBatch[]
}

export interface InspectionTrace {
  id: string
  inspectionNo: string
  result: string
  status: string
  qualifiedQty: string
  unqualifiedQty: string
  concessionQty: string
}

export interface StockMovementTrace {
  id: string
  warehouseName: string
  skuName: string
  locationCode?: string
  batchId?: string
  batchNo?: string
  movementType: string
  postingAction: 'post' | 'reverse'
  onHandDelta: string
  blockedDelta: string
  businessDate: string
  sourceType: string
  sourceId?: string
  sourceNo?: string
  returnMode?: 'original' | 'sourceless'
  returnSourceId?: string
  returnSourceNo?: string
  returnCostSource?: 'average' | 'last_in' | 'manual'
  receiptNo?: string
  purchaseReturnNo?: string
  supplierName?: string
  shipmentNo?: string
  shipmentStatus?: string
  signDate?: string
  customerName?: string
  confirmedQty?: string
  currentStatus?: string
  priorStatus?: string
  serials: string[]
  inspections?: InspectionTrace[]
}

export interface BatchTrace extends PageResult<StockMovementTrace> {
  batch: TrackedBatch
  balances: BatchBalance[]
}

export interface SerialTrace extends PageResult<StockMovementTrace> {
  serial: {
    id: string
    serialNo: string
    status?: string
  }
}
