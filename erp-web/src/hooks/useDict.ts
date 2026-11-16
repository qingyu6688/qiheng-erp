import { computed, onMounted, onScopeDispose, ref } from 'vue'
import { dictionaries } from '../api/platform'
import type { DictionaryItem } from '../api/platform'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'
import { cachedDictionary, cacheDictionary } from '../utils/dictionary-cache'

export function useDict(type: string) {
  const session = useSessionStore(),
    items = ref<DictionaryItem[]>([]),
    error = ref(''),
    loading = ref(false)
  let active = true,
    version: string | undefined
  async function reload(force = false): Promise<void> {
    const user = session.user?.id
    if (!user) return
    if (!version && !force) {
      const cached = cachedDictionary(user, type)
      if (cached) {
        items.value = cached.items
        version = cached.version
      }
    }
    loading.value = true
    error.value = ''
    try {
      const result = await dictionaries(type, force ? undefined : version)
      if (!active || user !== session.user?.id) return
      if (!result.unchanged) items.value = result.items?.[type] ?? []
      version = result.version
      cacheDictionary(user, type, { version, items: items.value })
    } catch (cause: unknown) {
      if (active && !isCancelled(cause)) error.value = messageOf(cause)
    } finally {
      if (active) loading.value = false
    }
  }
  const changed = (): void => {
    void reload(true)
  }
  onMounted(() => {
    void reload()
    window.addEventListener('erp:dict-changed', changed)
  })
  onScopeDispose(() => {
    active = false
    window.removeEventListener('erp:dict-changed', changed)
  })
  return {
    items,
    options: computed(() => items.value.filter((item) => Boolean(item.enabled))),
    error,
    loading,
    reload,
    label: (value: string): string =>
      items.value.find((item) => item.value === value)?.label ?? value,
  }
}
