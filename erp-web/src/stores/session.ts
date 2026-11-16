import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as auth from '../api/auth'
import { ApiError, setCompanyScope, renewSessionScope } from '../api/http'
import type { CodeLoginInput, Company, LoginInput, UserSession } from '../types/auth'

import { clearDictionaryCache } from '../utils/dictionary-cache'

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
    renewSessionScope(false)
    clearDictionaryCache()
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
    const user = await auth.login(input)
    renewSessionScope()
    accept(user)
  }

  async function signInByCode(input: CodeLoginInput): Promise<void> {
    const user = await auth.loginByCode(input)
    renewSessionScope()
    accept(user)
  }

  async function refresh(): Promise<void> {
    accept(await auth.currentUser())
  }

  function can(code: string): boolean {
    return user.value?.permissions.includes(code) ?? false
  }

  async function signOut(): Promise<void> {
    renewSessionScope(false)
    try {
      await auth.logout()
      clear()
    } catch (cause: unknown) {
      renewSessionScope()
      throw cause
    }
  }

  return {
    user,
    companyId,
    company,
    ready,
    restore,
    refresh,
    signIn,
    signInByCode,
    signOut,
    selectCompany,
    clear,
    can,
  }
})
