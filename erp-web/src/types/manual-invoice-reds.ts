export interface InvoiceRegistrationLine {
  id: string
  receivableId: string
  amount: string
  sourceNo?: string | null
  sourceType?: string | null
  sourceId?: string | null
  skuName?: string | null
}

export interface InvoiceRegistration {
  id: string
  invoiceType: 'sales' | 'purchase'
  invoiceNo: string
  invoiceDate: string
  partnerName: string
  amount: string
  taxAmount: string
  status: 'registered' | 'voided'
  version: number
  einvoiceRequestId: string | null
  manualRedId?: string | null
  manualRedStatus?: string | null
  lines: InvoiceRegistrationLine[]
}

export interface ManualInvoiceRed {
  id: string
  invoiceId: string
  invoiceNo: string
  redInvoiceNo: string
  redInvoiceDate: string
  amount: string
  taxAmount: string
  reason: string
  status: 'pending' | 'confirmed' | 'cancelled'
  version: number
  createdBy: string
  confirmedBy?: string | null
  confirmedAt?: string | null
  lines: InvoiceRegistrationLine[]
  allowedActions: ('confirm' | 'cancel')[]
}

export interface ManualInvoiceRedInput {
  invoiceId: string
  sourceVersion: number
  redInvoiceNo: string
  redInvoiceDate: string
  amount: string
  taxAmount: string
  reason: string
}

export const manualRedStatusLabels: Record<ManualInvoiceRed['status'], string> = {
  pending: '待财务确认',
  confirmed: '红字已确认',
  cancelled: '已撤回',
}
