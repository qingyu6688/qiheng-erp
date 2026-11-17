import { request } from './http'
import type { Id, PageResult } from '../types/api'
import type {
  BomPreview,
  ManufacturingArea,
  SalesWorkDraft,
  WorkOrder,
} from '../types/manufacturing'

const root = '/mfg/'

export function manufacturingList<T>(
  path: ManufacturingArea | `reports/${string}`,
  params: object = {},
): Promise<PageResult<T>> {
  return request({ url: root + path, params: { limit: 30, ...params } }, true)
}

export function manufacturingDetail<T>(path: ManufacturingArea, id: Id): Promise<T> {
  return request({ url: root + path + '/' + encodeURIComponent(id) }, true)
}

export function saveManufacturing<T>(
  path: ManufacturingArea,
  data: object,
  key: string,
  id?: Id,
): Promise<T> {
  return request(
    {
      url: root + path + (id ? '/' + encodeURIComponent(id) : ''),
      method: id ? 'PUT' : 'POST',
      data,
      headers: { 'Idempotency-Key': key },
    },
    true,
  )
}

export function manufacturingCommand<T>(
  path: ManufacturingArea,
  id: Id,
  action: string,
  data: object,
  key: string,
): Promise<T> {
  return request(
    {
      url: root + path + '/' + encodeURIComponent(id) + '/' + encodeURIComponent(action),
      method: 'POST',
      data,
      headers: { 'Idempotency-Key': key },
    },
    true,
  )
}

export function explodeBill(
  id: Id,
  qty: string,
  date?: string,
  warehouseId?: Id,
): Promise<{ lines: BomPreview[] }> {
  return request(
    {
      url: root + 'boms/' + encodeURIComponent(id) + '/explode',
      params: { qty, date, warehouseId },
    },
    true,
  )
}

export function orderFromSales(data: SalesWorkDraft, key: string): Promise<WorkOrder> {
  return request(
    {
      url: root + 'work-orders/from-sales-order',
      method: 'POST',
      data,
      headers: { 'Idempotency-Key': key },
    },
    true,
  )
}
