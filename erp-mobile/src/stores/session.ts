import { defineStore } from 'pinia'
import { api, ApiFailure, onSessionExpired, replaceContext, requireServerVersion, validOrigin } from '../api/http'
import type { Device, Id, PairingCode, Session } from '../types/api'

const deviceStorage = 'qiheng.device.session'

export function nativeApp(): boolean {
  const platform = uni.getSystemInfoSync().uniPlatform as string | undefined
  return platform === 'app' || platform === 'app-plus'
}

export const useSession = defineStore('mobile-session', {
  state: () => ({
    profile: undefined as Session | undefined,
    company: undefined as Id | undefined,
    origin: '',
    device: undefined as Device | undefined,
    notice: '',
  }),
  actions: {
    async restore(): Promise<void> {
      onSessionExpired(() => this.endSession('登录已失效，请重新登录'))
      if (nativeApp()) {
        const saved: unknown = uni.getStorageSync(deviceStorage)
        if (typeof saved !== 'object' || saved === null) return
        const parsed = saved as { origin?: string; device?: Device }
        if (!parsed.origin || !parsed.device?.token || Date.parse(parsed.device.expiresAt) <= Date.now()) {
          this.endSession('')
          return
        }
        this.origin = validOrigin(parsed.origin)
        this.device = parsed.device
      }
      this.applyContext()
      try {
        await requireServerVersion()
        this.accept(await api<Session>('/auth/me'))
      } catch (error) {
        if (!(error instanceof ApiFailure) || error.status !== 401) throw error
        this.endSession('')
      }
    },
    async signIn(tenantCode: string, username: string, password: string): Promise<void> {
      await requireServerVersion()
      this.accept(await api<Session>('/auth/login', {
        method: 'POST', public: true, data: { tenantCode, username, password },
      }))
    },
    async verify(code: string): Promise<void> {
      this.accept(await api<Session>('/auth/mfa/verify', { method: 'POST', public: true, data: { code } }))
    },
    async activate(address: string, pairingCode: string): Promise<void> {
      const origin = validOrigin(address)
      this.endSession('')
      this.origin = origin
      this.applyContext()
      try {
        await requireServerVersion()
        this.device = await api<Device>('/auth/devices/activate', {
          method: 'POST', public: true, data: { pairingCode },
        })
        this.applyContext()
        this.accept(await api<Session>('/auth/me'))
        uni.setStorageSync(deviceStorage, { origin, device: this.device })
      } catch (error) {
        this.endSession('')
        throw error
      }
    },
    pairingCode(deviceName: string): Promise<PairingCode> {
      return api<PairingCode>('/auth/devices', { method: 'POST', data: { deviceName } })
    },
    async logout(): Promise<void> {
      try {
        if (this.device) await api('/auth/devices/current', { method: 'DELETE' })
        else await api('/auth/logout', { method: 'POST' })
      } catch (error) {
        if (!(error instanceof ApiFailure) || error.status !== 401) throw error
      }
      this.endSession('')
    },
    accept(profile: Session): void {
      this.profile = profile
      this.company = profile.companies[0]?.id
      this.notice = ''
      this.applyContext()
    },
    selectCompany(id: Id): void {
      if (!this.profile?.companies.some((entry) => entry.id === id) || id === this.company) return
      this.company = id
      this.applyContext()
    },
    applyContext(): void {
      replaceContext({ origin: this.origin, token: this.device?.token, company: this.company })
    },
    endSession(message: string): void {
      this.profile = undefined
      this.company = undefined
      this.device = undefined
      this.notice = message
      if (nativeApp()) uni.removeStorageSync(deviceStorage)
      this.origin = ''
      this.applyContext()
    },
  },
})
