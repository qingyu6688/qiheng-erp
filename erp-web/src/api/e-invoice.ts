import { downloadFile } from './files'
import { request } from './http'

const root = '/e-invoices'

export type InvoiceKind = 'normal' | 'special'
export type InvoiceStatus =
  'draft' | 'submitted' | 'issued' | 'red_submitted' | 'red_issued' | 'failed'
export interface InvoiceRequest {
  id: string
  requestedBy: string
  buyerName: string
  buyerTaxNo: string
  invoiceKind: InvoiceKind
  status: InvoiceStatus
  amount: string
  taxAmount: string
  invoiceNo: string | null
  redInvoiceNo: string | null
  error: string | null
  version: number
}
export interface InvoiceLine {
  id: string
  goodsName: string
  taxCategoryCode: string
  unitName: string
  quantity: string
  untaxedAmount: string
  taxAmount: string
  shipmentId: string
}
export interface InvoiceFile {
  variant: 'issue' | 'red'
  fileFormat: 'pdf' | 'ofd' | 'xml'
}
export interface InvoiceDetail extends InvoiceRequest {
  lines: InvoiceLine[]
  files: InvoiceFile[]
}
export interface InvoiceCandidate {
  id: string
  goodsName: string
  shipmentNo: string
  businessDate: string
  amount: string
  taxAmount: string
  canIssue: boolean
}
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
export interface InvoicePayable {
  id: string
  sourceId: string
  receiptNo: string
  businessDate: string
  amount: string
  taxAmount: string
}
export interface InvoiceProvider {
  taxNo: string
  enabled: boolean
  tokenConfigured: boolean
  gatewayAvailable: boolean
  version: number
}
export interface InvoiceSku {
  id: string
  code: string
  name: string
  taxCategoryCode: string | null
  version: number
}

function read<T>(path: string, params?: Record<string, unknown>): Promise<T> {
  return request({ method: 'GET', url: root + path, params }, true)
}

function write<T>(path: string, method: 'PUT' | 'POST', data?: unknown, key?: string): Promise<T> {
  return request(
    { method, url: root + path, data, headers: key ? { 'Idempotency-Key': key } : {} },
    true,
  )
}

export function invoiceRequests(cursor?: string): Promise<InvoiceRequest[]> {
  return read('/requests', { cursor })
}
export function invoiceDetail(id: string): Promise<InvoiceDetail> {
  return read('/requests/' + id)
}
export function invoiceCandidates(partnerId: string): Promise<InvoiceCandidate[]> {
  return read('/candidates', { partnerId })
}
export function createInvoice(
  data: { partnerId: string; invoiceKind: InvoiceKind; receivableIds: string[] },
  key: string,
): Promise<{ id: string }> {
  return write('/requests', 'POST', data, key)
}
export function invoiceAction(
  id: string,
  action: 'submit' | 'red-issue',
  version: number,
  key: string,
  reason?: string,
): Promise<{ id: string }> {
  return write(`/requests/${id}/${action}`, 'POST', { version, reason }, key)
}
export function downloadInvoice(invoice: InvoiceDetail, file: InvoiceFile): Promise<void> {
  const label = file.variant === 'red' ? invoice.redInvoiceNo : invoice.invoiceNo
  return downloadFile(
    `${root}/requests/${invoice.id}/files/${file.variant}/${file.fileFormat}`,
    `${label ?? invoice.id}.${file.fileFormat}`,
  )
}
export function inboundInvoices(cursor?: string): Promise<InboundInvoice[]> {
  return read('/inbound', { cursor })
}
export function syncInvoices(from: string, to: string): Promise<{ count: number }> {
  return write('/inbound/sync', 'POST', { from, to })
}
export function payableCandidates(id: string): Promise<InvoicePayable[]> {
  return read(`/inbound/${id}/payables`)
}
export function linkPayable(
  invoice: InboundInvoice,
  payableId: string | null,
  key: string,
): Promise<InboundInvoice> {
  return write(`/inbound/${invoice.id}/match`, 'POST', { version: invoice.version, payableId }, key)
}
export function invoiceProvider(): Promise<InvoiceProvider> {
  return read('/provider')
}
export function saveInvoiceProvider(data: {
  taxNo: string
  enabled: boolean
  token?: string
  version: number
}): Promise<InvoiceProvider> {
  return write('/provider', 'PUT', data)
}
export function testInvoiceProvider(): Promise<{ connected: boolean }> {
  return write('/provider/test', 'POST')
}
export function invoiceSkus(keyword?: string): Promise<InvoiceSku[]> {
  return read('/skus', { keyword })
}
export function saveSkuTaxCategory(
  sku: InvoiceSku,
  taxCategoryCode: string | null,
): Promise<InvoiceSku> {
  return write(`/skus/${sku.id}/tax-category`, 'PUT', { version: sku.version, taxCategoryCode })
}
