<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock, OfficeBuilding } from '@element-plus/icons-vue'
import { useSessionStore } from '../stores/session'
import { messageOf } from '../api/http'
import BrandMark from '../components/BrandMark.vue'

const router = useRouter()
const session = useSessionStore()
const input = reactive({ tenantCode: '', username: '', password: '' })
const busy = ref(false)
const error = ref('')

async function submit(): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await session.signIn({
      ...input,
      tenantCode: input.tenantCode.trim(),
      username: input.username.trim(),
    })
    await router.replace(session.user?.mustChangePassword ? '/account/password' : '/warehouses')
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <main class="auth-page">
    <section class="login-panel" aria-labelledby="login-title">
      <BrandMark />
      <h1 id="login-title">登录工作台</h1>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="企业编码" for="tenant"
          ><el-input
            id="tenant"
            v-model="input.tenantCode"
            :prefix-icon="OfficeBuilding"
            placeholder="请输入企业编码"
            autocomplete="organization"
            maxlength="32"
            required
        /></el-form-item>
        <el-form-item label="用户名" for="username"
          ><el-input
            id="username"
            v-model="input.username"
            :prefix-icon="User"
            placeholder="请输入用户名"
            autocomplete="username"
            maxlength="64"
            required
        /></el-form-item>
        <el-form-item label="密码" for="password"
          ><el-input
            id="password"
            v-model="input.password"
            :prefix-icon="Lock"
            placeholder="请输入密码"
            type="password"
            autocomplete="current-password"
            show-password
            required
        /></el-form-item>
        <el-alert
          v-if="error"
          class="form-alert"
          type="error"
          :closable="false"
          :title="error"
          show-icon
        />
        <el-button class="login-submit" native-type="submit" type="primary" :loading="busy">{{
          busy ? '正在登录…' : '登录'
        }}</el-button>
      </el-form>
    </section>
  </main>
</template>
