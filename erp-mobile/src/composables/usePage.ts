import { ref, shallowRef } from 'vue'
import type { Id, Page } from '../types/api'
import { errorText } from '../api/http'

export function usePage<T>(request: (cursor?: Id) => Promise<Page<T>>) {
  const items = shallowRef<T[]>([])
  const cursor = ref<Id>()
  const busy = ref(false)
  const failure = ref('')
  let generation = 0

  async function load(more = false): Promise<void> {
    if (more && (!cursor.value || busy.value)) return
    const requestVersion = ++generation
    const previous = more ? items.value : []
    busy.value = true
    failure.value = ''
    if (!more) {
      items.value = []
      cursor.value = undefined
    }
    try {
      const reply = await request(more ? cursor.value : undefined)
      if (requestVersion !== generation) return
      items.value = [...previous, ...reply.items]
      cursor.value = reply.nextCursor ?? undefined
    } catch (error) {
      if (requestVersion === generation) failure.value = errorText(error)
    } finally {
      if (requestVersion === generation) busy.value = false
    }
  }

  return { items, cursor, busy, failure, load }
}
