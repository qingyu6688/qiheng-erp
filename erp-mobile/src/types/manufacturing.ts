import type { Id } from './api'

export interface ProductionMaterial {
  id: Id
  componentSkuId: Id
  skuCode?: string
  skuName?: string
  issueWarehouseId?: Id | null
  requiredQty: string
  issuedQty: string
  returnedQty: string
  substitutes?: { substituteSkuId: Id; skuName?: string; skuCode?: string; ratio?: string }[]
}

export interface MobileProductionOrder {
  id: Id
  documentNo: string
  status: string
  productName?: string
  skuName?: string
  skuCode?: string
  issueWarehouseId: Id
  issueWarehouseName?: string
  issueWarehouseCode?: string
  materials?: ProductionMaterial[]
}

export interface IssueSelection {
  batchId?: Id
  locationId?: Id
  serials?: string[]
}

export interface MobileIssueLine {
  materialId: Id
  skuId: Id
  skuName?: string
  qty: string
  stockSelection?: IssueSelection
}

export interface MobileProductionIssue {
  id: Id
  documentNo: string
  warehouseId: Id
  businessDate: string
  status: string
  version: number
  allowedActions: string[]
  lines?: MobileIssueLine[]
}
