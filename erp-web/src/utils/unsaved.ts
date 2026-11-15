const guards = new Set<() => boolean>()
export function registerCompanyLeaveGuard(guard: () => boolean): () => void {
  guards.add(guard)
  return () => {
    guards.delete(guard)
  }
}
export function confirmCompanyLeave(): boolean {
  return [...guards].every((guard) => guard())
}
