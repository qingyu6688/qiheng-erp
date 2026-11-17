import { api } from './http'
import { browse, command, idPart, read } from './work'
import type { Id, Page } from '../types/api'
import type { MobileProductionIssue, MobileProductionOrder } from '../types/manufacturing'

const orders = '/mfg/work-orders'
const issues = '/mfg/issues'

export const productionOrders = (keyword?: string, cursor?: Id): Promise<Page<MobileProductionOrder>> =>
  browse(orders, { keyword }, cursor)
export const productionOrder = (id: Id): Promise<MobileProductionOrder> => read(orders + '/' + idPart(id))
export const productionIssues = (workOrderId: Id, cursor?: Id): Promise<Page<MobileProductionIssue>> =>
  browse(issues, { workOrderId, direction: 'issue' }, cursor)
export const productionIssue = (id: Id): Promise<MobileProductionIssue> => read(issues + '/' + idPart(id))
export const saveProductionIssue = (body: Record<string, unknown>, key: string, id?: Id): Promise<MobileProductionIssue> => id
  ? api(issues + '/' + idPart(id), { company: true, method: 'PUT', data: body, key })
  : command(issues, body, key)
export const updateProductionIssue = (id: Id, action: 'submit' | 'post', version: number, key: string): Promise<MobileProductionIssue> =>
  command(issues + '/' + idPart(id) + '/' + action, { version }, key)
