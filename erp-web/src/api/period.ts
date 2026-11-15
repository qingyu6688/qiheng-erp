import { request } from './http'
export interface PeriodCheckItem { check: string; passed: boolean; count: number; message: string; documents?: { documentType: string; documentId?: string; documentNo?: string; count?: string }[] }
export interface PeriodCheck { id: string; period: string; version: number; passed: boolean; items: PeriodCheckItem[] }
export function checkPeriod(id: string): Promise<PeriodCheck> { return request({ method: 'POST', url: '/accounting-periods/' + id + '/close-check' }, true) }
export function changePeriod(id: string, action: 'close' | 'reopen', body: { version: number; reason?: string }, key: string): Promise<Record<string, unknown>> {
  return request({ method: 'POST', url: '/accounting-periods/' + id + '/' + action, data: body, headers: { 'Idempotency-Key': key } }, true)
}
