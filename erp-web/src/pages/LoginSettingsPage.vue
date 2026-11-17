<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { getLoginSettings, saveLoginSettings } from '../api/login-settings'
import { isCancelled, messageOf } from '../api/http'
import type { LoginSettings, LoginSettingsInput } from '../types/login-settings'

const DEFAULT_SMTP_PORT = 587

const settings = ref<LoginSettings>({
  sms: { enabled: false, secretConfigured: false, accessKeyId: '', signName: '', templateCode: '' },
  email: {
    enabled: false,
    secretConfigured: false,
    host: '',
    port: DEFAULT_SMTP_PORT,
    security: 'starttls',
    username: '',
    from: '',
  },
  wechat: { enabled: false, secretConfigured: false, appId: '', callbackUrl: '' },
})
const secrets = reactive({ sms: '', email: '', wechat: '' })
const loaded = ref(false)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const controller = new AbortController()
let saved: LoginSettings | undefined

function accept(result: LoginSettings): void {
  saved = result
  settings.value = {
    sms: { ...result.sms },
    email: { ...result.email },
    wechat: { ...result.wechat },
  }
  loaded.value = true
}

function providerChanged<T extends { secretConfigured: boolean }>(
  original: T,
  input: Omit<T, 'secretConfigured'> & { secret?: string },
): boolean {
  return Object.entries(input).some(([key, value]) =>
    key === 'secret' ? value !== undefined : value !== original[key as keyof T],
  )
}

async function load(): Promise<void> {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    accept(await getLoginSettings(controller.signal))
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    loading.value = false
  }
}

async function submit(): Promise<void> {
  if (saving.value || !saved) return
  const { sms, email, wechat } = settings.value
  const smsInput = {
    enabled: sms.enabled,
    accessKeyId: sms.accessKeyId.trim(),
    signName: sms.signName.trim(),
    templateCode: sms.templateCode.trim(),
    version: sms.version,
    secret: secrets.sms.trim() ? secrets.sms : undefined,
  }
  const emailInput = {
    enabled: email.enabled,
    host: email.host.trim(),
    port: email.port || DEFAULT_SMTP_PORT,
    security: email.security,
    username: email.username.trim(),
    from: email.from.trim(),
    version: email.version,
    secret: secrets.email.trim() ? secrets.email : undefined,
  }
  const wechatInput = {
    enabled: wechat.enabled,
    appId: wechat.appId.trim(),
    callbackUrl: wechat.callbackUrl.trim(),
    version: wechat.version,
    secret: secrets.wechat.trim() ? secrets.wechat : undefined,
  }
  const changes: LoginSettingsInput = {}
  if (providerChanged(saved.sms, smsInput)) changes.sms = smsInput
  if (providerChanged(saved.email, emailInput)) changes.email = emailInput
  if (providerChanged(saved.wechat, wechatInput)) changes.wechat = wechatInput
  error.value = notice.value = ''
  if (!Object.keys(changes).length) {
    notice.value = '没有需要保存的更改。'
    return
  }
  saving.value = true
  try {
    accept(await saveLoginSettings(changes))
    secrets.sms = secrets.email = secrets.wechat = ''
    notice.value = '登录方式设置已保存。'
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    saving.value = false
  }
}

function secretPlaceholder(configured: boolean): string {
  return configured ? '已配置，留空保留原值' : '请输入凭据'
}

onMounted(load)
onBeforeUnmount(() => controller.abort())
</script>

