export const dateShortcuts = {
  today: '今天',
  week: '本周',
  month: '本月',
  previousMonth: '上月',
  year: '本年',
} as const

export function businessDateRange(
  shortcut: keyof typeof dateShortcuts,
  day: string,
): [string, string] {
  const current = new Date(day + 'T00:00:00Z')
  const year = current.getUTCFullYear(),
    month = current.getUTCMonth()
  const format = (value: Date): string => value.toISOString().slice(0, 10)
  function range(start: Date, end: Date): [string, string] {
    return [format(start), format(end)]
  }
  switch (shortcut) {
    case 'today':
      return [day, day]
    case 'week': {
      const first = current.getUTCDate() - ((current.getUTCDay() + 6) % 7)
      return range(
        new Date(Date.UTC(year, month, first)),
        new Date(Date.UTC(year, month, first + 6)),
      )
    }
    case 'month':
      return range(new Date(Date.UTC(year, month, 1)), new Date(Date.UTC(year, month + 1, 0)))
    case 'previousMonth':
      return range(new Date(Date.UTC(year, month - 1, 1)), new Date(Date.UTC(year, month, 0)))
    case 'year':
      return [year + '-01-01', year + '-12-31']
  }
}
