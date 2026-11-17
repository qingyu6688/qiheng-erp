<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  accountMfa,
  activateAccountMfa,
  disableAccountMfa,
  setupAccountMfa,
} from '../api/platform-security'
import type { MfaSetup, MfaStatus } from '../api/platform-security'
import { request, messageOf } from '../api/http'
import MfaEnrollment from '../components/MfaEnrollment.vue'

const status = ref<MfaStatus>(),
  setup = ref<MfaSetup>(),
  code = ref(''),
  busy = ref(false),
  error = ref('')
const recovery = ref<string[]>([]),
  identities = ref<string[]>([])
const labels: Record<string, string> = {
  oidc: 'OIDC',
  ldap: 'LDAP / AD',
  wecom: '企业微信',
  dingtalk: '钉钉',
  feishu: '飞书',
  wechat: '微信',
  sms: '手机号',
  email: '邮箱',
}
async function load(): Promise<void> {
  try {
    status.value = await accountMfa()
    const data = await request<Record<string, unknown>>({ url: '/account/login-methods' })
    identities.value = Object.keys(labels)
      .filter((key) => Boolean(data[key]))
      .map((key) => labels[key]!)
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  }
}
async function update(action: 'setup' | 'activate' | 'disable'): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    if (action === 'setup') setup.value = await setupAccountMfa()
    else if (action === 'activate') {
      recovery.value = (await activateAccountMfa(code.value.trim())).recoveryCodes
      setup.value = undefined
      code.value = ''
      await load()
    } else {
      await disableAccountMfa(code.value.trim())
      code.value = ''
      recovery.value = []
      await load()
    }
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>

<template>
  <div class="page-heading"><h1>账户安全</h1></div>
  <section class="panel">
    <h2>多因素验证</h2>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="status">
      {{ status.enabled ? '验证器已绑定' : '尚未绑定验证器'
      }}<span v-if="status.required">，当前角色要求多因素验证。</span>
    </p>
    <el-button
      v-if="status && !status.enabled && !setup"
      type="primary"
      :loading="busy"
      @click="update('setup')"
      >绑定验证器</el-button
    >
    <MfaEnrollment v-if="setup" :setup="setup" />
    <el-form
      v-if="setup || (status?.enabled && !status.required)"
      label-position="top"
      class="security-code"
      @submit.prevent="update(setup ? 'activate' : 'disable')"
    >
      <el-form-item label="验证码" for="account-factor"
        ><el-input id="account-factor" v-model="code" autocomplete="one-time-code" maxlength="64"
      /></el-form-item>
      <el-button
        :type="setup ? 'primary' : 'danger'"
        native-type="submit"
        :loading="busy"
        :disabled="!code.trim()"
        >{{ setup ? '验证并启用' : '关闭多因素验证' }}</el-button
      >
    </el-form>
    <div v-if="recovery.length">
      <h3>一次性恢复码</h3>
      <p>请保存到安全位置，关闭后将隐藏。</p>
      <ul>
        <li v-for="value in recovery" :key="value">
          <code>{{ value }}</code>
        </li>
      </ul>
      <el-button @click="recovery = []">已保存</el-button>
    </div>
  </section>
  <section class="panel security-identity-list">
    <h2>已绑定身份</h2>
    <p v-if="!identities.length">当前没有外部身份绑定。</p>
    <ul v-else>
      <li v-for="identity in identities" :key="identity">{{ identity }}</li>
    </ul>
    <router-link to="/account/login-methods" class="text-link">管理个人登录方式</router-link>
  </section>
</template>

<style scoped>
.security-code {
  max-width: 340px;
}
.security-identity-list {
  margin-top: 16px;
}
</style>
