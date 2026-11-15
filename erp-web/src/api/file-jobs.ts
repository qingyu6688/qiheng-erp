import { request } from './http'
import type { PageResult } from '../types/api'
import type { FileJob, ImportError } from '../types/file-jobs'
export function fileJobs(kind: 'import' | 'export', params: Record<string, unknown>, signal?: AbortSignal): Promise<PageResult<FileJob>> { return request({ method: 'GET', url: '/' + kind + '-jobs', params, signal }, true) }
export function fileJob(kind: 'import' | 'export', id: string, signal?: AbortSignal): Promise<FileJob> { return request({ method: 'GET', url: '/' + kind + '-jobs/' + id, signal }, true) }
export function importErrors(id: string, cursor?: string, signal?: AbortSignal): Promise<PageResult<ImportError>> { return request({ method: 'GET', url: '/import-jobs/' + id + '/errors', params: { cursor, limit: 200 }, signal }, true) }
export function createImport(data: FormData, key: string, signal?: AbortSignal): Promise<FileJob> { return request({ method: 'POST', url: '/import-jobs', data, headers: { 'Idempotency-Key': key }, timeout: 60000, signal }, true) }
export function exportDownloadTicket(id: string, signal?: AbortSignal): Promise<{ url: string; expiresAt: string }> { return request({ method: 'POST', url: '/export-jobs/' + id + '/download-ticket', signal }, true) }
export function retryImport(id: string, key: string, signal?: AbortSignal): Promise<FileJob> { return request({ method: 'POST', url: '/import-jobs/' + id + '/retry', headers: { 'Idempotency-Key': key }, signal }, true) }
