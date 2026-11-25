import type { WorkflowSelections } from './workflow'

export type PriceAdjustmentStatus =
  'draft' | 'pending' | 'approved' | 'rejected' | 'posted' | 'voided' | 'reversed'
export type PriceAdjustmentAction = 'submit' | 'approve' | 'reject' | 'post' | 'void'

export interface PriceAdjustmentLine {
  id: string
  receiptLineId: string
  skuId: string
  skuName: string
  unitId: string
  unitName: string
  receiptNo: string
  sourceQty: string
  sourceBaseQty: string
  sourceReturnedQty: string
  inputUnitPrice: string | null
  inputAmount: string | null
  oldUnitPrice: string
  newUnitPrice: string
  amount: string
  untaxedAmount: string
  taxAmount: string
  stockCost: string
  issuedCost: string
  costTaxInclusive: boolean
}

export interface PriceCostPart {
  warehouseId: string
  warehouseName?: string
  inStockQty: string
  stockAmount: string
  issuedAmount: string
  adjustmentLineId?: string
}

export interface PriceAdjustment {
  id: string
  documentNo: string
  kind: 'purchase'
  partnerId: string
  partnerName: string
  warehouseId: string
  warehouseName: string
  businessDate: string
  reason: string
  status: PriceAdjustmentStatus
  version: number
  amount: string
  taxAmount: string
  untaxedAmount: string
  stockCost: string
  issuedCost: string
  createdBy: string
  approvedBy?: string | null
  wfInstanceId?: string
  lines: PriceAdjustmentLine[]
  costAllocations: PriceCostPart[]
  allowedActions: string[]
  canPreviewCost?: boolean
}

export interface PriceAdjustmentInput {
  version?: number
  partnerId: string
  warehouseId: string
  businessDate: string
  reason: string
  lines: { receiptLineId: string; newUnitPrice?: string; adjustmentAmount?: string }[]
}

export interface PriceAdjustmentCommand {
  version: number
  reason?: string
  workflowAssignees?: WorkflowSelections
}

export interface PriceSource {
  id: string
  receiptId: string
  receiptNo: string
  skuId: string
  skuName: string
  unitId: string
  unitName: string
  returnableQty: string
  baseQty: string
  pendingReturnBaseQty: string
  payableAmount: string
  taxAmount: string
  priceAdjustedAmount: string
  priceAdjustedTax: string
}

export interface PricePreview {
  notice: string
  items: {
    lineId: string
    receiptLineId: string
    costDelta: string
    allocations: PriceCostPart[]
  }[]
}

export const priceStatusLabels: Record<PriceAdjustmentStatus, string> = {
  draft: '草稿',
  pending: '待审批',
  approved: '已批准',
  rejected: '已驳回',
  posted: '已过账',
  voided: '已作废',
  reversed: '已冲销',
}
export const priceActionLabels: Record<PriceAdjustmentAction, string> = {
  submit: '提交审批',
  approve: '批准',
  reject: '驳回',
  post: '过账',
  void: '作废',
}
