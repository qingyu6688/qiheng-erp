import { computed, shallowReactive, type Ref } from 'vue'

const guards = shallowReactive(new Map<() => boolean, Readonly<Ref<boolean>> | undefined>())
export const hasUnsavedWork = computed(() => [...guards.values()].some((dirty) => dirty?.value))

export function registerCompanyLeaveGuard(guard: () => boolean, dirty?: Readonly<Ref<boolean>>): () => void {
  guards.set(guard, dirty)
  return () => {
    guards.delete(guard)
  }
}
export function confirmCompanyLeave(): boolean {
  return [...guards.keys()].every((guard) => guard())
}
