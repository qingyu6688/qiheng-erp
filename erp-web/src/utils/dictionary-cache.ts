import type { DictionaryItem } from '../api/platform'

const PREFIX = 'erp.dict.'
interface CachedDictionary {
  version: string
  items: DictionaryItem[]
}
export function cachedDictionary(user: string, type: string): CachedDictionary | undefined {
  const key = PREFIX + user + '.' + type
  try {
    const raw = sessionStorage.getItem(key)
    if (!raw) return undefined
    const value: unknown = JSON.parse(raw)
    if (
      !value ||
      typeof value !== 'object' ||
      !('version' in value) ||
      typeof value.version !== 'string' ||
      !('items' in value) ||
      !Array.isArray(value.items)
    )
      throw new Error('字典缓存格式失效')
    const items: DictionaryItem[] = []
    for (const item of value.items as unknown[]) {
      if (
        !item ||
        typeof item !== 'object' ||
        !('id' in item) ||
        typeof item.id !== 'string' ||
        !('value' in item) ||
        typeof item.value !== 'string' ||
        !('label' in item) ||
        typeof item.label !== 'string' ||
        !('color' in item) ||
        typeof item.color !== 'string' ||
        !('enabled' in item) ||
        ![true, false, 0, 1].includes(item.enabled as boolean | number)
      )
        throw new Error('字典缓存项失效')
      items.push({
        id: item.id,
        value: item.value,
        label: item.label,
        color: item.color,
        enabled: Boolean(item.enabled),
      })
    }
    return { version: value.version, items }
  } catch {
    sessionStorage.removeItem(key)
    return undefined
  }
}
export function cacheDictionary(user: string, type: string, value: CachedDictionary): void {
  try {
    sessionStorage.setItem(PREFIX + user + '.' + type, JSON.stringify(value))
  } catch {
    clearDictionaryCache()
  }
}
export function clearDictionaryCache(): void {
  const keys = Array.from({ length: sessionStorage.length }, (_, index) =>
    sessionStorage.key(index),
  ).filter((key): key is string => Boolean(key?.startsWith(PREFIX)))
  keys.forEach((key) => sessionStorage.removeItem(key))
}
