import { ref } from 'vue'
import { errorText } from '../api/http'

export function keyFor(snapshot: string, keys: Map<string, string>, makeKey: () => string): string {
  const known = keys.get(snapshot)
  if (known) return known
  const created = makeKey()
  keys.set(snapshot, created)
  return created
}

function newKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `m-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`
}

export function useTaskFlow() {
  const keys = new Map<string, string>()
  const busy = ref(false)
  const failure = ref('')
  const success = ref('')
  async function run<T>(
    snapshot: unknown,
    action: (key: string) => Promise<T>,
    description = '操作已完成',
  ): Promise<T | undefined> {
    if (busy.value) return undefined
    const key = keyFor(JSON.stringify(snapshot), keys, newKey)
    busy.value = true
    failure.value = ''
    success.value = ''
    try {
      const value = await action(key)
      success.value = description
      return value
    } catch (error) {
      failure.value = errorText(error)
      return undefined
    } finally {
      busy.value = false
    }
  }
  return { busy, failure, success, run }
}
