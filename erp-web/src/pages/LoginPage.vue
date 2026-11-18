<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Lock, OfficeBuilding, User } from '@element-plus/icons-vue'
import { version as appVersion } from '../../package.json'
import { availableLoginMethods, sendLoginCode, startWechatLogin } from '../api/auth'
import { useSessionStore } from '../stores/session'
import { ApiError, messageOf } from '../api/http'
import BrandMark from '../components/BrandMark.vue'
import MfaEnrollment from '../components/MfaEnrollment.vue'
import {
  activateLoginMfa,
  ldapLogin,
  loginMfaSetup,
  loginMfaStatus,
  ssoStart,
  verifyLoginMfa,
} from '../api/platform-security'
import type { MfaSetup } from '../api/platform-security'
import type { AlternateLoginMethod, CodeRequest, LoginAvailability, SsoType } from '../types/auth'
import {
  alternateMethods,
  contactLabel,
  goToWechat,
  loginDestination,
  methodLabels,
} from '../utils/login-methods'

const METHOD_LOOKUP_DELAY = 180
const RESEND_SECONDS = 60

const router = useRouter()
const route = useRoute()
const session = useSessionStore()
const input = reactive({
  tenantCode: import.meta.env.DEV ? import.meta.env.VITE_DEV_TENANT || 'DEMO' : '',
  username: import.meta.env.DEV ? import.meta.env.VITE_DEV_USER || 'admin' : '',
  password: import.meta.env.DEV ? import.meta.env.VITE_DEV_PASSWORD || '' : '',
})
const busy = ref(false)
const sending = ref(false)
const error = ref(
  route.query.sso === 'unbound'
    ? '当前外部身份尚未绑定，请联系管理员。'
    : route.query.sso === 'pending'
      ? '账户已提交，启用后可登录。'
      : route.query.sso === 'error'
        ? '企业登录未完成，请重新发起。'
        : route.query.wechat === 'error'
          ? '微信登录未完成，请重新扫码或使用账号密码。'
          : route.query.wechat === 'success'
            ? '微信登录会话已失效，请重新扫码。'
            : '',
)
const notice = ref('')
const method = ref<'password' | AlternateLoginMethod | SsoType>('password')
const methods = ref<LoginAvailability>()
const enabledMethods = computed(() => alternateMethods.filter((name) => methods.value?.[name]))
const providers = computed(() => methods.value?.sso ?? [])
const passwordAllowed = computed(() => methods.value?.password !== false)
const passwordInput = computed(
  () => (method.value === 'password' && passwordAllowed.value) || method.value === 'ldap',
)
const mfa = ref<'verify' | 'enroll' | null>(null),
  enrollment = ref<MfaSetup>(),
  mfaCode = ref('')
const recoveryCodes = ref<string[]>([])
const tenantCode = computed(() => input.tenantCode.trim())
const destination = ref('')
const code = ref('')
const countdown = ref(0)
let clock: number | undefined

watch(
  tenantCode,
  (tenant, _, onCleanup) => {
    methods.value = undefined
    method.value = 'password'
    if (!tenant) return
    const controller = new AbortController()
    const timer = window.setTimeout(() => {
      availableLoginMethods(tenant, controller.signal)
        .then((result) => {
          if (!controller.signal.aborted) {
            methods.value = result
            if (result.password === false)
              method.value = result.sso?.[0]?.id ?? enabledMethods.value[0] ?? 'password'
          }
        })
        .catch(() => {
          // 备用登录方式查询失败时，账号密码仍可直接登录。
          if (!controller.signal.aborted) methods.value = undefined
        })
    }, METHOD_LOOKUP_DELAY)
    onCleanup(() => {
      window.clearTimeout(timer)
      controller.abort()
    })
  },
  { immediate: true },
)

watch([tenantCode, destination], () => {
  notice.value = ''
  code.value = ''
})

function selectMethod(value: typeof method.value): void {
  if (method.value === value) return
  method.value = value
  destination.value = code.value = error.value = notice.value = ''
}

