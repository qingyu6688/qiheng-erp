import { AxiosError, AxiosHeaders, CanceledError } from 'axios'
import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios'
import type { InventoryBalance } from '../types/inventory'
import type { ReportResult } from '../types/reporting'
import { accounts, demoSession, documents, inventoryRows, money, partners, people, products, snapshotDate, snapshotTime, warehouses } from '../demo/data'
import type { DemoDocument } from '../demo/data'
import { demoReports } from '../demo/scope'

type Params = Record<string, unknown>
type Row = Record<string, unknown>
const sum = (rows: Row[], key: string): number => rows.reduce((value, row) => value + Number(row[key] ?? 0), 0)

function filterRows<T extends Row>(rows: T[], params: Params): T[] {
  return rows.filter((row) => {
    const keyword = String(params.keyword ?? '').trim().toLowerCase()
    if (keyword && !JSON.stringify(row).toLowerCase().includes(keyword)) return false
    for (const key of ['status', 'partnerId', 'warehouseId', 'skuId', 'cashAccountId', 'businessUserId', 'fulfillmentStatus', 'settlementStatus']) {
      if (params[key] && String(row[key] ?? '') !== String(params[key])) return false
    }
    const date = String(row.businessDate ?? '')
    return !date || ((!params.dateFrom || date >= String(params.dateFrom)) && (!params.dateTo || date <= String(params.dateTo)))
  })
}

function paginate<T>(rows: T[], params: Params): { items: T[]; nextCursor: string | null; total: number } {
  const offset = Math.max(0, Number(params.cursor) || 0)
  const limit = Math.max(1, Math.min(200, Number(params.limit) || 20))
  return { items: rows.slice(offset, offset + limit), nextCursor: offset + limit < rows.length ? String(offset + limit) : null, total: rows.length }
}

function listDocuments(kind: string, params: Params): Row {
  const rows = documents.filter((row) => row.kind === kind).sort((a, b) => b.businessDate.localeCompare(a.businessDate) || b.id.localeCompare(a.id))
  const statusCounts: Record<string, number> = {}
  for (const row of filterRows(rows, { ...params, status: undefined })) statusCounts[row.status] = (statusCounts[row.status] ?? 0) + 1
  return { ...paginate(filterRows(rows, params), params), statusCounts }
}

function inventory(params: Params): Row {
  const rows = filterRows(inventoryRows().map((row) => ({ ...row })), params)
    .filter((row) => (!params.hideZero || Number(row.onHandQty) !== 0) && (!params.shortageOnly || Number(row.pendingDemandQty) > Number(row.availableQty)))
  return { ...paginate(rows, params), asOf: snapshotTime, summary: { costAmount: money(sum(rows, 'costAmount')), blockedQty: '0', shortageCount: rows.filter((row) => Number(row.pendingDemandQty) > Number(row.availableQty)).length, unreceivedCount: 0, unreceivedCostAmount: '0.00' } }
}

function lineReport(row: DemoDocument): Row {
  const product = products.find((item) => item.id === row.productId)!
  return { ...row, skuId: product.id, skuCode: product.code, skuName: product.name, qty: String(row.quantity), amount: row.totalAmount, costAmount: money(row.quantity * product.cost), sourceNo: row.documentNo, sourceType: row.kind === 'shipments' ? 'shipment' : 'goods_receipt', sourceId: row.id, sourceCount: '1', brandName: '柒衡办公（示例）' }
}

function grouped(rows: Row[], by: string): Row[] {
  const keys: Record<string, string[]> = { customer: ['partnerName'], sku: ['skuCode', 'skuName'], department: ['departmentName'], business_user: ['businessUserName'], brand: ['brandName'] }
  const fields = keys[by]
  if (!fields) return rows
  const groups = new Map<string, Row[]>()
  for (const row of rows) {
    const key = fields.map((field) => String(row[field])).join('|')
    groups.set(key, [...(groups.get(key) ?? []), row])
  }
  return [...groups.values()].map((items) => ({
    ...Object.fromEntries(fields.map((field) => [field, items[0]![field]])),
    ...Object.fromEntries(['qty', 'amount', 'untaxedAmount', 'taxAmount', 'costAmount', 'openAmount', 'age030', 'age3160', 'age6190', 'age91180', 'ageOver180', 'aging0', 'aging1', 'aging2', 'aging3', 'aging4', 'onHandQty', 'reservedQty', 'blockedQty', 'availableQty'].map((field) => [field, money(sum(items, field))])), sourceCount: String(items.length),
  }))
}

