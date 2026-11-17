import type { DictionaryItem } from '../../api/platform'

export function crmDictionaryLabel(items: DictionaryItem[], code?: string): string {
  return items.find((item) => item.value === code)?.label ?? (code || '—')
}

export function crmLocalTime(value?: string | null): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}

export function crmTimestamp(value: string): string | null {
  if (!value) return null
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) throw new Error('请输入有效的日期和时间')
  return date.toISOString()
}
