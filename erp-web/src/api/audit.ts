import { request } from './http'
import type { PageResult } from '../types/api'
export interface AuditRow { id: string; action: string; entityType: string; entityId?: string; operatorName?: string; operatorId?: string; companyName?: string; createdAt: string; requestId?: string; reason?: string; before?: unknown; after?: unknown }
export interface OutboxRow { id: string; eventType: string; status: string; attempts: number; lastError: string }
export function auditLogs(params: Record<string, unknown>): Promise<PageResult<AuditRow>> { return request({ method: 'GET', url: '/audit-logs', params }) }
export function outboxEvents(params: Record<string, unknown>): Promise<PageResult<OutboxRow>> { return request({ method: 'GET', url: '/admin/outbox-events', params }, true) }
export function replayEvent(id: string, key: string): Promise<unknown> { return request({ method: 'POST', url: '/admin/outbox-events/' + id + '/replay', headers: { 'Idempotency-Key': key } }, true) }
