<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { allRecords } from '../api/admin'
import { messageOf } from '../api/http'
import {
  bindIdentity,
  listProviders,
  readSecurityPolicy,
  resetUserMfa,
  saveProvider,
  saveSecurityPolicy,
  testProvider,
  unbindIdentity,
} from '../api/platform-security'
import type { SecurityPolicy, SsoProvider } from '../api/platform-security'
import type { SsoType } from '../types/auth'
import type { DataRow } from '../types/admin'
import { useSessionStore } from '../stores/session'

const route = useRoute(),
  session = useSessionStore()
const policyMode = computed(() => route.name === 'security-policy')
const loading = ref(false),
  busy = ref(false),
  error = ref(''),
  notice = ref('')
const providers = ref<SsoProvider[]>([]),
  selection = ref<SsoType>('oidc'),
  secret = ref('')
const titles: Record<SsoType, string> = {
  oidc: 'OIDC',
  ldap: 'LDAP / AD',
  wecom: '企业微信',
  dingtalk: '钉钉',
  feishu: '飞书',
}
const configKeys: Record<SsoType, string[]> = {
  oidc: [
    'issuer',
    'authorizationUrl',
    'tokenUrl',
    'jwksUrl',
    'clientId',
    'callbackUrl',
    'clientAuth',
  ],
  ldap: ['serverUrl', 'baseDn', 'userAttribute', 'bindDn'],
  wecom: ['corpId', 'agentId', 'callbackUrl'],
  dingtalk: ['clientId', 'callbackUrl'],
  feishu: ['clientId', 'callbackUrl'],
}
const configLabels: Record<string, string> = {
  issuer: '签发方 Issuer',
  authorizationUrl: '授权地址',
  tokenUrl: '令牌地址',
  jwksUrl: 'JWKS 地址',
  clientId: '应用 ID',
  callbackUrl: '回调地址',
  clientAuth: '客户端认证方式',
  serverUrl: '目录服务地址',
  baseDn: '目录 Base DN',
  userAttribute: '账号属性',
  bindDn: '服务账号 DN',
  emailAttribute: '邮箱属性',
  corpId: '企业 ID',
  agentId: '应用 Agent ID',
}
const edit = reactive({
  enabled: false,
  name: '',
  config: {} as Record<string, string>,
  version: 0,
  secretConfigured: false,
})
const policies = ref<SecurityPolicy>(),
  roles = ref<DataRow[]>([]),
  users = ref<DataRow[]>([])
