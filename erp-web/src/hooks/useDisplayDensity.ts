import { computed, inject, provide, ref, watch } from 'vue'
import type { InjectionKey, WritableComputedRef } from 'vue'

export type DisplayDensity = 'compact' | 'default' | 'comfortable'

const densityKey: InjectionKey<WritableComputedRef<DisplayDensity>> = Symbol('displayDensity')
const storagePrefix = 'erp.display-density.'

function readDensity(userId?: string): DisplayDensity {
  if (!userId) return 'default'
  try {
    const value = localStorage.getItem(storagePrefix + userId)
    return value === 'compact' || value === 'comfortable' ? value : 'default'
  } catch {
    return 'default'
  }
}

export function provideDisplayDensity(
  userId: () => string | undefined,
): WritableComputedRef<DisplayDensity> {
  const value = ref<DisplayDensity>('default')
  watch(userId, (id) => (value.value = readDensity(id)), { immediate: true, flush: 'sync' })

  const density = computed({
    get: (): DisplayDensity => value.value,
    set: (next: DisplayDensity): void => {
      const id = userId()
      if (!id) return
      try {
        localStorage.setItem(storagePrefix + id, next)
        value.value = next
      } catch {
        value.value = 'default'
      }
    },
  })
  provide(densityKey, density)
  return density
}

export function useDisplayDensity(): WritableComputedRef<DisplayDensity> | undefined {
  return inject(densityKey, undefined)
}
