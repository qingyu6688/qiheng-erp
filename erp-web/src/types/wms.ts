import type { Id, Quantity } from './api'

export type WmsTaskStatus = 'created' | 'assigned' | 'in_progress' | 'done' | 'cancelled'
export type WmsTaskMode = 'off' | 'optional'

export interface WmsWarehouse {
  id: Id
  code: string
  name: string
  taskMode: WmsTaskMode
  locationManaged: boolean
}

export interface WmsTaskLine {
  id: Id
  skuId: Id
  skuCode: string
  skuName: string
  batchNo: string | null
  fromLocationCode: string
  recommendedLocationCode: string | null
  planQty: Quantity
  doneQty: Quantity
}

export interface WmsTaskSummary {
  id: Id
  no: string
  warehouseId: Id
  sourceType: 'goods_receipt' | 'sales_return'
  sourceId: Id
  status: WmsTaskStatus
  assigneeId: Id | null
  version: number
}

export interface WmsTask extends WmsTaskSummary {
  lines: WmsTaskLine[]
}

export interface WmsTaskFilter {
  warehouseId?: Id
  status?: WmsTaskStatus
  cursor?: Id
  limit: number
}

export interface WmsLineConfirmation {
  fromLocationCode: string
  toLocationCode: string
  batchNo: string | null
  qty: Quantity
  serialNos: string[]
  version: number
}
