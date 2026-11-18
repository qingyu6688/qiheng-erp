import type { Id } from './api'

export interface Warehouse {
  id: Id
  code: string
  name: string
  locationManaged: boolean
}

export type WorkType = 'putaway' | 'pick' | 'count' | 'move' | 'replenish' | 'receive' | 'approval'

export interface TaskStatistics {
  items: { assigneeId: Id; taskCount: number }[]
  myPendingToday?: number
}

export interface WorkRow {
  id: Id
  no?: string
  documentNo?: string
  shipmentNo?: string
  status: string
  version: number
  warehouseId?: Id
  assigneeId?: Id
  shipmentId?: Id
  shortPick?: boolean | number
  type?: string
}

export interface StockLine {
  id: Id
  skuId: Id
  skuCode: string
  skuName?: string
  batchId?: Id
  batchNo?: string | null
  fromLocationCode?: string
  recommendedLocationCode?: string | null
  toLocationCode?: string
  planQty?: string
  doneQty?: string
  qty?: string
  serialNos?: string[]
  countedQty?: string
  serialManaged?: boolean | number
}

export interface StockWork extends WorkRow {
  locationCode?: string
  snapshotAt?: string | null
  lines: StockLine[]
}

export interface StockConfirmation {
  fromLocationCode: string
  toLocationCode: string
  batchNo: string | null
  qty: string
  serialNos: string[]
}

export interface WaveRow extends WorkRow {
  shipments: WorkRow[]
  lines: (StockLine & { taskId: Id; shipmentNo?: string })[]
}

export interface ApprovalRow extends WorkRow {
  instanceId: Id
  title: string
  businessNo: string
  taskVersion: number
  nodeName?: string
  businessType: string
}

export interface ApprovalInstance {
  id: Id
  businessId: Id
  title: string
  businessNo: string
  summary: Record<string, unknown>
}

export interface Receipt extends WorkRow {
  supplierId: Id
  warehouseId: Id
  businessDate: string
  remark?: string
  buyerUserId?: Id
  actions?: string[]
  allowedActions?: string[]
  lines: ReceiptLine[]
}

export interface InventoryRow extends StockLine {
  warehouseName?: string
  onHandQty: string
  availableQty?: string
  blockedQty: string
  locationCode?: string
}

export interface PackInput {
  shipmentId: Id
  boxes: { boxNo: string; weightKg?: string; volume?: string; lines: PackLineInput[] }[]
}

export interface PackLineInput {
  pickLineId: Id
  qty: string
  serialNos: string[]
  scanCode?: string
}

export interface PackRow extends WorkRow {
  boxNo: string
  weightKg?: string
  volume?: string
}

export interface ReceiptLine extends StockLine {
  orderLineId: Id
  unitId: Id
  quantity?: string
  stockSelection?: {
    batchId?: Id
    batchNo?: string
    productionDate?: string
    expiryDate?: string
    locationId?: Id
    serialNos?: string[]
    serials?: string[]
  }
}

export interface LabelItem {
  type: 'sku' | 'location' | 'batch' | 'box'
  id: Id
  copies: number
}
