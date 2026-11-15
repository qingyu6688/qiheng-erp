import { request } from './http'
import type { Attachment, PrintData } from '../types/documents'
export function attachments(ownerType: string, ownerId: string, signal?: AbortSignal): Promise<Attachment[]> { return request({ method: 'GET', url: '/attachments', params: { ownerType, ownerId }, signal }, true) }
export function uploadAttachment(data: FormData, key: string, signal?: AbortSignal): Promise<Attachment> { return request({ method: 'POST', url: '/attachments', data, headers: { 'Idempotency-Key': key }, timeout: 60000, signal }, true) }
export function deleteAttachment(id: string, key: string, signal?: AbortSignal): Promise<unknown> { return request({ method: 'DELETE', url: '/attachments/' + id, headers: { 'Idempotency-Key': key }, signal }, true) }
export function printData(type: string, id: string, signal?: AbortSignal): Promise<PrintData> { return request({ method: 'GET', url: '/print-data/' + type + '/' + id, signal }, true) }
