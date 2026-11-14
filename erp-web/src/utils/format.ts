import type { Money, Price, Quantity } from '../types/api'

function decimal(value: string | undefined | null, minFraction: number): string {
  const parts = value?.match(/^(-?)(\d+)(?:\.(\d+))?$/)
  if (!parts) return '—'
  const [, sign, integral, raw = ''] = parts
  const fraction = raw.replace(/0+$/, '').padEnd(minFraction, '0')
  const grouped = integral.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return (sign ? '−' : '') + grouped + (fraction ? '.' + fraction : '')
}

export function formatMoney(value?: Money | null): string {
  return decimal(value, 2)
}
export function formatPrice(value?: Price | null): string {
  return decimal(value, 2)
}
export function formatQuantity(value: Quantity | undefined | null, unit = ''): string {
  const number = decimal(value, 0)
  return unit && number !== '—' ? number + ' ' + unit : number
}