function navigateMethods(event: KeyboardEvent): void {
  const container = event.currentTarget as HTMLElement
  const tabs = [...container.querySelectorAll<HTMLButtonElement>('[role="tab"]:not(:disabled)')]
  const index = tabs.indexOf(event.target as HTMLButtonElement)
  if (index < 0 || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const next =
    event.key === 'Home'
      ? 0
      : event.key === 'End'
        ? tabs.length - 1
        : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length
  const tab = tabs[next]
  tab?.click()
  tab?.focus()
}

function codeRequest(): CodeRequest {
  if (!tenantCode.value) throw new ApiError(400, 'VALIDATION_FAILED', '请输入企业编码。')
  const selected = method.value
  if (selected !== 'sms' && selected !== 'email') {
    throw new ApiError(400, 'VALIDATION_FAILED', '请选择验证码登录方式。')
  }
  return {
    tenantCode: tenantCode.value,
    method: selected,
    destination: loginDestination(selected, destination.value),
  }
}

function startCountdown(): void {
  countdown.value = RESEND_SECONDS
  window.clearInterval(clock)
  clock = window.setInterval(() => {
    countdown.value--
    if (!countdown.value) window.clearInterval(clock)
  }, 1000)
}

onBeforeUnmount(() => window.clearInterval(clock))
onMounted(() => {
  if (route.query.mfa === 'verify' || route.query.mfa === 'enroll') {
    void loginMfaStatus()
      .then(({ step }) => {
        mfa.value = step
        error.value = ''
      })
      .catch((cause: unknown) => {
        error.value = messageOf(cause)
      })
  }
})

async function finishLogin(): Promise<void> {
  await router.replace(session.user?.mustChangePassword ? '/account/password' : '/')
}

async function submitMfa(): Promise<void> {
  if (busy.value || !mfa.value) return
  busy.value = true
  error.value = ''
  try {
    if (mfa.value === 'enroll') {
      if (!enrollment.value) {
        enrollment.value = await loginMfaSetup()
        return
      }
      const result = await activateLoginMfa(mfaCode.value.trim())
      session.acceptLogin(result.user)
      recoveryCodes.value = result.recoveryCodes
      enrollment.value = undefined
    } else {
      session.acceptLogin(await verifyLoginMfa(mfaCode.value.trim()))
      await finishLogin()
    }
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

async function sendCode(): Promise<void> {
  if (sending.value || busy.value || countdown.value) return
  sending.value = true
  error.value = notice.value = ''
  try {
    await sendLoginCode(codeRequest())
    notice.value = '如该号码或邮箱已绑定，验证码将发送到对应账户。'
    startCountdown()
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    sending.value = false
  }
}

async function submit(): Promise<void> {
  if (busy.value || sending.value) return
  busy.value = true
  error.value = ''
  try {
    if (method.value === 'ldap') {
      session.acceptLogin(
        await ldapLogin({
          ...input,
          tenantCode: tenantCode.value,
          username: input.username.trim(),
        }),
      )
      await finishLogin()
      return
    }
    const provider = providers.value.find((item) => item.id === method.value)
    if (provider) {
      window.location.assign(ssoStart(provider.id, tenantCode.value))
      return
    }
    if (method.value === 'wechat') {
      const result = await startWechatLogin(tenantCode.value)
      goToWechat(result.authorizationUrl)
      return
    }
    if (method.value === 'password') {
      if (!passwordAllowed.value) {
        error.value = '本企业已关闭密码登录，请选择企业登录方式。'
        return
      }
      await session.signIn({
        ...input,
        tenantCode: tenantCode.value,
        username: input.username.trim(),
      })
    } else {
      if (!/^\d{6}$/.test(code.value.trim())) {
        error.value = '请输入 6 位验证码。'
        return
      }
      await session.signInByCode({ ...codeRequest(), code: code.value.trim() })
    }
    await finishLogin()
  } catch (cause: unknown) {
    if (
      cause instanceof ApiError &&
      ['MFA_REQUIRED', 'MFA_ENROLLMENT_REQUIRED'].includes(cause.code)
    ) {
      mfa.value = cause.code === 'MFA_REQUIRED' ? 'verify' : 'enroll'
      input.password = ''
    } else error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <main class="login-page">
    <aside class="login-identity" aria-label="柒衡 ERP">
      <header class="login-brand"><BrandMark /></header>
      <div class="login-introduction">
        <h2>柒衡 ERP</h2>
        <p>采购、销售、库存与财务协同管理</p>
      </div>
    </aside>
    <section class="login-form-wrap" aria-labelledby="login-title">
      <div class="login-form-content">
        <h1 id="login-title">{{ mfa ? '账号安全验证' : '登录工作台' }}</h1>
        <section v-if="recoveryCodes.length">
          <p>请保存恢复码。每个恢复码可使用一次，本页只显示一次。</p>
          <ul>
            <li v-for="value in recoveryCodes" :key="value">
              <code>{{ value }}</code>
            </li>
          </ul>
          <el-button class="login-submit" type="primary" @click="finishLogin"
            >已保存，进入工作台</el-button
          >
        </section>
        <el-form v-else-if="mfa" label-position="top" @submit.prevent="submitMfa">
          <MfaEnrollment v-if="enrollment" :setup="enrollment" />
          <p v-if="mfa === 'enroll' && !enrollment">当前角色需要绑定验证器才能进入工作台。</p>
          <el-form-item
            v-if="mfa === 'verify' || enrollment"
            label="验证码或恢复码"
            for="mfa-login-code"
          >
            <el-input
              id="mfa-login-code"
              v-model="mfaCode"
              autocomplete="one-time-code"
              maxlength="64"
              required
            />
          </el-form-item>
          <el-alert v-if="error" :title="error" type="error" :closable="false" />
          <el-button class="login-submit" native-type="submit" type="primary" :loading="busy">
            {{ mfa === 'enroll' && !enrollment ? '绑定验证器' : '验证' }}
          </el-button>
        </el-form>
        <el-form v-else label-position="top" @submit.prevent="submit">
          <el-form-item label="企业编码" for="tenant">
            <el-input
              id="tenant"
              v-model="input.tenantCode"
              :prefix-icon="OfficeBuilding"
              placeholder="请输入企业编码"
              autocomplete="organization"
              maxlength="32"
              :disabled="busy || sending"
              required
            />
          </el-form-item>
          <div
            v-if="enabledMethods.length || providers.length"
            class="login-methods"
            role="group"
            aria-label="登录方式"
          >
            <div
              class="login-method-tabs"
              role="tablist"
              aria-label="选择登录方式"
              @keydown="navigateMethods"
            >
              <button
                v-if="passwordAllowed"
                id="login-method-password"
                role="tab"
                type="button"
                aria-controls="login-method-panel"
                :aria-selected="method === 'password'"
                :tabindex="method === 'password' ? 0 : -1"
                :disabled="busy || sending"
                @click="selectMethod('password')"
              >
                账号密码
              </button>
              <button
                v-for="name in enabledMethods"
                :key="name"
                :id="'login-method-' + name"
                role="tab"
                type="button"
                aria-controls="login-method-panel"
                :aria-selected="method === name"
                :tabindex="method === name ? 0 : -1"
                :disabled="busy || sending"
                @click="selectMethod(name)"
              >
                {{ methodLabels[name] }}
              </button>
              <button
                v-for="provider in providers"
                :key="provider.id"
                :id="'login-method-' + provider.id"
                role="tab"
                type="button"
                aria-controls="login-method-panel"
                :aria-selected="method === provider.id"
                :tabindex="method === provider.id ? 0 : -1"
                :disabled="busy || sending"
                @click="selectMethod(provider.id)"
              >
                {{ provider.name }}
              </button>
            </div>
          </div>
          <div
            id="login-method-panel"
            :role="enabledMethods.length || providers.length ? 'tabpanel' : undefined"
            :aria-labelledby="
              enabledMethods.length || providers.length ? 'login-method-' + method : undefined
            "
          >
            <el-form-item v-if="passwordInput" label="用户名" for="username">
              <el-input
                id="username"
                v-model="input.username"
                :prefix-icon="User"
                placeholder="请输入用户名"
                autocomplete="username"
                maxlength="64"
                required
              />
            </el-form-item>
            <el-form-item v-if="passwordInput" label="密码" for="password">
              <el-input
                id="password"
                v-model="input.password"
                :prefix-icon="Lock"
                placeholder="请输入密码"
                type="password"
                autocomplete="current-password"
                show-password
                required
              />
            </el-form-item>
            <template v-if="method === 'sms' || method === 'email'">
              <el-form-item :label="contactLabel(method)" for="login-destination">
                <el-input
                  id="login-destination"
                  v-model="destination"
                  :type="method === 'sms' ? 'tel' : 'email'"
                  :autocomplete="method === 'sms' ? 'tel' : 'email'"
                  :maxlength="method === 'sms' ? 14 : 254"
                  :placeholder="'请输入' + contactLabel(method)"
                  :disabled="busy || sending"
                  required
                />
              </el-form-item>
              <el-form-item label="验证码" for="login-code">
                <div class="login-code">
                  <el-input
                    id="login-code"
                    v-model="code"
                    inputmode="numeric"
                    autocomplete="one-time-code"
                    maxlength="6"
                    placeholder="请输入验证码"
                    required
                  />
                  <el-button :disabled="busy || countdown > 0" :loading="sending" @click="sendCode">
                    {{ countdown ? countdown + ' 秒后重发' : '发送验证码' }}
                  </el-button>
                </div>
              </el-form-item>
            </template>
            <p v-if="method === 'wechat'" class="wechat-hint">
              打开微信扫码，使用已绑定的微信身份登录。
            </p>
            <el-alert
              v-if="notice"
              class="form-alert"
              type="success"
              :closable="false"
              :title="notice"
            />
            <el-alert
              v-if="error"
              class="form-alert"
              type="error"
              :closable="false"
              :title="error"
              show-icon
            />
            <el-button class="login-submit" native-type="submit" type="primary" :loading="busy">{{
              busy ? '正在登录…' : method === 'wechat' ? '前往微信扫码' : '登录'
            }}</el-button>
          </div>
        </el-form>
      </div>
      <footer class="login-version">柒衡 ERP v{{ appVersion }}</footer>
    </section>
  </main>
</template>

<style scoped>
.login-page {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 3fr);
  min-height: 100dvh;
  color: var(--erp-text-1);
  background: var(--erp-fill-1);
}

.login-identity {
  display: flex;
  min-width: 0;
  flex-direction: column;
  padding: 32px clamp(24px, 4vw, 64px);
  border-inline-end: 1px solid var(--erp-primary-2);
  background: var(--erp-primary-1);
}

.login-brand {
  align-self: flex-start;
}

.login-introduction {
  display: flex;
  flex: 1;
  justify-content: center;
  flex-direction: column;
  gap: 16px;
  padding-block: 80px;
}

.login-introduction h2 {
  margin: 0;
  color: var(--erp-primary-7);
  font-size: clamp(28px, 3vw, 40px);
  font-weight: 600;
  line-height: 1.4;
}

.login-introduction p {
  max-width: 22em;
  margin: 0;
  color: var(--erp-text-2);
  font-size: 16px;
  line-height: 1.8;
}

.login-form-wrap {
  display: flex;
  min-width: 0;
  min-height: 100dvh;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 24px;
  padding: 64px clamp(24px, 5vw, 72px);
}

.login-form-content {
  width: min(100%, 432px);
  padding: 32px;
  border: 1px solid var(--erp-border-1);
  border-radius: var(--erp-radius-lg);
  background: var(--erp-bg);
}

.login-form-content h1 {
  margin: 0 0 24px;
  color: var(--erp-text-1);
  font-size: 24px;
  font-weight: 600;
  line-height: 32px;
}

.login-form-content :deep(.el-form-item) {
  margin-bottom: 20px;
}

.login-form-content :deep(.el-input) {
  --el-input-height: var(--erp-control, 32px);
  --el-input-border-radius: var(--erp-radius);
}

.login-form-content :deep(.el-input__wrapper) {
  padding-inline: 10px;
}

.login-form-content .login-submit {
  width: 100%;
  height: var(--erp-control, 32px);
  margin: 4px 0 0;
  border-radius: var(--erp-radius);
  font-weight: 500;
}

.login-methods {
  min-width: 0;
  margin-bottom: 20px;
}

.login-method-tabs {
  display: flex;
  gap: 20px;
  overflow-x: auto;
  overscroll-behavior-inline: contain;
  border-bottom: 1px solid var(--erp-border-1);
}

.login-method-tabs button {
  flex: 0 0 auto;
  min-height: 36px;
  padding: 0 0 6px;
  border: 0;
  border-bottom: 2px solid transparent;
  color: var(--erp-text-2);
  background: transparent;
  font: inherit;
  cursor: pointer;
}

.login-method-tabs button[aria-selected='true'] {
  border-bottom-color: var(--erp-primary-6);
  color: var(--erp-primary-7);
  font-weight: 500;
}

.login-method-tabs button:focus-visible {
  outline: 2px solid var(--erp-primary-6);
  outline-offset: -2px;
}

.login-method-tabs button:disabled {
  color: var(--erp-text-3);
  cursor: wait;
}

.login-code {
  display: grid;
  width: 100%;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
}

.wechat-hint {
  margin: 0 0 16px;
  color: var(--erp-text-2);
  line-height: 22px;
}

.login-version {
  color: var(--erp-text-3);
  font-size: 12px;
  line-height: 20px;
}

@media (max-width: 800px) {
  .login-page {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: auto minmax(0, 1fr);
    background: var(--erp-bg);
  }

  .login-identity {
    padding: 24px;
    border: 0;
    background: var(--erp-bg);
  }

  .login-introduction {
    display: none;
  }

  .login-form-wrap {
    min-height: 0;
    justify-content: flex-start;
    padding: 24px 24px 32px;
  }

  .login-form-content {
    width: min(100%, 384px);
    padding: 0;
    border: 0;
  }

  .login-version {
    margin-top: auto;
    padding-top: 32px;
  }
}
</style>
