import type { StatusTone } from './status'

export type InboundMatchStatus = 'pending' | 'partial' | 'matched' | 'difference'
export type InboundSource = 'manual' | 'einv'
export interface InboundInvoice {
  id: string
  invoiceNo: string
  sellerName: string
  sellerTaxNo: string
  invoiceDate: string
  amount: string
  taxAmount: string
  status: 'normal' | 'voided' | 'red_issued'
  checked: boolean
  payableId: string | null
  version: number
}
export interface InboundInvoiceRow extends InboundInvoice {
  source: InboundSource
  supplierId: string | null
  receivedDate: string | null
  remark: string | null
  matchNotice: string | null
  untaxedAmount: string
  allocatedUntaxedAmount: string
  allocatedTaxAmount: string
  remainingUntaxedAmount: string
  remainingTaxAmount: string
  untaxedDifference: string
  taxDifference: string
  absoluteDifference: string
  fullyAllocated: boolean
  matchStatus: InboundMatchStatus
}
export interface InboundPage<T> {
  items: T[]
  nextCursor: string | null
}
export interface InboundAllocation {
  payableId: string
  invoiceUntaxedAmount: string
  invoiceTaxAmount: string
  payableUntaxedAmount: string
  payableTaxAmount: string
}
export interface InboundMatch extends InboundAllocation {
  id: string
  sourceType: string
  sourceId: string
  sourceLineId: string
  sourceNo: string | null
  businessDate: string
  sourceAmount: string
  sourceUntaxedAmount: string
  sourceTaxAmount: string
  untaxedDifference: string
  taxDifference: string
  differenceAmount: string
  matchedAt: string
  matchedDate: string
  releasedAt: string | null
  releasedDate: string | null
  releasedReason: string | null
  legacyMigrated: boolean
}
export interface InboundInvoiceDetail extends InboundInvoiceRow {
  allocations: InboundMatch[]
  history: InboundPage<InboundMatch>
  tolerance: string
}
export interface InboundSupplier {
  id: string
  code: string
  name: string
  taxNo: string | null
}
export interface InboundPayableSource {
  id: string
  supplierId: string
  supplierName: string
  sourceType: string
  sourceId: string
  sourceLineId: string
  receiptNo: string
  originalReceiptId: string | null
  originalReceiptNo: string | null
  businessDate: string
  warehouseId: string | null
  skuId: string | null
  basisUntaxedAmount: string
  basisTaxAmount: string
  invoicedUntaxedAmount: string
  invoicedTaxAmount: string
  availableUntaxedAmount: string
  availableTaxAmount: string
  invoicingStatus: 'uninvoiced' | 'partial' | 'invoiced'
}
export interface InboundManualInput {
  version?: number
  supplierId: string
  invoiceNo: string
  sellerTaxNo: string
  sellerName?: string
  invoiceDate: string
  receivedDate: string
  amount: string
  taxAmount: string
  remark?: string
}
export interface InboundAllocationInput {
  version: number
  lines: InboundAllocation[]
}
export interface InboundVoidInput {
  version: number
  reason: string
}
export interface InboundPreview {
  supplierId: string | null
  tolerance: string
  allocatedUntaxedAmount: string
  allocatedTaxAmount: string
  remainingUntaxedAmount: string
  remainingTaxAmount: string
  untaxedDifference: string
  taxDifference: string
  absoluteDifference: string
  fullyAllocated: boolean
  matchStatus: InboundMatchStatus
  notice: string
  items: (InboundAllocation & {
    untaxedDifference: string
    taxDifference: string
    payableStatus: string
  })[]
}
export interface InboundFilters {
  matchStatus?: InboundMatchStatus
  source?: InboundSource
  keyword?: string
}
export const inboundMatchLabels: Record<InboundMatchStatus, string> = {
  pending: '待核对',
  partial: '部分核对',
  matched: '已核对',
  difference: '有差异',
}
export const inboundSourceLabels: Record<InboundSource, string> = {
  manual: '手工登记',
  einv: '数电同步',
}
export const inboundStatusLabels: Record<InboundInvoice['status'], string> = {
  normal: '正常',
  voided: '已作废',
  red_issued: '已红冲',
}
export function inboundMatchTone(status: InboundMatchStatus): StatusTone {
  return status === 'matched'
    ? 'success'
    : status === 'difference'
      ? 'warning'
      : status === 'partial'
        ? 'info'
        : 'neutral'
}
