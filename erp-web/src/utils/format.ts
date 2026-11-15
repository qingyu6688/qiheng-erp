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

const businessTimeZone = 'Asia/Shanghai'
function dateParts(value: string | null | undefined): Record<string, string> | undefined {
  if (!value || !/^\d{4}-\d{2}-\d{2}T/.test(value)) return undefined
  if (!/(?:Z|[+-]\d{2}:\d{2})$/.test(value)) {
    if (import.meta.env.DEV) console.warn('接口返回的时间缺少时区，已跳过显示。')
    return undefined
  }
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return undefined
  return Object.fromEntries(
    new Intl.DateTimeFormat('sv-SE', {
      timeZone: businessTimeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hourCycle: 'h23',
    })
      .formatToParts(date)
      .map((part) => [part.type, part.value]),
  )
}
function time(
  value: string | undefined | null,
  now: Date,
  seconds: boolean,
  full: boolean,
): string {
  const parts = dateParts(value),
    today = dateParts(now.toISOString())
  if (!parts || !today) return '—'
  return (
    (full || parts.year !== today.year ? parts.year + '-' : '') +
    parts.month +
    '-' +
    parts.day +
    ' ' +
    parts.hour +
    ':' +
    parts.minute +
    (seconds ? ':' + parts.second : '')
  )
}
export function formatDateTime(value?: string | null, now = new Date()): string {
  return time(value, now, false, false)
}
export function formatDateTimeSeconds(value?: string | null, now = new Date()): string {
  return time(value, now, true, false)
}
export function formatPrintTime(value?: string | null): string {
  return time(value, new Date(), false, true)
}
export function formatRelativeTime(value?: string | null, now = new Date()): string {
  const parts = dateParts(value),
    today = dateParts(now.toISOString()),
    yesterday = dateParts(new Date(now.getTime() - 86400000).toISOString())
  if (!parts || !today || !yesterday) return '—'
  const day = (data: Record<string, string>): string => data.year + data.month + data.day
  if (day(parts) === day(today)) return parts.hour + ':' + parts.minute
  if (day(parts) === day(yesterday)) return '昨天 ' + parts.hour + ':' + parts.minute
  return formatDateTime(value, now)
}
