<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { changePassword } from '../api/auth'
import { messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'

const form = reactive({ old: '', fresh: '', repeat: '' })
const busy = ref(false)
const error = ref('')
const repeatError = ref('')
const passwordError = ref('')
const done = ref(false)
const router = useRouter()
const session = useSessionStore()
async function finish(): Promise<void> {
  try {
    if (session.user?.mustChangePassword) await session.refresh()
    await router.replace('/warehouses')
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  }
}
async function submit(): Promise<void> {
  if (busy.value) return
  error.value = ''
  repeatError.value = passwordError.value = ''
  if ([...form.fresh].length < 8 || new TextEncoder().encode(form.fresh).length > 72) {
    passwordError.value = '密码至少 8 个字符，UTF-8 编码最多 72 字节。'
    return
  }
  if (form.fresh === form.old) {
    passwordError.value = '新密码须与原密码不同。'
    return
  }
  if (form.fresh !== form.repeat) {
    repeatError.value = '两次输入的新密码不一致。'
    return
  }
  busy.value = true
  try {
    await changePassword(form.old, form.fresh)
    done.value = true
    form.old = form.fresh = form.repeat = ''
    await session.refresh()
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <h1>修改密码</h1>
  <section class="panel password-panel" aria-label="修改密码">
    <template v-if="done">
      <el-alert type="success" title="密码已修改。" :closable="false" show-icon />
      <el-alert v-if="error" type="error" :title="error" :closable="false" />
      <el-button type="primary" @click="finish">返回工作台</el-button>
    </template>
    <el-form v-else label-position="top" @submit.prevent="submit">
      <el-alert
        v-if="session.user?.mustChangePassword"
        class="form-alert"
        type="warning"
        title="请先修改初始密码，再使用业务功能。"
        :closable="false"
        show-icon
      />
      <el-form-item label="原密码" for="old-password"
        ><el-input
          id="old-password"
          v-model="form.old"
          type="password"
          autocomplete="current-password"
          show-password
          required
      /></el-form-item>
      <el-form-item label="新密码" for="new-password" :error="passwordError"
        ><el-input
          id="new-password"
          v-model="form.fresh"
          type="password"
          autocomplete="new-password"
          placeholder="至少 8 个字符"
          show-password
          required
      /></el-form-item>
      <el-form-item label="再次输入新密码" for="repeat-password" :error="repeatError"
        ><el-input
          id="repeat-password"
          v-model="form.repeat"
          type="password"
          autocomplete="new-password"
          show-password
          required
      /></el-form-item>
      <el-alert
        v-if="error"
        class="form-alert"
        :title="error"
        type="error"
        :closable="false"
        show-icon
      />
      <el-button native-type="submit" type="primary" :loading="busy">保存新密码</el-button>
    </el-form>
  </section>
</template>
