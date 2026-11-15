interface ProviderSettings {
  enabled: boolean
  secretConfigured: boolean
  version?: number
}

interface SmsSettings extends ProviderSettings {
  accessKeyId: string
  signName: string
  templateCode: string
}

interface EmailSettings extends ProviderSettings {
  host: string
  port: number
  security: 'starttls' | 'tls'
  username: string
  from: string
}

interface WechatSettings extends ProviderSettings {
  appId: string
  callbackUrl: string
}

export interface LoginSettings {
  sms: SmsSettings
  email: EmailSettings
  wechat: WechatSettings
}

export type LoginSettingsInput = {
  [Method in keyof LoginSettings]?: Omit<LoginSettings[Method], 'secretConfigured'> & {
    secret?: string
  }
}
