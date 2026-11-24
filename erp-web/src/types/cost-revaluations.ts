import type { PageResult } from './api'

export type CostRevaluationStatus = 'draft' | 'pending' | 'approved' | 'rejected' | 'posted' | 'voided'
export type CostRevaluationAction = 'refresh' | 'submit' | 'post' | 'void'
export interface CostRevaluationInput {
  warehouseId: string
  skuId: string
  startDate: string
  businessDate: string
  reason: string
}
export interface CostRevaluationCommand {
  version: number
  reason?: string
  workflowAssignees?: Record<string, string[]>
}
export interface CostRevaluationSummary {
  id: string
  documentNo: string
  status: CostRevaluationStatus
  warehouseId: string
  warehouseName: string
  skuId: string
  skuName: string
  startDate: string
  businessDate: string
  reason: string
  quantity: string
  beforeCost: string
  afterCost: string
  stockDelta: string
  eventCount: number
  version: number
}
export interface CostRevaluationLine {
  id: string
  movementId: string
  movementType: string
  businessDate: string
  postedAt: string
  onHandDelta: string
  sourceType: string
  sourceId: string
  beforeCost: string
  afterCost: string
  costDelta: string
}
export interface CostRevaluation extends CostRevaluationSummary {
  lines: CostRevaluationLine[]
  issuedDelta: string
  previewHash: string
  wfInstanceId: string
  allowedActions: CostRevaluationAction[]
}
export interface CostRevaluationMetadata {
  warehouses: { id: string; name: string }[]
  skus: { id: string; name: string }[]
  skuLimit: number
}
export type CostRevaluationPage = PageResult<CostRevaluationSummary>
export const revaluationStatusLabels: Record<CostRevaluationStatus, string> = {
  draft: '待提交', pending: '审批中', approved: '待执行', rejected: '已驳回', posted: '已执行', voided: '已作废',
}
export const revaluationActionLabels: Record<CostRevaluationAction, string> = {
  refresh: '重新生成预览', submit: '提交审批', post: '执行重估', void: '作废任务',
}