const identity = reactive({ user: '', method: 'oidc' as SsoType, subject: '' })
function choose(id: SsoType): void {
  selection.value = id
  const item = providers.value.find((value) => value.id === id)
  Object.assign(edit, {
    enabled: item?.enabled ?? false,
    name: item?.name ?? titles[id],
    config: { ...item?.config },
    version: item?.version ?? 0,
    secretConfigured: item?.secretConfigured ?? false,
  })
  secret.value = ''
  error.value = ''
  notice.value = ''
}
async function reload(): Promise<void> {
  loading.value = true
  try {
    if (policyMode.value) {
      policies.value = await readSecurityPolicy()
      const [a, b] = await Promise.allSettled([
        session.can('role:view') ? allRecords('roles') : Promise.resolve([]),
        session.can('user:view') ? allRecords('users') : Promise.resolve([]),
      ])
      roles.value = a.status === 'fulfilled' ? a.value : []
      users.value = b.status === 'fulfilled' ? b.value : []
    } else {
      providers.value = await listProviders()
      choose(selection.value)
    }
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    loading.value = false
  }
}
async function perform(action: () => Promise<unknown>, message: string): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = notice.value = ''
  try {
    await action()
    notice.value = message
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
async function saveSso(): Promise<void> {
  await perform(async () => {
    const data = await saveProvider(selection.value, {
      name: edit.name,
      enabled: edit.enabled,
      config: Object.fromEntries(Object.entries(edit.config).filter(([, value]) => value.trim())),
      version: edit.version,
      secret: secret.value || undefined,
    })
    providers.value = [...providers.value.filter((item) => item.id !== selection.value), data]
    choose(selection.value)
  }, '登录方式已保存')
}
async function savePolicy(): Promise<void> {
  if (!policies.value) return
  const input = policies.value
  await perform(async () => {
    policies.value = await saveSecurityPolicy(input)
  }, '企业安全策略已保存')
}
function manageIdentity(action: 'bind' | 'unbind' | 'reset'): void {
  if (!identity.user) return
  if (
    action !== 'bind' &&
    !window.confirm(action === 'reset' ? '确认重置该用户的多因素验证？' : '确认解除该登录身份？')
  )
    return
  void perform(
    () =>
      action === 'reset'
        ? resetUserMfa(identity.user)
        : action === 'unbind'
          ? unbindIdentity(identity.user, identity.method)
          : bindIdentity(identity.user, identity.method, identity.subject.trim()),
    action === 'reset' ? '已重置多因素验证' : action === 'unbind' ? '已解除绑定' : '外部身份已绑定',
  )
}
watch(
  policyMode,
  () => {
    error.value = notice.value = secret.value = ''
    void reload()
  },
  { immediate: true },
)
</script>

<template>
  <div class="page-heading">
    <div>
      <h1>{{ policyMode ? '企业安全策略' : '企业单点登录' }}</h1>
    </div>
    <router-link v-if="!policyMode" to="/login-settings" class="text-link"
      >返回登录方式设置</router-link
    >
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-alert v-if="notice" :title="notice" type="success" :closable="false" />
  <section v-if="!policyMode" v-loading="loading" class="panel">
    <el-select
      :model-value="selection"
      aria-label="登录提供方"
      class="security-select"
      @change="choose($event)"
    >
      <el-option v-for="(name, type) in titles" :key="type" :value="type" :label="name" />
    </el-select>
    <el-form label-width="140px" class="security-form" @submit.prevent="saveSso">
      <el-form-item label="启用登录方式"><el-switch v-model="edit.enabled" /></el-form-item>
      <el-form-item label="登录页显示名称"
        ><el-input v-model="edit.name" maxlength="64"
      /></el-form-item>
      <el-form-item v-for="key in configKeys[selection]" :key="key" :label="configLabels[key]">
        <el-select
          v-if="key === 'clientAuth'"
          v-model="edit.config[key]"
          clearable
          placeholder="默认使用客户端 Basic 认证"
        >
          <el-option label="客户端 Basic 认证" value="client_secret_basic" /><el-option
            label="请求体客户端凭据"
            value="client_secret_post"
          />
        </el-select>
        <el-input
          v-else
          v-model="edit.config[key]"
          :placeholder="
            key === 'callbackUrl' ? 'https://erp.example.com/api/v1/auth/sso/callback' : undefined
          "
        />
      </el-form-item>
      <el-form-item :label="selection === 'ldap' ? '服务账号密码' : '应用密钥'">
        <el-input
          v-model="secret"
          type="password"
          show-password
          autocomplete="new-password"
          :placeholder="edit.secretConfigured ? '留空保留已保存的密钥' : '填写提供方下发的密钥'"
        />
      </el-form-item>
      <el-form-item
        ><el-button type="primary" :loading="busy" native-type="submit">保存配置</el-button>
        <el-button
          :disabled="busy || !edit.version"
          @click="perform(() => testProvider(selection), '连接检查通过')"
          >测试连接</el-button
        ></el-form-item
      >
    </el-form>
  </section>
  <template v-else>
    <section v-loading="loading" class="panel">
      <el-form
        v-if="policies"
        label-width="160px"
        class="security-form"
        @submit.prevent="savePolicy"
      >
        <el-form-item label="允许密码登录"
          ><el-switch v-model="policies.passwordLoginAllowed"
        /></el-form-item>
        <el-form-item label="强制 MFA 的角色"
          ><el-select
            v-model="policies.mfaRequiredRoles"
            multiple
            filterable
            placeholder="选择角色"
          >
            <el-option
              v-for="row in roles"
              :key="row.id"
              :value="row.id"
              :label="String(row.name ?? row.code ?? row.id)"
            /> </el-select
        ></el-form-item>
        <el-form-item label="允许 SSO 自动建档"
          ><el-switch v-model="policies.ssoAutoProvision"
        /></el-form-item>
        <el-form-item v-if="policies.ssoAutoProvision" label="新用户默认角色"
          ><el-select v-model="policies.ssoDefaultRoleId" clearable>
            <el-option
              v-for="row in roles"
              :key="row.id"
              :value="row.id"
              :label="String(row.name ?? row.code ?? row.id)"
            /> </el-select
        ></el-form-item>
        <el-form-item
          ><el-button type="primary" native-type="submit" :loading="busy"
            >保存策略</el-button
          ></el-form-item
        >
      </el-form>
    </section>
    <section class="panel security-identities">
      <h2>用户身份维护</h2>
      <el-form label-width="160px" class="security-form">
        <el-form-item label="本地用户"
          ><el-select v-model="identity.user" filterable placeholder="选择用户">
            <el-option
              v-for="row in users"
              :key="row.id"
              :value="row.id"
              :label="String(row.displayName ?? row.username ?? row.id)"
            /> </el-select
        ></el-form-item>
        <el-form-item label="外部身份渠道"
          ><el-select v-model="identity.method"
            ><el-option
              v-for="(name, type) in titles"
              :key="type"
              :value="type"
              :label="name" /></el-select
        ></el-form-item>
        <el-form-item label="外部身份标识"><el-input v-model="identity.subject" /></el-form-item>
        <el-form-item
          ><el-button
            type="primary"
            :disabled="busy || !identity.user || !identity.subject.trim()"
            @click="manageIdentity('bind')"
            >绑定外部身份</el-button
          >
          <el-button :disabled="busy || !identity.user" @click="manageIdentity('unbind')"
            >解除绑定</el-button
          >
          <el-button
            :disabled="busy || !identity.user || identity.user === session.user?.id"
            @click="manageIdentity('reset')"
            >重置 MFA</el-button
          ></el-form-item
        >
      </el-form>
    </section>
  </template>
</template>

<style scoped>
.security-select {
  max-width: 240px;
  margin-bottom: 16px;
}
.security-form {
  max-width: 680px;
}
.security-form .el-select {
  width: 100%;
}
.security-identities {
  margin-top: 16px;
}
</style>
