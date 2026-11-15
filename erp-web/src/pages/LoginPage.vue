<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Box, Document, Lock, OfficeBuilding, User } from '@element-plus/icons-vue'
import { availableLoginMethods, sendLoginCode, startWechatLogin } from '../api/auth'
import { useSessionStore } from '../stores/session'
import { ApiError, messageOf } from '../api/http'
import BrandMark from '../components/BrandMark.vue'
import type { AlternateLoginMethod, CodeRequest, LoginAvailability } from '../types/auth'
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
  route.query.wechat === 'error'
    ? '微信登录未完成，请重新扫码或使用账号密码。'
    : route.query.wechat === 'success'
      ? '微信登录会话已失效，请重新扫码。'
      : '',
)
const notice = ref('')
const method = ref<'password' | AlternateLoginMethod>('password')
const methods = ref<LoginAvailability>()
const enabledMethods = computed(() => alternateMethods.filter((name) => methods.value?.[name]))
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
          if (!controller.signal.aborted) methods.value = result
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
    if (method.value === 'wechat') {
      const result = await startWechatLogin(tenantCode.value)
      goToWechat(result.authorizationUrl)
      return
    }
    if (method.value === 'password') {
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
    await router.replace(session.user?.mustChangePassword ? '/account/password' : '/')
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <main class="login-page">
    <header class="login-brand">
      <BrandMark />
    </header>
    <div class="login-illustration" aria-hidden="true">
      <div class="login-illustration-ring"></div>
      <div class="login-illustration-dashboard">
        <div class="login-illustration-toolbar"><i></i><i></i><i></i></div>
        <div class="login-illustration-content">
          <div class="login-illustration-menu"><i></i><i></i><i></i><i></i></div>
          <div class="login-illustration-chart"><i></i><i></i><i></i><i></i><i></i></div>
        </div>
      </div>
      <span class="login-illustration-icon inventory"><Box /></span>
      <span class="login-illustration-icon document"><Document /></span>
    </div>
    <section class="login-form-wrap" aria-labelledby="login-title">
      <div class="login-form-content">
        <h1 id="login-title">登录工作台</h1>
        <el-form label-position="top" @submit.prevent="submit">
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
            v-if="enabledMethods.length"
            class="login-methods"
            role="group"
            aria-label="登录方式"
          >
            <button
              type="button"
              :aria-pressed="method === 'password'"
              :disabled="busy || sending"
              @click="selectMethod('password')"
            >
              账号密码
            </button>
            <button
              v-for="name in enabledMethods"
              :key="name"
              type="button"
              :aria-pressed="method === name"
              :disabled="busy || sending"
              @click="selectMethod(name)"
            >
              {{ methodLabels[name] }}
            </button>
          </div>
          <el-form-item v-if="method === 'password'" label="用户名" for="username">
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
          <el-form-item v-if="method === 'password'" label="密码" for="password">
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
        </el-form>
      </div>
    </section>
  </main>
</template>

<style scoped>
.login-page {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(0, 1fr);
  min-height: 100dvh;
  background: var(--el-bg-color);
}

.login-brand {
  position: absolute;
  z-index: 1;
  top: 28px;
  inset-inline-start: clamp(24px, 4vw, 56px);
}

.login-form-wrap {
  display: grid;
  place-items: center;
  padding: 100px clamp(24px, 5vw, 76px) 56px;
}

.login-form-content {
  width: min(100%, 412px);
}

.login-form-content h1 {
  margin: 0 0 32px;
  color: var(--el-text-color-primary);
  font-size: clamp(27px, 2.6vw, 36px);
  line-height: 1.25;
  font-weight: 650;
}

.login-form-content :deep(.el-form-item) {
  margin-bottom: 20px;
}

.login-form-content :deep(.el-input) {
  --el-input-height: 44px;
  --el-input-border-radius: 8px;
}

.login-form-content :deep(.el-input__wrapper) {
  padding-inline: 13px;
}

.login-form-content .login-submit {
  width: 100%;
  height: 44px;
  margin: 4px 0 0;
  border-radius: 8px;
  font-weight: 600;
}

.login-methods {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 20px;
  padding: 4px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 9px;
  background: var(--el-fill-color-lighter);
}

.login-methods button {
  flex: 1 0 auto;
  min-height: 36px;
  padding: 0 8px;
  border: 0;
  border-radius: 6px;
  color: var(--el-text-color-regular);
  background: transparent;
  cursor: pointer;
}

.login-methods button[aria-pressed='true'] {
  color: var(--el-color-primary);
  background: var(--el-bg-color);
  font-weight: 600;
  box-shadow: 0 1px 3px color-mix(in srgb, var(--el-text-color-primary) 12%, transparent);
}

.login-methods button:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
}