function agingRows(kind: 'shipments' | 'goods-receipts', params: Params): Row[] {
  return documents.filter((row) => row.kind === kind && row.businessDate <= String(params.agingDate || snapshotDate)).map((row) => {
    const order = documents.find((order) => order.id === row.relatedOrderId)!
    const open = Number(row.totalAmount) - Number(order.allocatedAmount)
    const days = Math.max(0, Math.floor((Date.parse(String(params.agingDate || snapshotDate)) - Date.parse(row.businessDate)) / 86400000))
    return { ...lineReport(row), agingDate: row.businessDate, direction: kind === 'shipments' ? 'D' : 'C', openAmount: money(open), ageDays: days,
      age030: days <= 30 ? money(open) : '0.00', age3160: days > 30 && days <= 60 ? money(open) : '0.00', age6190: days > 60 && days <= 90 ? money(open) : '0.00', age91180: days > 90 && days <= 180 ? money(open) : '0.00', ageOver180: days > 180 ? money(open) : '0.00' }
  }).filter((row) => Number(row.openAmount) > 0).map((row) => ({ ...row, aging0: row.age030, aging1: row.age3160, aging2: row.age6190, aging3: row.age91180, aging4: row.ageOver180 }))
}

function inventoryFlow(params: Params): Row[] {
  const movements = documents.filter((row) => ['goods-receipts', 'shipments'].includes(row.kind))
  if (params.detail) return filterRows(movements.map((row) => {
    const incoming = row.kind === 'goods-receipts'
    const line = lineReport(row)
    return { ...line, direction: incoming ? 'in' : 'out', postingAction: 'post', qty: String((incoming ? 1 : -1) * row.quantity), costAmount: money((incoming ? 1 : -1) * Number(line.costAmount)), blockedQty: '0', inQty: incoming ? String(row.quantity) : '0', outQty: incoming ? '0' : String(row.quantity), baseQty: String(row.quantity) }
  }), params)
  return products.map((product) => {
    const rows = movements.filter((row) => row.productId === product.id)
    const before = rows.filter((row) => params.dateFrom && row.businessDate < String(params.dateFrom))
    const selected = filterRows(rows, { dateFrom: params.dateFrom, dateTo: params.dateTo })
    const quantity = (items: DemoDocument[], kind: string): number => items.filter((row) => row.kind === kind).reduce((value, row) => value + row.quantity, 0)
    const opening = 100 + quantity(before, 'goods-receipts') - quantity(before, 'shipments')
    const incoming = quantity(selected, 'goods-receipts'), outgoing = quantity(selected, 'shipments')
    return { warehouseId: '101', warehouseName: warehouses[0]!.name, skuId: product.id, skuCode: product.code, skuName: product.name, openingQty: String(opening), inQty: String(incoming), outQty: String(outgoing), closingQty: String(opening + incoming - outgoing), openingCost: money(opening * product.cost), inCost: money(incoming * product.cost), outCost: money(outgoing * product.cost), closingCost: money((opening + incoming - outgoing) * product.cost) }
  })
}

function cashRows(): Row[] {
  let balance = 100000
  return documents.filter((row) => ['receipts', 'payments'].includes(row.kind)).sort((a, b) => a.businessDate.localeCompare(b.businessDate) || a.id.localeCompare(b.id)).map((row) => {
    const opening = balance
    const receipt = row.kind === 'receipts' ? Number(row.totalAmount) : 0
    const payment = row.kind === 'payments' ? Number(row.totalAmount) : 0
    balance += receipt - payment
    return { ...row, sourceNo: row.documentNo, sourceType: row.kind === 'receipts' ? 'receipt' : 'payment', sourceId: row.id, openingBalance: money(opening), runningBalance: money(balance), receiptAmount: money(receipt), paymentAmount: money(payment) }
  })
}

