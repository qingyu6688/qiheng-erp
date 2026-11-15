import { salesStatusLabel, salesSpecs, salesStatuses, statusLabels } from './sales'
import type { SalesKind } from './sales'
import { procurementSpecs } from './procurement'
import { stockSpecs, stockStatusLabels } from './stock-operations'

export function documentStateType(
  status: string,
): 'info' | 'warning' | 'primary' | 'success' | 'danger' {
  if (status === 'reversed') return 'danger'
  if (
    [
      'pending',
      'pending_review',
      'pending_payment',
      'pending_confirm',
      'change_pending',
      'received_with_diff',
      'signed_with_diff',
    ].includes(status)
  )
    return 'warning'
  if (['approved', 'submitted', 'shipped', 'counting'].includes(status)) return 'primary'
  if (
    ['signed', 'confirmed', 'paid', 'registered_paid', 'refunded', 'received', 'posted'].includes(
      status,
    )
  )
    return 'success'
  return 'info'
}

export function documentSpec(kind: string): {
  title: string
  singular: string
  permission: string
  states: string[]
} {
  const sales = salesSpecs.find((spec) => spec.path === kind)
  if (sales) return { ...sales, states: salesStatuses[sales.path] }
  const other = [...procurementSpecs, ...stockSpecs].find((spec) => spec.path === kind)
  if (!other) throw new Error('未找到单据类型')
  return other
}

export function documentStateLabel(kind: string, status: string): string {
  if (salesSpecs.some((spec) => spec.path === kind))
    return salesStatusLabel(kind as SalesKind, status)
  if (status === 'posted') return '已过账'
  return stockStatusLabels[status] ?? statusLabels[status] ?? status
}

export function documentStateTag(
  kind: string,
  status: string,
): ReturnType<typeof documentStateType> {
  return kind === 'shipments' && status === 'posted' ? 'primary' : documentStateType(status)
}

/** 数量只为交互提示做精确差值，签收确认与应收金额仍以服务端结果为准。 */
export function quantityDifference(shipped: string, signed: string): string {
  const scaled = (value: string): bigint | undefined => {
    const parts = /^(\d{1,24})(?:\.(\d{1,6}))?$/.exec(value)
    return parts
      ? BigInt(parts[1]!) * 1_000_000n + BigInt((parts[2] ?? '').padEnd(6, '0'))
      : undefined
  }
  const expected = scaled(shipped),
    actual = scaled(signed)
  if (expected === undefined || actual === undefined || actual > expected) return '—'
  const difference = expected - actual
  const fraction = String(difference % 1_000_000n)
    .padStart(6, '0')
    .replace(/0+$/, '')
  return String(difference / 1_000_000n) + (fraction ? '.' + fraction : '')
}