.login-code {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
}

.wechat-hint {
  margin: 0 0 16px;
  color: var(--el-text-color-secondary);
}

.login-illustration {
  --illustration-line: color-mix(in srgb, var(--el-color-primary) 16%, var(--el-bg-color));
  position: relative;
  display: grid;
  place-items: center;
  isolation: isolate;
  overflow: hidden;
  background: var(--el-color-primary-light-9);
}

.login-illustration-ring {
  position: absolute;
  z-index: -1;
  width: min(78%, 550px);
  aspect-ratio: 1;
  border: 1px solid var(--illustration-line);
  border-radius: 50%;
  background: color-mix(in srgb, var(--el-color-primary) 3%, transparent);
  box-shadow: 0 0 0 32px color-mix(in srgb, var(--el-color-primary) 3%, transparent);
}

.login-illustration-dashboard {
  width: min(65%, 440px);
  aspect-ratio: 1.55;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 14px;
  border: 1px solid var(--illustration-line);
  border-radius: 18px;
  background: var(--el-bg-color);
  box-shadow: 0 24px 54px color-mix(in srgb, var(--el-color-primary) 16%, transparent);
  transform: rotate(-8deg);
}

.login-illustration-toolbar {
  display: flex;
  gap: 5px;
  padding: 4px 4px 12px;
  border-bottom: 1px solid var(--illustration-line);
}

.login-illustration-toolbar i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--illustration-line);
}

.login-illustration-content {
  display: grid;
  grid-template-columns: 1fr 2.4fr;
  gap: 12px;
  flex: 1;
}

.login-illustration-menu,
.login-illustration-chart {
  border-radius: 10px;
  background: var(--el-color-primary-light-9);
}

.login-illustration-menu {
  display: grid;
  align-content: start;
  gap: 14px;
  padding: 16px 12px;
}

.login-illustration-menu i {
  width: 80%;
  height: 6px;
  border-radius: 6px;
  background: var(--illustration-line);
}

.login-illustration-menu i:first-child {
  width: 58%;
  background: var(--el-color-primary-light-5);
}

.login-illustration-menu i:nth-child(even) {
  width: 65%;
}

.login-illustration-chart {
  display: flex;
  align-items: end;
  gap: 9%;
  padding: 12%;
}

.login-illustration-chart i {
  flex: 1;
  height: 36%;
  border-radius: 6px 6px 2px 2px;
  background: var(--el-color-primary-light-5);
}

.login-illustration-chart i:nth-child(2) {
  height: 64%;
}

.login-illustration-chart i:nth-child(3) {
  height: 50%;
}

.login-illustration-chart i:nth-child(4) {
  height: 77%;
}

.login-illustration-chart i:last-child {
  height: 96%;
  background: var(--el-color-primary);
}

.login-illustration-icon {
  position: absolute;
  display: grid;
  place-items: center;
  border-radius: 18px;
  box-shadow: 0 16px 32px color-mix(in srgb, var(--el-color-primary) 22%, transparent);
}

.login-illustration-icon svg {
  width: 51%;
  height: 51%;
}

.login-illustration-icon.inventory {
  top: calc(50% + 52px);
  left: 68%;
  width: clamp(56px, 6.2vw, 96px);
  aspect-ratio: 1;
  color: var(--el-color-white);
  background: var(--el-color-primary);
  transform: rotate(12deg);
}

.login-illustration-icon.document {
  bottom: calc(50% + 52px);
  right: 69%;
  width: clamp(42px, 4.8vw, 72px);
  aspect-ratio: 1;
  border: 1px solid var(--illustration-line);
  color: var(--el-color-primary);
  background: var(--el-bg-color);
  transform: rotate(-12deg);
}

@media (max-width: 800px) {
  .login-page {
    grid-template-columns: minmax(0, 1fr);
  }

  .login-illustration {
    display: none;
  }

  .login-form-wrap {
    padding-top: 100px;
  }
}
</style>
