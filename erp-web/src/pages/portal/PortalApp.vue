<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { portalRead, portalWrite } from '../../api/portal'
import { ApiError, messageOf } from '../../api/http'
import { portalViews, type PortalUser } from '../../types/portal'
import BrandMark from '../../components/BrandMark.vue'

const router = useRouter()
const user = ref<PortalUser>()
const busy = ref(false)
const restoring = ref(true)
const smsEnabled = ref(false)
const sms = ref(false)
const assistantEnabled = ref(false)
const navigation = computed(() =>
  portalViews.filter((item) => item.key !== 'assistant' || assistantEnabled.value),
)
const error = ref('')
const notice = ref('')
const credentials = reactive({ tenantCode: '', username: '', password: '', code: '' })

async function restore(): Promise<void> {
  try {
    const config = await portalRead<{ smsEnabled: boolean }>('/auth/csrf')
    smsEnabled.value = Boolean(config.smsEnabled)
    user.value = await portalRead<PortalUser>('/auth/me')
    await loadAssistantStatus()
  } catch (cause: unknown) {
    if (!(cause instanceof ApiError && cause.status === 401)) error.value = messageOf(cause)
  } finally {
    restoring.value = false
  }
}

async function loadAssistantStatus(): Promise<void> {
  try {
    assistantEnabled.value = (await portalRead<{ enabled: boolean }>('/assistant/status')).enabled
  } catch (cause: unknown) {
    if (cause instanceof ApiError && cause.status === 401) expire()
    else assistantEnabled.value = false
  }
}

async function authenticate(sendCode = false): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  const { tenantCode, username, password, code } = credentials
  try {
    if (sendCode) {
      await portalWrite('/auth/code/send', { tenantCode, username })
      notice.value = '如果账号已开通短信登录，验证码将发送到绑定手机。'
    } else {
      user.value = sms.value
        ? await portalWrite<PortalUser>('/auth/code/login', { tenantCode, username, code })
        : await portalWrite<PortalUser>('/auth/login', { tenantCode, username, password })
      credentials.password = ''
      credentials.code = ''
      await loadAssistantStatus()
    }
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

function expire(): void {
  user.value = undefined
  assistantEnabled.value = false
  error.value = '会话已过期，请重新登录。'
}

async function logout(): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    await portalWrite('/auth/logout')
    user.value = undefined
    assistantEnabled.value = false
    await router.replace('/')
    await portalRead('/auth/csrf')
  } catch (cause: unknown) {
    if (cause instanceof ApiError && cause.status === 401) expire()
    else error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

onMounted(restore)
</script>

<template>
  <header class="portal-header">
    <BrandMark class="portal-brand" label="柒衡 ERP 客户门户" />
    <div v-if="user" class="portal-account">
      <span>{{ user.customerName }} · {{ user.contactName }}</span>
      <button type="button" :disabled="busy" @click="logout">退出</button>
    </div>
  </header>
  <p v-if="restoring" class="portal-shell" role="status">正在检查登录状态…</p>
  <main v-else-if="!user" class="portal-login">
    <h1>客户登录</h1>
    <p v-if="error" class="portal-error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <form class="portal-form" @submit.prevent="authenticate()">
      <label
        >企业编码<input
          v-model.trim="credentials.tenantCode"
          required
          maxlength="64"
          autocomplete="organization"
      /></label>
      <label
        >联系人账号<input
          v-model.trim="credentials.username"
          required
          maxlength="128"
          autocomplete="username"
      /></label>
      <label v-if="!sms"
        >密码<input
          v-model="credentials.password"
          required
          type="password"
          autocomplete="current-password"
          maxlength="128"
      /></label>
      <div v-else class="portal-form-row">
        <label
          >验证码<input
            v-model.trim="credentials.code"
            required
            inputmode="numeric"
            autocomplete="one-time-code"
            maxlength="8"
        /></label>
        <button
          type="button"
          :disabled="busy || !credentials.tenantCode || !credentials.username"
          @click="authenticate(true)"
        >
          发送验证码
        </button>
      </div>
      <button class="portal-primary" :disabled="busy" type="submit">登录</button>
      <button v-if="smsEnabled" type="button" :disabled="busy" @click="sms = !sms">
        {{ sms ? '使用密码登录' : '使用短信登录' }}
      </button>
    </form>
  </main>
  <div v-else class="portal-shell">
    <nav class="portal-nav" aria-label="客户门户菜单">
      <RouterLink v-for="item in navigation" :key="item.key" :to="item.path">{{
        item.label
      }}</RouterLink>
    </nav>
    <p v-if="error" class="portal-error" role="alert">{{ error }}</p>
    <RouterView v-slot="{ Component }"><component :is="Component" @expired="expire" /></RouterView>
  </div>
</template>
