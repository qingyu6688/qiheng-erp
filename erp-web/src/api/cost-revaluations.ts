import { request } from './http'
import type {
  CostRevaluation, CostRevaluationAction, CostRevaluationCommand, CostRevaluationInput,
  CostRevaluationMetadata, CostRevaluationPage,
} from '../types/cost-revaluations'

const resource = '/cost-revaluations'
export function costRevaluations(params: { status?: string; cursor?: string; limit?: number }, signal?: AbortSignal): Promise<CostRevaluationPage> {
  return request({ method: 'GET', url: resource, params, signal }, true)
}
export function costRevaluation(id: string, signal?: AbortSignal): Promise<CostRevaluation> {
  return request({ method: 'GET', url: `${resource}/${id}`, signal }, true)
}
export function costRevaluationMetadata(search?: string, signal?: AbortSignal): Promise<CostRevaluationMetadata> {
  return request({ method: 'GET', url: `${resource}/metadata`, params: { search }, signal }, true)
}
export function createCostRevaluation(input: CostRevaluationInput, key: string, signal?: AbortSignal): Promise<CostRevaluation> {
  return request({ method: 'POST', url: resource, data: input, headers: { 'Idempotency-Key': key }, signal }, true)
}
export function commandCostRevaluation(id: string, action: CostRevaluationAction, input: CostRevaluationCommand, key: string, signal?: AbortSignal): Promise<CostRevaluation> {
  return request({ method: 'POST', url: `${resource}/${id}/${action}`, data: input, headers: { 'Idempotency-Key': key }, signal }, true)
}
export function costRevaluationResult(operation: string, key: string): Promise<{ status: string; response: CostRevaluation | null }> {
  return request({ method: 'GET', url: '/command-results', params: { operation, key } }, true)
}
