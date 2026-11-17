<script setup lang="ts">
import { ref } from 'vue'
import { ApiFailure, errorText } from '../api/http'
import { nativeApp, useSession } from '../stores/session'

const session = useSession()
const deviceMode = nativeApp()
const address = ref(import.meta.env.VITE_ERP_ORIGIN ?? '')
const pairingCode = ref('')
const tenantCode = ref('')
const username = ref('')
const password = ref('')
const code = ref('')
const needsMfa = ref(false)
const busy = ref(false)
const error = ref('')

async function submit(): Promise<void> {
  if (busy.value) return
  error.value = ''
  busy.value = true
  try {
    if (deviceMode) {
      if (!pairingCode.value.trim()) throw new Error('请输入配对码')
      await session.activate(address.value, pairingCode.value.trim())
      pairingCode.value = ''
    } else if (needsMfa.value) {
      if (!code.value.trim()) throw new Error('请输入动态验证码或恢复码')
      await session.verify(code.value.trim())
      code.value = ''
    } else {
      if (!tenantCode.value.trim() || !username.value.trim() || !password.value)
        throw new Error('请填写企业编码、用户名和密码')
      await session.signIn(tenantCode.value.trim(), username.value.trim(), password.value)
    }
  } catch (cause: unknown) {
    if (cause instanceof ApiFailure && cause.code === 'MFA_REQUIRED') {
      needsMfa.value = true
    } else if (cause instanceof ApiFailure && cause.code === 'MFA_ENROLLMENT_REQUIRED') {
      error.value = '企业要求先绑定验证器。请从管理端完成绑定，再登录移动端。'
    } else {
      error.value = errorText(cause)
    }
  } finally {
    if (!deviceMode && needsMfa.value) password.value = ''
    busy.value = false
  }
}
</script>

<template>
  <view class="panel login">
    <text class="heading">{{ deviceMode ? '绑定 PDA 设备' : '登录柒衡 ERP' }}</text>
    <text v-if="session.notice" class="error" role="alert">{{ session.notice }}</text>
    <text v-if="error" class="error" role="alert">{{ error }}</text>
    <template v-if="deviceMode">
      <text class="muted">从已登录的管理端生成一次性配对码。</text>
      <text class="field-label">ERP 服务地址</text>
      <input v-model="address" class="field" aria-label="ERP 服务地址" placeholder="https://erp.example.com">
      <text class="field-label">配对码</text>
      <input v-model="pairingCode" class="field" aria-label="配对码" :maxlength="160" password @confirm="submit">
    </template>
    <template v-else-if="needsMfa">
      <text class="field-label">动态验证码或恢复码</text>
      <input v-model="code" class="field" aria-label="动态验证码或恢复码" :maxlength="64" @confirm="submit">
      <button class="secondary" :disabled="busy" @click="needsMfa = false; code = ''">返回登录</button>
    </template>
    <template v-else>
      <text class="field-label">企业编码</text>
      <input v-model="tenantCode" class="field" aria-label="企业编码" :maxlength="32">
      <text class="field-label">用户名</text>
      <input v-model="username" class="field" aria-label="用户名" :maxlength="64">
      <text class="field-label">密码</text>
      <input v-model="password" class="field" aria-label="密码" password @confirm="submit">
    </template>
    <wd-button block :loading="busy" :disabled="busy" @click="submit">
      {{ deviceMode ? '配对并登录' : needsMfa ? '验证并登录' : '登录' }}
    </wd-button>
  </view>
</template>
