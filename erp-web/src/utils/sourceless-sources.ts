import type { SourcelessKind } from '../hooks/useSourcelessMutation'

export interface SalesSourceReference {
  sourceType?: unknown
  sourceId?: unknown
  sourceNo?: unknown
  returnMode?: unknown
  returnSourceId?: unknown
  returnSourceNo?: unknown
  refundMode?: unknown
}
export interface SourcelessSourceTarget {
  kind: SourcelessKind
  id: string
  number?: string
}
function identifier(value: unknown): string | undefined {
  return typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value) ? value : undefined
}
function numberText(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined
}
export function sourcelessSourceTarget(
  row: SalesSourceReference,
  can: (permission: string) => boolean,
  original = false,
): SourcelessSourceTarget | undefined {
  if (!can('sales_return:view')) return undefined
  if (row.returnMode === 'sourceless') {
    const reversal = row.sourceType === 'document_reversal'
    if (original !== reversal) return undefined
    if (!reversal && !['sales_return', 'sales_return_line'].includes(String(row.sourceType)))
      return undefined
    const id = identifier(row.returnSourceId ?? (!reversal ? row.sourceId : undefined))
    if (id)
      return { kind: 'sales-returns', id, number: numberText(row.returnSourceNo ?? row.sourceNo) }
  }
  if (
    !original &&
    row.sourceType === 'refund_request' &&
    row.refundMode === 'sourceless_credit' &&
    can('refund_request:view')
  ) {
    const id = identifier(row.sourceId)
    if (id) return { kind: 'refund-requests', id, number: numberText(row.sourceNo) }
  }
  return undefined
}
