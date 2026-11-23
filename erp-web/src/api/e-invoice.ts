import { downloadFile } from './files'
import { request } from './http'
import type {
  InboundInvoice,
  InboundInvoiceRow,
  InboundInvoiceDetail,
  InboundFilters,
  InboundPage,
  InboundSupplier,
  InboundMatch,
  InboundPayableSource,
  InboundManualInput,
  InboundAllocationInput,
  InboundVoidInput,
  InboundPreview,
} from '../types/inbound-invoices'
export type { InboundInvoice } from '../types/inbound-invoices'

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

function read<T>(path: string, params?: Record<string, unknown>, signal?: AbortSignal): Promise<T> {
  return request({ method: 'GET', url: root + path, params, ...(signal ? { signal } : {}) }, true)
}

function write<T>(
  path: string,
  method: 'PUT' | 'POST',
  data?: unknown,
  key?: string,
  signal?: AbortSignal,
): Promise<T> {
  return request(
    {
      method,
      url: root + path,
      data,
      headers: key ? { 'Idempotency-Key': key } : {},
      ...(signal ? { signal } : {}),
    },
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
export function inboundInvoices(
  cursor?: string,
  filters: InboundFilters = {},
  signal?: AbortSignal,
): Promise<InboundInvoiceRow[]> {
  return read('/inbound', { cursor, ...filters }, signal)
}
export function syncInvoices(
  from: string,
  to: string,
  signal?: AbortSignal,
): Promise<{ count: number }> {
  return write('/inbound/sync', 'POST', { from, to }, undefined, signal)
}
export function inboundInvoice(id: string, signal?: AbortSignal): Promise<InboundInvoiceDetail> {
  return read(`/inbound/${id}`, undefined, signal)
}
export function inboundHistory(
  id: string,
  cursor?: string,
  signal?: AbortSignal,
): Promise<InboundPage<InboundMatch>> {
  return read(`/inbound/${id}/history`, { cursor }, signal)
}
export function inboundSources(
  id: string,
  cursor?: string,
  signal?: AbortSignal,
): Promise<InboundPage<InboundPayableSource>> {
  return read(`/inbound/${id}/sources`, { cursor }, signal)
}
export function inboundSuppliers(
  keyword?: string,
  cursor?: string,
  signal?: AbortSignal,
): Promise<InboundPage<InboundSupplier>> {
  return read('/inbound/suppliers', { keyword, cursor }, signal)
}
export function saveInboundInvoice(
  input: InboundManualInput,
  key: string,
  id?: string,
  signal?: AbortSignal,
): Promise<InboundInvoiceDetail> {
  return write('/inbound' + (id ? '/' + id : ''), id ? 'PUT' : 'POST', input, key, signal)
}
export function previewInboundInvoice(
  id: string,
  input: InboundAllocationInput,
  signal?: AbortSignal,
): Promise<InboundPreview> {
  return write(`/inbound/${id}/preview`, 'POST', input, undefined, signal)
}
export function allocateInboundInvoice(
  id: string,
  input: InboundAllocationInput,
  key: string,
  signal?: AbortSignal,
): Promise<InboundInvoiceDetail> {
  return write(`/inbound/${id}/allocations`, 'POST', input, key, signal)
}
export function voidInboundInvoice(
  id: string,
  input: InboundVoidInput,
  key: string,
  signal?: AbortSignal,
): Promise<InboundInvoiceDetail> {
  return write(`/inbound/${id}/void`, 'POST', input, key, signal)
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
