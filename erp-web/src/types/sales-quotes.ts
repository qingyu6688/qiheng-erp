import type { PageResult } from './api'
import type { PaymentTerm } from './payment-terms'

export type SalesQuoteStatus = 'draft' | 'pending' | 'issued' | 'rejected' | 'converted' | 'expired' | 'voided'
export type SalesQuoteAction = 'submit' | 'convert' | 'void'
export interface QuoteOrderLine {
  skuId: string
  unitId: string
  warehouseId?: string
  qty: string
  unitPrice?: string
  lineDiscountAmount?: string
  taxRate?: string
}
export interface QuoteOrderInput {
  customerId: string
  businessDate: string
  deliveryDate?: string
  priceTaxInclusive: boolean
  orderDiscountAmount?: string
  lines: QuoteOrderLine[]
  fees?: { feeType: string; bearer: string; amount: string; taxRate?: string }[]
  remark?: string
  salesUserId?: string
  deliveryAreaId?: string
  deliveryAddress?: string
  extJson?: Record<string, unknown>
  paymentTerms?: PaymentTerm | null
  inheritPaymentTerms?: boolean
}
export interface SalesQuoteInput {
  order: QuoteOrderInput
  validTo: string
  opportunityId?: string
  version?: number
}
export interface SalesQuoteCommand {
  version: number
  reason?: string
  businessDate?: string
  workflowAssignees?: Record<string, string[]>
}
export interface SalesQuoteSummary {
  id: string
  documentNo: string
  customerId: string
  customerName: string
  businessDate: string
  validTo: string
  status: SalesQuoteStatus
  version: number
  totalAmount: string
}
export interface QuotePricingLine {
  lineNo: number
  skuId: string
  unitId: string
  warehouseId?: string | null
  qty: string
  baseQty: string
  unitRate: string
  unitPrice: string
  lineDiscountAmount: string
  totalAmount: string
  taxRate: string
  untaxedAmount: string
  taxAmount: string
}
export interface QuotePricing {
  priceTaxInclusive: boolean
  goodsAmount: string
  orderDiscountAmount: string
  lineDiscountAmount: string
  feeAmount: string
  totalAmount: string
  untaxedAmount: string
  taxAmount: string
  lines: QuotePricingLine[]
  warnings: { lineNo: number; type: string; message: string }[]
}
export interface SalesQuote extends SalesQuoteSummary {
  input: QuoteOrderInput
  pricing: QuotePricing
  remark?: string | null
  opportunityId?: string | null
  opportunityName?: string
  wfInstanceId: string
  belowMinPrice: boolean
  belowMinPricePolicy: 'warn' | 'approval' | 'block'
  convertedOrderId?: string | null
  convertedOrderNo?: string
  allowedActions: ('update' | SalesQuoteAction)[]
  lines: { id: string; lineNo: number; skuId: string; skuName: string; unitId: string; unitName: string; warehouseName?: string }[]
}
export interface QuoteChoice { id: string; name: string }
export interface QuoteMetadata { customers: QuoteChoice[]; skus: QuoteChoice[]; warehouses: QuoteChoice[]; opportunities: QuoteChoice[] }
export interface QuoteUnit { unitId: string; unitName: string; rateToBase: string; isBase: boolean | number }
export type SalesQuotePage = PageResult<SalesQuoteSummary>
export const quoteStatusLabels: Record<SalesQuoteStatus, string> = {
  draft: '草稿', pending: '审批中', issued: '报价有效', rejected: '已驳回', converted: '已转单', expired: '已失效', voided: '已作废',
}
export const quoteActionLabels: Record<SalesQuoteAction, string> = { submit: '确认报价', convert: '转销售订单', void: '作废报价' }