function report(type: string, params: Params): ReportResult | undefined {
  if (!demoReports.some((item) => item.type === type)) return undefined
  let rows: Row[]
  if (type === 'sales-summary' || type === 'purchase-summary') rows = documents.filter((row) => row.kind === (type === 'sales-summary' ? 'shipments' : 'goods-receipts')).map(lineReport)
  else if (type === 'inventory-summary') rows = inventoryRows().map((row) => ({ ...row, brandName: '柒衡办公（示例）' }))
  else if (type === 'inventory-flow') rows = inventoryFlow(params)
  else if (type === 'receivable-aging' || type === 'payable-aging') rows = agingRows(type === 'receivable-aging' ? 'shipments' : 'goods-receipts', params)
  else rows = cashRows()
  rows = filterRows(rows, type.endsWith('-aging') ? { ...params, dateFrom: undefined, dateTo: undefined } : params)
  if (params.feesOnly) rows = []
  if (!params.detail) rows = grouped(rows, String(params.groupBy ?? 'standard'))
  const summaryFields: Record<string, string[]> = {
    'sales-summary': ['amount', 'qty', 'untaxedAmount', 'taxAmount', 'costAmount'],
    'purchase-summary': ['amount', 'qty', 'untaxedAmount', 'taxAmount', 'costAmount'],
    'receivable-aging': ['openAmount'], 'payable-aging': ['openAmount'],
    'cash-journal': ['receiptAmount', 'paymentAmount'],
    'inventory-summary': ['onHandQty', 'reservedQty', 'blockedQty', 'availableQty', 'costAmount'],
    'inventory-flow': params.detail ? ['inQty', 'outQty', 'costAmount'] : ['openingQty', 'inQty', 'outQty', 'closingQty', 'openingCost', 'inCost', 'outCost', 'closingCost'],
  }
  const fields = summaryFields[type] ?? []
  const summary: Row = Object.fromEntries(fields.map((field) => [field, money(sum(rows, field))]))
  if (type === 'cash-journal' && rows.length) {
    summary.openingBalance = rows[0]!.openingBalance
    summary.closingBalance = rows.at(-1)!.runningBalance
  }
  return { reportType: type, asOf: snapshotTime, dataSource: 'primary', statisticDate: snapshotDate, ...paginate(rows, params), rowCount: rows.length, summary }
}

function tradeSummary(): Row {
  const aggregate = (kind: string, prefix: string): string => money(documents.filter((row) => row.kind === kind && row.businessDate.startsWith(prefix)).reduce((value, row) => value + Number(row.totalAmount), 0))
  const amounts = (kind: string): Row => ({ today: aggregate(kind, snapshotDate), yesterday: aggregate(kind, '2026-11-19'), month: aggregate(kind, '2026-11'), year: aggregate(kind, '2026') })
  const trend = Array.from({ length: 12 }, (_, index) => {
    const month = new Date(Date.UTC(2025, 11 + index, 1)).toISOString().slice(0, 7)
    return { month, sales: aggregate('shipments', month), purchase: aggregate('goods-receipts', month) }
  })
  return { asOf: snapshotTime, dataSource: 'primary', sales: amounts('shipments'), purchase: amounts('goods-receipts'), receivable: money(sum(agingRows('shipments', {}), 'openAmount')), inventory: money(inventoryRows().reduce((value: number, row: InventoryBalance) => value + Number(row.costAmount), 0)), trend }
}

