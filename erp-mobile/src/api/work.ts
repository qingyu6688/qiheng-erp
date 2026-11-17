import type { Id, Page, Query, ScanMatch } from '../types/api'
import type {
  ApprovalInstance, ApprovalRow, InventoryRow, LabelItem, PackInput, PackRow, Receipt, StockWork,
  Warehouse, WaveRow, WorkRow,
} from '../types/work'
import { api, binaryApi } from './http'

export const idPart = (id: Id): string => {
  if (!/^[1-9]\d{0,18}$/.test(id)) throw new Error('ERP 单据编号无效')
  return id
}

export function read<T>(path: string, params?: Query): Promise<T> {
  return api<T>(path, { company: true, params })
}

export function browse<T>(path: string, params?: Query, cursor?: Id): Promise<Page<T>> {
  return read(path, { ...params, limit: 30, cursor })
}

export function command<T>(path: string, body: Record<string, unknown>, key: string): Promise<T> {
  return api<T>(path, { company: true, method: 'POST', data: body, key })
}

export function save<T>(path: string, body: Record<string, unknown>): Promise<T> {
  return api<T>(path, { company: true, method: 'PUT', data: body })
}

export function resolveCode(code: string, warehouseId?: Id): Promise<ScanMatch> {
  if (!code.trim()) return Promise.reject(new Error('请扫描或输入编码'))
  return read('/wms/scan/resolve', { code: code.trim(), warehouseId })
}

export const warehouseChoices = (): Promise<{ items: Warehouse[] }> => read('/wms/warehouses')
export const goodsReceipt = (id: Id): Promise<Receipt> => read('/goods-receipts/' + idPart(id))
export const waveInfo = (id: Id): Promise<WaveRow> => read('/wms/waves/' + idPart(id))
export const stockWork = (path: string, id: Id): Promise<StockWork> => read(path + '/' + idPart(id))
export const approvals = (cursor?: Id): Promise<Page<ApprovalRow>> => browse('/workflow/tasks/todo', {}, cursor)
export const approvalInstance = (id: Id): Promise<ApprovalInstance> => read('/workflow/instances/' + idPart(id))
export const warehouseStock = (params: Query, cursor?: Id): Promise<Page<InventoryRow>> =>
  browse('/inventory-balances', params, cursor)
export const locationStock = (warehouse: Id, location: Id, cursor?: Id): Promise<Page<InventoryRow>> =>
  browse('/warehouses/' + idPart(warehouse) + '/locations/balances', { locationId: location }, cursor)
export const moves = (type: 'move' | 'replenish', cursor?: Id): Promise<Page<WorkRow>> =>
  browse('/wms/moves', { type }, cursor)

export function pack(input: PackInput, key: string): Promise<Page<PackRow>> {
  return command('/wms/packs', { shipmentId: input.shipmentId, boxes: input.boxes }, key)
}

export type LabelSize = '50x30' | '70x40' | '100x100'

export function labels(items: LabelItem[], size: LabelSize, warehouseId?: Id): Promise<ArrayBuffer> {
  return binaryApi('/labels/print-jobs', {
    method: 'POST',
    company: true,
    data: { items, size, format: 'pdf', warehouseId },
  })
}
