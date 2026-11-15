import { request } from './http'
import type { ReportResult } from '../types/reporting'
export function report(type: string, params: Record<string, unknown>, signal?: AbortSignal): Promise<ReportResult> { return request({ method: 'GET', url: '/reports/' + type, params, signal }, true) }
export function createExport(type: string, query: Record<string, unknown>, key: string, signal?: AbortSignal): Promise<{ taskId: string }> { return request({ method: 'POST', url: '/export-jobs', data: { exportType: type.replaceAll('-', '_'), query }, headers: { 'Idempotency-Key': key }, signal }, true) }