function workbench(): Row {
  const pending = documents.filter((row) => row.status === 'pending')
  const todos = pending.map((row) => ({ id: row.id, kind: row.kind, label: row.kind === 'sales-orders' ? '待审核销售订单' : '待审核采购订单', documentNo: row.documentNo, partnerName: row.partnerName, amount: row.totalAmount, businessDate: row.businessDate, url: '/' + row.kind + '/' + row.id }))
  return { asOf: snapshotTime, blocks: ['sales-orders', 'purchase-orders'].map((kind) => ({ key: kind, label: kind === 'sales-orders' ? '销售待审核' : '采购待审核', count: todos.filter((row) => row.kind === kind).length, url: '/' + kind + '?status=pending' })), todos, announcements: [], inventoryAlerts: [], periods: [{ id: '1', period: '2026-11', status: 'open', startDate: '2026-11-01', endDate: '2026-11-30' }], recentActions: [] }
}

function readEndpoint(path: string, params: Params): unknown {
  if (path === '/system/version') return { apiMajor: 2 }
  if (path === '/auth/me') return demoSession
  if (path === '/workbench/summary') return workbench()
  if (path === '/workbench/trade-summary') return tradeSummary()
  if (path === '/inventory-balances') return inventory(params)
  if (path.startsWith('/custom-fields/')) return []
  if (path === '/attachments') return []
  const lists: Record<string, Row[]> = { '/warehouses': warehouses, '/partners': partners, '/cash-accounts': accounts, '/org/business-users': people, '/skus': products.map((row) => ({ ...row, skuCode: row.code, skuName: row.name })) }
  if (lists[path]) return paginate(filterRows(lists[path], params), params)
  if (path.startsWith('/document-browser/')) return listDocuments(path.slice('/document-browser/'.length), params)
  if (path.startsWith('/reports/')) return report(path.slice('/reports/'.length), params)
  const parts = path.split('/').filter(Boolean)
  const row = documents.find((document) => document.kind === parts[0] && document.id === parts[1])
  if (!row) return undefined
  if (parts.length === 2) return row
  if (parts[2] === 'reservations') return { items: row.status === 'pending' ? [] : [{ id: row.id, remainingQty: row.lines?.[0]?.reservedQty, status: row.lines?.[0]?.reservationState, expireAt: '2026-11-30T23:59:59+08:00' }], nextCursor: null }
  if (parts[2] === 'settlement-summary') {
    const fulfilled = documents.find((document) => document.relatedOrderId === row.id && ['shipments', 'goods-receipts'].includes(document.kind))
    return { allocatedCashAmount: row.allocatedAmount, openAmount: money(Number(fulfilled?.totalAmount ?? 0) - Number(row.allocatedAmount)), scope: 'company' }
  }
  if (parts[2] === 'related') {
    const allocation = parts[3] === 'settlement-allocations'
    const kind = allocation ? row.kind === 'sales-orders' ? 'receipts' : 'payments' : parts[3]
    return paginate(documents.filter((document) => document.relatedOrderId === row.id && document.kind === kind).map((document) => ({ id: document.id, documentId: document.id, documentNo: document.documentNo, businessDate: document.businessDate, status: allocation ? 'active' : document.status, amount: document.totalAmount })), params)
  }
  return undefined
}

function reject(config: InternalAxiosRequestConfig, status: number, message: string): never {
  throw new AxiosError(message, 'ERR_BAD_REQUEST', config, undefined, { status, statusText: message, headers: new AxiosHeaders(), config, data: { message, errorCode: status === 403 ? 'DEMO_READ_ONLY' : 'DEMO_NOT_FOUND' } })
}

/** 所有演示请求在浏览器内完成；未知接口和写操作直接拒绝，禁止回退到公网 API。 */
export const demoAdapter: AxiosAdapter = async (config) => {
  if (config.signal?.aborted) throw new CanceledError('请求已取消')
  if ((config.method ?? 'get').toLowerCase() !== 'get') reject(config, 403, '这是只读演示，不能修改或导出演示数据。')
  const url = config.url ?? ''
  if (!url.startsWith('/') || url.startsWith('//') || url.includes('://')) reject(config, 403, '演示环境不访问外部业务接口。')
  const result = readEndpoint(url, (config.params ?? {}) as Params)
  if (result === undefined) reject(config, 404, '此记录或功能未纳入本次演示。')
  return { config, status: 200, statusText: '成功', headers: new AxiosHeaders(), data: { code: 200, message: '成功', data: structuredClone(result) } }
}
