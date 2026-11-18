import { request } from './http'
import type { Id, PageResult, Quantity } from '../types/api'
import type { WmsLineConfirmation, WmsTaskStatus } from '../types/wms'

export interface JobRow {
  id: Id
  no?: string
  status: WmsTaskStatus | string
  version: number
  warehouseId: Id
  assigneeId?: Id | null
  assigneeName?: string | null
  type?: string
  waveId?: Id | null
}
export interface PickLine {
  id: Id
  taskId?: Id
  shipmentId?: Id
  shipmentNo?: string
  skuId: Id
  skuCode: string
  skuName: string
  batchNo?: string | null
  fromLocationCode: string
  toLocationCode: string
  planQty: Quantity
  doneQty: Quantity
  serialNos?: string[]
}
export interface PickTask extends JobRow {
  shipmentId: Id
  shipmentNo: string
  lines?: PickLine[]
}
export interface PickWave extends JobRow {
  shipments?: Array<{ id: Id; shipmentId?: Id; shipmentNo?: string }>
  lines?: PickLine[]
}
export interface PackBox extends JobRow {
  shipmentId: Id
  boxNo: string
  labelCode?: string
  weightKg?: string
  volume?: string
}
export interface MoveLine {
  skuId: Id
  batchId?: Id
  fromLocationId: Id
  toLocationId: Id
  qty: Quantity
  serialNos?: string[]
  skuCode?: string
  skuName?: string
  batchNo?: string
  fromLocationCode?: string
  toLocationCode?: string
}
export interface MoveDocument extends JobRow {
  remark?: string
  lines: MoveLine[]
}
export interface LocationRule {
  id?: Id
  version?: number
  warehouseId: Id
  skuId?: Id
  categoryId?: Id
  zoneId?: Id
  fixedLocationId?: Id
  minQty?: Quantity
  maxQty?: Quantity
}
export interface CountLine {
  id: Id
  lineId?: Id
  skuId: Id
  skuCode?: string
  skuName?: string
  batchNo?: string | null
  countedQty?: Quantity
  systemQty?: Quantity
  bookQty?: Quantity
  serialManaged?: boolean
}
export interface CountJob extends JobRow {
  locationId: Id
  locationCode: string
  stockCountId?: Id
  countNo?: string
  snapshotAt?: string | null
  lines?: CountLine[]
}
export interface LabelCode {
  type: 'sku' | 'location' | 'batch' | 'box' | 'serial'
  id: Id
  code: string
  name: string
  skuId?: Id
  skuCode?: string
  unitId?: Id
  batchId?: Id
  batchNo?: string
}

const root = '/wms'
export type JobResource =
  'picks' | 'waves' | 'packs' | 'moves' | 'count-tasks' | 'tasks' | 'task-admin'
export type IdInput = {
  warehouseId?: Id
  status?: string
  type?: string
  shipmentId?: Id
  assignee?: 'me'
  cursor?: Id
}
export type Scan = Omit<WmsLineConfirmation, 'version'>

function resource(path: string, id: Id): string {
  return path + '/' + encodeURIComponent(id)
}

export function workList<T>(path: JobResource, params: IdInput): Promise<PageResult<T>> {
  return request({ url: root + '/' + path, params: { ...params, limit: 30 } }, true)
}

export function workDetail<T>(path: JobResource, id: Id): Promise<T> {
  return request({ url: resource(root + '/' + path, id) }, true)
}

export function workCommand<T = unknown>(path: string, data: unknown, key: string): Promise<T> {
  return request(
    { url: root + path, method: 'POST', data, headers: { 'Idempotency-Key': key } },
    true,
  )
}

export function saveMove(id: Id, data: Partial<MoveDocument>, key: string): Promise<MoveDocument> {
  return request(
    {
      url: resource(root + '/moves', id),
      method: 'PUT',
      data,
      headers: { 'Idempotency-Key': key },
    },
    true,
  )
}

export function removeMove(id: Id, version: number, key: string): Promise<unknown> {
  return request(
    {
      url: resource(root + '/moves', id),
      method: 'DELETE',
      data: { version },
      headers: { 'Idempotency-Key': key },
    },
    true,
  )
}

export function taskAssignees(
  id: Id,
  keyword?: string,
): Promise<{ items: Array<{ id: Id; displayName: string }> }> {
  return request(
    { url: resource(root + '/task-admin', id) + '/assignees', params: { keyword } },
    true,
  )
}

export function locationRules(
  warehouseId: Id,
): Promise<{ items: LocationRule[] } | LocationRule[]> {
  return request({ url: root + '/location-rules', params: { warehouseId } }, true)
}

export function saveLocationRule(data: LocationRule): Promise<LocationRule> {
  return request({ url: root + '/location-rules', method: 'PUT', data }, true)
}

export function workStatistics(
  warehouseId: Id,
  from?: string,
  to?: string,
): Promise<{ items: Array<Record<string, string | number | null>> }> {
  return request({ url: root + '/task-admin/statistics', params: { warehouseId, from, to } }, true)
}

export function scanCode(code: string, warehouseId?: Id): Promise<LabelCode> {
  return request({ url: root + '/scan/resolve', params: { warehouseId, code } }, true)
}

export function createLabels(
  warehouseId: Id | undefined,
  size: string,
  format: string,
  items: Array<{ id: Id; type: LabelCode['type']; copies: number }>,
): Promise<Blob> {
  return request(
    {
      url: '/labels/print-jobs',
      method: 'POST',
      data: { warehouseId, size, format, items },
      responseType: 'blob',
    },
    true,
  )
}
