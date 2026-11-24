import type { Id, Money, Price, Quantity } from './api'
import type { StockSelection } from './inventory-tracking'

export type SalesReturnMode = 'original' | 'sourceless'
export type RefundMode = 'receipt' | 'sourceless_credit'
export type ReturnCostSource = 'average' | 'last_in' | 'manual'

export interface ReturnCostBasis {
  source: ReturnCostSource
  warehouseId: Id
  skuId: Id
  stockQuantity: Quantity
  stockAmount: Money
  unitCost: Price
  version: string
  baseQuantity: Quantity
  costAmount: Money
}

export interface ReturnCostPreview {
  items: (ReturnCostBasis & { lineId: Id })[]
  totalCost: Money
  notice: string
}

export interface SourcelessReturnLineInput {
  skuId: Id
  unitId: Id
  qty: Quantity
  qualityResult: 'pending' | 'passed' | 'failed'
  unitPrice: Price
  taxRate: string
  stockSelection?: StockSelection | null
  manualUnitCost?: Price | null
  manualCostReason?: string | null
}

export interface SourcelessReturnInput {
  customerId: Id
  warehouseId: Id
  businessDate: string
  reason: string
  legacyReference?: string | null
  salesUserId?: Id | null
  lines: SourcelessReturnLineInput[]
  version?: number
}

export interface SourcelessCreditRefundInput {
  customerId: Id
  cashAccountId: Id
  amount: Money
  businessDate: string
  reason: string
  creditItemIds: Id[]
  version?: number
}

export interface RefundCreditItem {
  id: Id
  partnerId: Id
  sourceId: Id
  sourceLineId: Id
  sourceNo: string
  returnMode: 'sourceless'
  sourceStatus?: string
  warehouseId: Id
  warehouseName: string
  skuName: string
  businessDate: string
  amount: Money
  openAmount: Money
}
