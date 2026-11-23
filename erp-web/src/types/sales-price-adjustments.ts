import type { PriceAdjustment, PriceAdjustmentLine } from './price-adjustments'

export interface SalesRedInvoice {
  invoiceId: string
  invoiceNo: string
  status: string
  einvoiceRequestId: string | null
  electronicStatus: string | null
  redInvoiceNo: string | null
  redComplete: boolean | number
}

export interface SalesPriceAdjustmentLine extends Omit<
  PriceAdjustmentLine,
  'receiptLineId' | 'receiptNo' | 'stockCost' | 'issuedCost' | 'costTaxInclusive'
> {
  shipmentLineId: string
  shipmentNo: string
  oldAmount: string
  oldTax: string
  needsRed: boolean
  redInvoices?: SalesRedInvoice[]
}

export interface SalesPriceAdjustment extends Omit<
  PriceAdjustment,
  'kind' | 'lines' | 'stockCost' | 'issuedCost' | 'costAllocations' | 'canPreviewCost'
> {
  kind: 'sales'
  lines: SalesPriceAdjustmentLine[]
}

export interface SalesPriceAdjustmentInput {
  version?: number
  partnerId: string
  warehouseId: string
  businessDate: string
  reason: string
  lines: { shipmentLineId: string; newUnitPrice?: string; adjustmentAmount?: string }[]
}

export interface SalesPriceSource {
  id: string
  shipmentId: string
  shipmentNo: string
  customerId: string
  warehouseId: string
  skuId: string
  skuName: string
  unitId: string
  unitName: string
  version: number
  returnableQty: string
  amount: string
  taxAmount: string
}

export interface SalesPricePreview {
  notice: string
  items: {
    lineId: string
    shipmentLineId: string
    amount: string
    taxAmount: string
    remainingQuantity: string
    needsRed: boolean
  }[]
}

export interface SalesPriceMetadata {
  partners: { id: string; name: string }[]
  warehouses: { id: string; name: string }[]
}
