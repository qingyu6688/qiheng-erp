const scale = 1_000_000n

export function scaledQuantity(value: string): bigint | undefined {
  const match = /^(\d{1,18})(?:\.(\d{1,6}))?$/.exec(value.trim())
  if (!match) return undefined
  return BigInt(match[1]!) * scale + BigInt((match[2] ?? '').padEnd(6, '0'))
}

export function formatQuantity(value: bigint): string {
  const whole = value / scale
  const fraction = (value % scale).toString().padStart(6, '0').replace(/0+$/, '')
  return fraction ? `${whole}.${fraction}` : String(whole)
}

export function remaining(plan?: string, completed?: string): string {
  const difference = (scaledQuantity(plan ?? '') ?? 0n) - (scaledQuantity(completed ?? '') ?? 0n)
  return formatQuantity(difference > 0n ? difference : 0n)
}

export function quantity(value: string, allowZero = false): string {
  const amount = scaledQuantity(value)
  if (amount === undefined || (!allowZero && amount === 0n)) {
    throw new Error(allowZero ? '数量不能小于零，最多保留六位小数' : '数量须大于零，最多保留六位小数')
  }
  return formatQuantity(amount)
}

export function stepQuantity(value: string, maximum: string, direction: -1 | 1): string {
  const limit = scaledQuantity(maximum) ?? 0n
  const current = scaledQuantity(value) ?? 0n
  const next = current + BigInt(direction) * scale
  if (next <= 0n || limit === 0n) return value
  return formatQuantity(next > limit ? limit : next)
}

export function serialLines(value: string): string[] {
  const result = value.split(/[\r\n]+/).map((line) => line.trim()).filter(Boolean)
  if (new Set(result).size !== result.length) throw new Error('序列号不能重复')
  return result
}
