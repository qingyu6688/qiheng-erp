import { request } from './http'
import type { Id, PageResult } from '../types/api'
import type {
  WmsLineConfirmation,
  WmsTask,
  WmsTaskFilter,
  WmsTaskMode,
  WmsTaskSummary,
  WmsWarehouse,
} from '../types/wms'

const taskRoot = '/wms/tasks'

function taskUrl(id: Id): string {
  return taskRoot + '/' + encodeURIComponent(id)
}

export function wmsTasks(
  params: WmsTaskFilter,
  signal: AbortSignal,
): Promise<PageResult<WmsTaskSummary>> {
  return request({ url: taskRoot, params, signal }, true)
}

export function wmsTask(id: Id, signal: AbortSignal): Promise<WmsTask> {
  return request({ url: taskUrl(id), signal }, true)
}

export function wmsWarehouses(signal: AbortSignal): Promise<{ items: WmsWarehouse[] }> {
  return request({ url: '/wms/warehouses', signal }, true)
}

export function setWmsTaskMode(warehouseId: Id, taskMode: WmsTaskMode): Promise<unknown> {
  return request(
    {
      method: 'PUT',
      url: '/wms/warehouses/' + encodeURIComponent(warehouseId) + '/task-mode',
      data: { taskMode },
    },
    true,
  )
}

function taskCommand(
  path: string,
  data: { version: number } | WmsLineConfirmation,
  key: string,
): Promise<unknown> {
  return request({ method: 'POST', url: path, data, headers: { 'Idempotency-Key': key } }, true)
}

export function claimWmsTask(id: Id, version: number, key: string): Promise<unknown> {
  return taskCommand(taskUrl(id) + '/claim', { version }, key)
}

export function confirmWmsTaskLine(
  taskId: Id,
  lineId: Id,
  input: WmsLineConfirmation,
  key: string,
): Promise<unknown> {
  return taskCommand(
    taskUrl(taskId) + '/lines/' + encodeURIComponent(lineId) + '/confirm',
    input,
    key,
  )
}

export function finishWmsTask(id: Id, version: number, key: string): Promise<unknown> {
  return taskCommand(taskUrl(id) + '/finish', { version }, key)
}
