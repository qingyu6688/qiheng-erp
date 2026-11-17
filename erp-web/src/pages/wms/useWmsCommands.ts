import { ref } from 'vue'
import { isCancelled, messageOf, newIdempotencyKey } from '../../api/http'

export function useWmsCommands() {
  const busy = ref(false)
  const error = ref('')
  const notice = ref('')
  const keys = new Map<string, string>()

  async function run<T>(
    operation: string,
    payload: unknown,
    action: (key: string) => Promise<T>,
    message?: string,
  ): Promise<T | undefined> {
    if (busy.value) return undefined
    const fingerprint = operation + ':' + JSON.stringify(payload)
    const key = keys.get(fingerprint) ?? newIdempotencyKey()
    keys.set(fingerprint, key)
    busy.value = true
    error.value = ''
    notice.value = ''
    try {
      const result = await action(key)
      keys.delete(fingerprint)
      notice.value = message ?? ''
      return result
    } catch (cause: unknown) {
      if (!isCancelled(cause)) error.value = messageOf(cause)
      return undefined
    } finally {
      busy.value = false
    }
  }

  return { busy, error, notice, run }
}
