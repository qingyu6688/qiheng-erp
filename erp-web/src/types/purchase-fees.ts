import type { WorkflowSelections } from './workflow'
import type {
  PriceAdjustmentStatus,
  PriceAdjustmentAction,
  PriceCostPart,
  PriceSource,
} from './price-adjustments'

export type PurchaseFeeStatus = PriceAdjustmentStatus
export type PurchaseFeeAction = PriceAdjustmentAction
export type AllocationMethod = 'amount' | 'quantity' | 'weight'
export interface PurchaseFeeLine {
  id: string
  feeType: 'freight' | 'loading' | 'other'
  amount: string
  untaxedAmount: string
  taxAmount: string
  taxRate: string
  remark?: string | null
  dueDate?: string | null
}
export interface PurchaseFeeAllocation {
  id: string
  receiptLineId: string
  receiptId: string
  receiptNo: string
  skuName: string
  unitName: string
  sourceWarehouseName: string
  sourceBaseQty: string
  amount: string
  taxAmount: string
  costAmount: string
  stockCost: string
  issuedCost: string
}
export interface PurchaseFeeCost extends PriceCostPart {
  allocationId?: string
}
export interface PurchaseFee {
  id: string
  documentNo: string
  sourceKind: 'manual' | 'receipt'
  sourceReceiptId?: string
  partnerId: string
  partnerName: string
  businessDate: string
  allocationMethod: AllocationMethod
  reason: string
  status: PurchaseFeeStatus
  version: number
  amount: string
  untaxedAmount: string
  taxAmount: string
  stockCost: string
  issuedCost: string
  dueDate?: string | null
  wfInstanceId?: string
  lines: PurchaseFeeLine[]
  allocations: PurchaseFeeAllocation[]
  costAllocations: PurchaseFeeCost[]
  allowedActions: string[]
  canPreviewCost?: boolean
}
export type PurchaseFeeSummary = Pick<
  PurchaseFee,
  'id' | 'documentNo' | 'sourceKind' | 'partnerName' | 'businessDate' | 'status' | 'amount'
>

export interface PurchaseFeeInput {
  version?: number
  partnerId: string
  businessDate: string
  allocationMethod: AllocationMethod
  reason: string
  fees: {
    feeType: 'freight' | 'loading' | 'other'
    amount: string
    taxRate: string
    remark?: string
  }[]
  receiptLineIds: string[]
}
export interface PurchaseFeeCommand {
  version: number
  reason?: string
  workflowAssignees?: WorkflowSelections
}
export type PurchaseFeeSource = PriceSource
export interface PurchaseFeePreview {
  notice: string
  items: {
    allocationId: string
    receiptLineId: string
    amount: string
    taxAmount: string
    costAmount: string
    allocations: PurchaseFeeCost[]
  }[]
}
export const allocationMethodLabels: Record<AllocationMethod, string> = {
  amount: '按金额',
  quantity: '按基本数量',
  weight: '按重量',
}
export const feeTypeLabels = { freight: '运费', loading: '装卸费', other: '其他费用' }
export {
  priceStatusLabels as feeStatusLabels,
  priceActionLabels as feeActionLabels,
} from './price-adjustments'
