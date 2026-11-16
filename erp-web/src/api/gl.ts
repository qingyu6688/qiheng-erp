import { request } from './http'
import type {
  GlBook,
  GlConfigRow,
  GlLedger,
  GlQuery,
  GlReport,
  GlSubject,
  GlVoucher,
  GlVoucherInput,
} from '../types/gl'

export function glBook(): Promise<GlBook> {
  return request({ method: 'GET', url: '/gl/book' }, true)
}
export function glCommand<T>(
  path: string,
  data: unknown,
  key: string,
  method: 'POST' | 'PUT' = 'POST',
): Promise<T> {
  return request({ method, url: '/gl/' + path, data, headers: { 'Idempotency-Key': key } }, true)
}
export function glConfig(kind: string): Promise<GlConfigRow[]> {
  return request({ method: 'GET', url: '/gl/config/' + kind }, true)
}
export function glSubjects(): Promise<GlSubject[]> {
  return request({ method: 'GET', url: '/gl/config/subjects' }, true)
}
export function glVouchers(params: Record<string, unknown>): Promise<GlVoucher[]> {
  return request({ method: 'GET', url: '/gl/vouchers', params }, true)
}
export function glVoucher(id: string): Promise<GlVoucher> {
  return request({ method: 'GET', url: '/gl/vouchers/' + id }, true)
}
export function saveGlVoucher(
  id: string | undefined,
  data: GlVoucherInput,
  key: string,
): Promise<GlVoucher> {
  return glCommand('vouchers' + (id ? '/' + id : ''), data, key, id ? 'PUT' : 'POST')
}
export function glLedger(type: string, params: GlQuery): Promise<GlLedger> {
  return request({ method: 'GET', url: '/gl/ledgers/' + type, params }, true)
}
export function glReport(type: string, periodId: string): Promise<GlReport> {
  return request({ method: 'GET', url: '/gl/reports/' + type, params: { periodId } }, true)
}
export function glRead<T>(path: string, params?: Record<string, unknown>): Promise<T> {
  return request({ method: 'GET', url: '/gl/' + path, params }, true)
}
export function createGlExport(
  type: string,
  financeQuery: Record<string, unknown>,
  key: string,
): Promise<{ taskId: string }> {
  return request(
    {
      method: 'POST',
      url: '/export-jobs',
      data: { exportType: 'gl-' + type, financeQuery },
      headers: { 'Idempotency-Key': key },
    },
    true,
  )
}
