import { request } from './http'
import type { PageResult } from '../types/api'
import type { StockSelection } from '../types/inventory-tracking'

export type StockMiscDirection = 'in' | 'out'
export type StockMiscStatus = 'draft' | 'pending' | 'approved' | 'posted' | 'reversed' | 'voided'

export interface StockMiscLine {
  id: string
  skuId: string
  skuName?: string
  unitId: string
  unitName?: string
  qty: string
  baseQty: string
  inputUnitCost?: string
  unitCost?: string
  amount?: string
  zeroCostReason?: string
  batchId?: string
  locationKey?: string
  originalLoanLineId?: string
  stockSelection?: StockSelection | null
}

export interface LoanOutstanding {
  id: string
  skuId: string
  skuName?: string
  unitId: string
  baseQty: string
  remainingQty: string
  stockSelection?: StockSelection | null
}

export interface StockMiscDocument {
  id: string
  documentNo: string
  direction: StockMiscDirection
  purpose: string
  version: number
  status: StockMiscStatus
  warehouseId: string
  warehouseName?: string
  partnerId?: string
  partnerName?: string
  incomeExpenseItemId: string
  businessDate: string
  expectedReturnDate?: string
  originalLoanId?: string
  remark?: string
  allowedActions?: ('update' | StockMiscAction)[]
  lines?: StockMiscLine[]
}

export type StockMiscAction = 'submit' | 'approve' | 'reject' | 'post' | 'void'

const path = '/stock-misc-docs'

export function listStockMisc(direction: StockMiscDirection, params: { status?: string; cursor?: string; keyword?: string } = {}): Promise<PageResult<StockMiscDocument>> {
  return request({ method: 'GET', url: path, params: { direction, ...params } }, true)
}

export function stockLoans(params: { cursor?: string; keyword?: string } = {}): Promise<PageResult<StockMiscDocument>> {
  return request({ method: 'GET', url: path + '/loans', params }, true)
}

export function stockLoanLines(id: string): Promise<LoanOutstanding[]> {
  return request({ method: 'GET', url: path + '/loans/' + id + '/outstanding' }, true)
}

export function getStockMisc(id: string): Promise<StockMiscDocument> {
  return request({ method: 'GET', url: path + '/' + id }, true)
}

export function saveStockMisc(data: object, id: string | undefined, key: string): Promise<StockMiscDocument> {
  return request({ method: id ? 'PUT' : 'POST', url: path + (id ? '/' + id : ''), data, headers: { 'Idempotency-Key': key } }, true)
}

export function changeStockMisc(id: string, action: StockMiscAction, data: object, key: string): Promise<StockMiscDocument> {
  return request({ method: 'POST', url: path + '/' + id + '/' + action, data, headers: { 'Idempotency-Key': key } }, true)
}