<template>
  <div class="page-heading">
    <div>
      <h1>登录方式设置</h1>
      <p>设置本企业员工可使用的登录方式。员工可在账户菜单绑定已启用的方式。</p>
    </div>
    <router-link to="/login-settings/sso" class="text-link">企业单点登录</router-link>
  </div>
  <el-alert
    v-if="error"
    class="form-alert"
    type="error"
    :title="error"
    :closable="false"
    show-icon
  />
  <el-alert
    v-if="notice"
    class="form-alert"
    type="success"
    :title="notice"
    :closable="false"
    show-icon
  />
  <p v-if="loading" role="status">正在读取登录设置…</p>
  <el-form
    v-else-if="loaded"
    class="provider-settings"
    label-position="top"
    @submit.prevent="submit"
  >
    <section class="panel provider-panel" aria-labelledby="sms-settings-title">
      <header>
        <div>
          <h2 id="sms-settings-title">手机短信</h2>
          <p>使用阿里云短信服务，填写已审核的签名和验证码模板。</p>
        </div>
        <el-switch
          v-model="settings.sms.enabled"
          aria-label="启用手机短信登录"
          :disabled="saving"
        />
      </header>
      <div class="provider-fields">
        <el-form-item label="访问密钥 ID" for="sms-key">
          <el-input
            id="sms-key"
            v-model="settings.sms.accessKeyId"
            autocomplete="off"
            :required="settings.sms.enabled"
            :disabled="saving"
          />
        </el-form-item>
        <el-form-item label="访问密钥 Secret" for="sms-secret">
          <el-input
            id="sms-secret"
            v-model="secrets.sms"
            type="password"
            autocomplete="new-password"
            :placeholder="secretPlaceholder(settings.sms.secretConfigured)"
            :required="settings.sms.enabled && !settings.sms.secretConfigured"
            :disabled="saving"
          />
        </el-form-item>
        <el-form-item label="短信签名" for="sms-sign">
          <el-input
            id="sms-sign"
            v-model="settings.sms.signName"
            :required="settings.sms.enabled"
            :disabled="saving"
          />
        </el-form-item>
        <el-form-item label="模板编号" for="sms-template">
          <el-input
            id="sms-template"
            v-model="settings.sms.templateCode"
            placeholder="SMS_"
            :required="settings.sms.enabled"
            :disabled="saving"
          />
        </el-form-item>
      </div>
    </section>
    <section class="panel provider-panel" aria-labelledby="email-settings-title">
      <header>
        <div>
          <h2 id="email-settings-title">邮箱验证码</h2>
          <p>邮件服务器须在部署环境的 SMTP 允许清单中。</p>
        </div>
        <el-switch v-model="settings.email.enabled" aria-label="启用邮箱登录" :disabled="saving" />
      </header>
      <div class="provider-fields">
        <el-form-item label="SMTP 服务器" for="email-host">
          <el-input
            id="email-host"
            v-model="settings.email.host"
            placeholder="smtp.example.com"
            :required="settings.email.enabled"
            :disabled="saving"
          />
        </el-form-item>
        <el-form-item label="端口" for="email-port">
          <el-input-number
            id="email-port"
            v-model="settings.email.port"
            :min="1"
            :max="65535"
            :precision="0"
            :disabled="saving"
          />
        </el-form-item>
        <el-form-item label="加密方式" for="email-security">
          <el-select id="email-security" v-model="settings.email.security" :disabled="saving">
            <el-option label="STARTTLS" value="starttls" />
            <el-option label="TLS" value="tls" />
          </el-select>
        </el-form-item>
        <el-form-item label="发件邮箱" for="email-from">
          <el-input
            id="email-from"
            v-model="settings.email.from"
            :type="settings.email.enabled ? 'email' : 'text'"
            :required="settings.email.enabled"
            :disabled="saving"
          />
        </el-form-item>
        <el-form-item label="SMTP 用户名" for="email-username">
          <el-input
            id="email-username"
            v-model="settings.email.username"
            autocomplete="off"
            :required="settings.email.enabled"
            :disabled="saving"
          />
        </el-form-item>
        <el-form-item label="SMTP 密码或授权码" for="email-secret">
          <el-input
            id="email-secret"
            v-model="secrets.email"
            type="password"
            autocomplete="new-password"
            :placeholder="secretPlaceholder(settings.email.secretConfigured)"
            :required="settings.email.enabled && !settings.email.secretConfigured"
            :disabled="saving"
          />
        </el-form-item>
      </div>
    </section>
    <section class="panel provider-panel" aria-labelledby="wechat-settings-title">
      <header>
        <div>
          <h2 id="wechat-settings-title">微信扫码</h2>
          <p>填写微信开放平台网站应用的凭据与授权回调地址。</p>
        </div>
        <el-switch v-model="settings.wechat.enabled" aria-label="启用微信登录" :disabled="saving" />
      </header>
      <div class="provider-fields">
        <el-form-item label="微信 AppID" for="wechat-app-id">
          <el-input
            id="wechat-app-id"
            v-model="settings.wechat.appId"
            autocomplete="off"
            :required="settings.wechat.enabled"
            :disabled="saving"
          />
        </el-form-item>
        <el-form-item label="微信 AppSecret" for="wechat-secret">
          <el-input
            id="wechat-secret"
            v-model="secrets.wechat"
            type="password"
            autocomplete="new-password"
            :placeholder="secretPlaceholder(settings.wechat.secretConfigured)"
            :required="settings.wechat.enabled && !settings.wechat.secretConfigured"
            :disabled="saving"
          />
        </el-form-item>
        <el-form-item class="provider-wide" label="授权回调地址" for="wechat-callback">
          <el-input
            id="wechat-callback"
            v-model="settings.wechat.callbackUrl"
            :type="settings.wechat.enabled ? 'url' : 'text'"
            placeholder="https://erp.example.com/api/v1/auth/wechat/callback"
            :required="settings.wechat.enabled"
            :disabled="saving"
          />
        </el-form-item>
      </div>
    </section>
    <div class="provider-actions">
      <el-button native-type="submit" type="primary" :loading="saving">保存设置</el-button>
    </div>
  </el-form>
  <el-button v-else @click="load">重新加载</el-button>
</template>

<style scoped>
.provider-settings {
  display: grid;
  gap: 16px;
  max-width: 960px;
}

.provider-panel {
  padding: 20px 24px 4px;
}

.provider-panel header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.provider-panel h2 {
  font-size: 17px;
  margin: 0;
}

.provider-panel p {
  margin: 5px 0 0;
  color: var(--el-text-color-secondary);
}

.provider-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 20px;
}

.provider-wide {
  grid-column: 1 / -1;
}

.provider-actions {
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 680px) {
  .provider-panel {
    padding-inline: 16px;
  }

  .provider-fields {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
