import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as auth from '../api/auth'
import { ApiError, setCompanyScope } from '../api/http'
import type { Company, LoginInput, UserSession } from '../types/auth'

const COMPANY_KEY = 'erp.company'

export const useSessionStore = defineStore('session', () => {
  const user = ref<UserSession | null>(null)
  const ready = ref(false)
  const companyId = ref<string | null>(null)
  const company = computed<Company | undefined>(() =>
    user.value?.companies.find((c) => c.id === companyId.value),
  )
  let restoring: Promise<void> | null = null

  function selectCompany(id: string | null): void {
    const permitted = user.value?.companies.some((c) => c.id === id) ?? false
    companyId.value = permitted ? id : null
    setCompanyScope(companyId.value)
    if (companyId.value) sessionStorage.setItem(COMPANY_KEY, companyId.value)
    else sessionStorage.removeItem(COMPANY_KEY)
  }

  function accept(value: UserSession): void {
    user.value = value
    const previous = sessionStorage.getItem(COMPANY_KEY)
    selectCompany(
      value.companies.find((c) => c.id === previous)?.id ?? value.companies[0]?.id ?? null,
    )
    ready.value = true
  }

  function clear(): void {
    user.value = null
    selectCompany(null)
    ready.value = true
  }

  async function restore(): Promise<void> {
    if (ready.value) return
    if (!restoring) {
      restoring = auth
        .currentUser()
        .then(accept)
        .catch((error: unknown) => {
          if (error instanceof ApiError && error.status === 401) clear()
          else throw error
        })
        .finally(() => {
          restoring = null
        })
    }
    await restoring
  }

  async function signIn(input: LoginInput): Promise<void> {
    accept(await auth.login(input))
  }

  async function refresh(): Promise<void> {
    accept(await auth.currentUser())
  }

  function can(code: string): boolean {
    return user.value?.permissions.includes(code) ?? false
  }

  async function signOut(): Promise<void> {
    await auth.logout()
    clear()
  }

  return {
    user,
    companyId,
    company,
    ready,
    restore,
    refresh,
    signIn,
    signOut,
    selectCompany,
    clear,
    can,
  }
})
