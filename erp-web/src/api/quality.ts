import { request } from './http'
import type { PageResult } from '../types/api'

export interface QualityItem {
  id: string
  code: string
  name: string
  type: 'quantitative' | 'qualitative'
  unit?: string
  method?: string
  status: 'active' | 'disabled'
  version: number
}

export interface QualityCheck {
  itemId: string
  standardValue: string
  lowerLimit?: string | null
  upperLimit?: string | null
  required: boolean
}

export interface QualityCriterion extends QualityCheck {
  code: string
  name: string
  type: QualityItem['type']
  unit?: string
  method?: string
}

export interface QualityTemplate {
  id: string
  code: string
  name: string
  sampling: 'full' | 'fixed_qty' | 'percent'
  samplingValue: string
  status: 'active' | 'disabled'
  checks: QualityCriterion[]
  version: number
}

export interface QualityRule {
  id: string
  targetType: 'sku' | 'category'
  targetId: string
  supplierId: string
  templateId?: string | null
  inspectionRequired: boolean | number
  status: 'active' | 'disabled'
  version: number
}

export interface QualityInspection {
  id: string
  inspectionNo: string
  sourceId: string
  skuId: string
  supplierId: string
  warehouseId: string
  skuName?: string
  supplierName?: string
  warehouseName?: string
  lotKey: string
  locationKey: string
  roundQty: string
  sampleQty: string
  qualifiedQty: string
  unqualifiedQty: string
  concessionQty: string
  status: 'draft' | 'submitted' | 'approved' | 'cancelled'
  result: 'pending' | 'passed' | 'failed' | 'partial'
  version: number
  businessDate: string
}

export interface QualityValue {
  itemId: string
  measuredValue: string
  judgement?: 'ok' | 'ng'
}

export interface QualityDefect {
  defectCode: string
  qty: string
  remark?: string
}

export interface QualityInspectionDetail extends QualityInspection {
  sourceLineId: string
  snapshot: Pick<QualityTemplate, 'code' | 'name' | 'sampling' | 'samplingValue' | 'checks'>
  rounds: Array<{
    roundNo: number
    qualifiedQty: string
    unqualifiedQty: string
    concessionQty: string
    concessionReason?: string
    approverId: string
    approvedAt: string
  }>
  values: Array<QualityValue & { roundNo: number }>
  defects: Array<QualityDefect & { id: string; roundNo: number }>
  dispositions: Array<{
    id: string
    action: 'return' | 'scrap' | 'reinspect'
    qty: string
    reason: string
    targetId?: string
    targetType?: string
    targetStatus?: string
  }>
  receivedQty: string
  remainingQty: string
  concessionReason?: string
  inspectorId?: string
  roundNo: number
}

export type QualitySettingPath = 'items' | 'templates' | 'sku-rules'

type QualitySetting = {
  items: QualityItem
  templates: QualityTemplate
  'sku-rules': QualityRule
}

export function qualitySettings<K extends QualitySettingPath>(path: K, cursor?: string): Promise<QualitySetting[K][]> {
  return request({ method: 'GET', url: '/qc/' + path, params: { limit: 200, cursor } }, true)
}

export function saveQualitySetting(path: QualitySettingPath, data: object, id?: string): Promise<void> {
  return request({ method: id ? 'PUT' : 'POST', url: '/qc/' + path + (id ? '/' + id : ''), data }, true)
}

export function qualityInspections(nonconforming: boolean, status?: string, cursor?: string): Promise<PageResult<QualityInspection>> {
  return request({
    method: 'GET',
    url: nonconforming ? '/qc/nonconforming' : '/qc/inspections',
    params: { status, cursor, limit: 50 },
  }, true)
}

export function qualityInspection(id: string): Promise<QualityInspectionDetail> {
  return request({ method: 'GET', url: '/qc/inspections/' + id }, true)
}

export function changeQualityInspection(
  id: string,
  action: 'submit' | 'approve' | 'reject' | 'dispose',
  data: object,
  key: string,
): Promise<QualityInspectionDetail> {
  const path = action === 'dispose' ? 'nonconforming' : 'inspections'
  return request({ method: 'POST', url: '/qc/' + path + '/' + id + '/' + action, data, headers: { 'Idempotency-Key': key } }, true)
}
