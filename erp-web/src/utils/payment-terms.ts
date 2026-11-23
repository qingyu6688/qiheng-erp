import type { PaymentTerm } from '../types/payment-terms'

export function readPaymentTerm(value: unknown): PaymentTerm {
  if (!value || typeof value !== 'object') return { type: null, value: null }
  const term = value as Record<string, unknown>
  return {
    type: term.type === 'days' || term.type === 'monthly' ? term.type : null,
    value: typeof term.value === 'number' ? term.value : null,
  }
}

export function paymentTermLabel(type: unknown, value: unknown): string {
  if (type === 'days' && typeof value === 'number') return value === 0 ? '现结' : `业务日期后 ${value} 天`
  if (type === 'monthly' && typeof value === 'number') return `月结次月 ${value} 日`
  return '未设账期'
}
