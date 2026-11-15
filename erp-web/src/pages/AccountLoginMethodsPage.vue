<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  bindContact,
  loginBindings,
  removeBinding,
  sendBindingCode,
  startWechatBinding,
} from '../api/auth'
import { messageOf } from '../api/http'
import type { AlternateLoginMethod, LoginBindings } from '../types/auth'
import {
  alternateMethods,
  contactLabel,
  goToWechat,
  loginDestination,
  methodLabels,
} from '../utils/login-methods'

const route = useRoute()
const router = useRouter()
const bindings = ref<LoginBindings>()
const action = ref<{ method: AlternateLoginMethod; removing: boolean }>()
const password = ref('')
const destination = ref('')
const code = ref('')
const sentDestination = ref('')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const dialogError = ref('')
const notice = ref('')
const codeMethod = computed(() => {
  const method = action.value?.method
  return method === 'sms' || method === 'email' ? method : undefined
})
const dialogTitle = computed(() => {
  if (!action.value) return ''
  return (action.value.removing ? '解除绑定' : '绑定') + methodLabels[action.value.method]
})

async function load(): Promise<void> {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    bindings.value = await loginBindings()
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await load()
  if (route.query.wechat === 'bound') notice.value = '微信已绑定。'
  if (route.query.wechat === 'error' && !error.value) error.value = '微信绑定未完成，请重新扫码。'
  if (route.query.wechat) await router.replace({ query: {} })
})

function resetForm(): void {
  password.value = destination.value = code.value = sentDestination.value = dialogError.value = ''
}

function open(method: AlternateLoginMethod, removing = false): void {
  resetForm()
  error.value = notice.value = ''
  action.value = { method, removing }
}

function close(): void {
  if (!busy.value) {
    action.value = undefined
    resetForm()
  }
}

function status(method: AlternateLoginMethod): string {
  const bound = bindings.value?.[method]
  return typeof bound === 'string' ? bound : bound ? '已绑定' : '未绑定'
}

function submitLabel(): string {
  if (action.value?.removing) return '确认解除绑定'
  if (action.value?.method === 'wechat') return '前往微信扫码'
  return sentDestination.value ? '确认绑定' : '发送验证码'
}

async function submit(): Promise<void> {
  const current = action.value
  if (!current || busy.value) return
  if (sentDestination.value && !/^\d{6}$/.test(code.value.trim())) {
    dialogError.value = '请输入 6 位验证码。'
    return
  }
  if (!sentDestination.value && !password.value) {
    dialogError.value = '请输入当前密码。'
    return
  }
  busy.value = true
  dialogError.value = ''
  try {
    if (current.removing) {
      await removeBinding(current.method, password.value)
    } else if (current.method === 'wechat') {
      const result = await startWechatBinding(password.value)
      password.value = ''
      goToWechat(result.authorizationUrl)
      return
    } else if (!sentDestination.value) {
      const normalized = loginDestination(current.method, destination.value)
      await sendBindingCode(current.method, normalized, password.value)
      sentDestination.value = normalized
      password.value = ''
      return
    } else {
      await bindContact(current.method, sentDestination.value, code.value.trim())
    }
    action.value = undefined
    resetForm()
    await load()
    notice.value = current.removing ? '已解除绑定。' : '已完成绑定。'
  } catch (cause: unknown) {
    dialogError.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="page-heading">
    <div>
      <h1>我的登录方式</h1>
      <p>绑定手机、邮箱或微信后，可用对应方式登录本企业。</p>
    </div>
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
  <p v-if="loading" role="status">正在读取绑定信息…</p>
  <el-button v-else-if="!bindings" @click="load">重新加载</el-button>
  <section v-else class="panel binding-list" aria-label="账户绑定">
    <article v-for="method in alternateMethods" :key="method" class="binding-row">
      <div>
        <h2>{{ methodLabels[method] }}</h2>
        <p>
          {{ status(method) }}<template v-if="!bindings.methods[method]"> · 企业未启用</template>
        </p>
      </div>
      <div class="binding-actions">
        <el-button v-if="bindings.methods[method]" @click="open(method)">
          {{ (bindings[method] ? '更换绑定' : '绑定') + methodLabels[method] }}
        </el-button>
        <el-button v-if="bindings[method]" text @click="open(method, true)">
          解除绑定{{ methodLabels[method] }}
        </el-button>
      </div>
    </article>
  </section>

  <el-dialog
    :model-value="Boolean(action)"
    :title="dialogTitle"
    width="min(430px, calc(100vw - 32px))"
    :close-on-click-modal="false"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    @update:model-value="
      (visible: boolean) => {
        if (!visible) close()
      }
    "
  >
    <el-form v-if="action" label-position="top" @submit.prevent="submit">
      <p v-if="action.removing">解除后，该账户无法通过{{ methodLabels[action.method] }}登录。</p>
      <el-form-item v-if="!sentDestination" label="当前密码" for="binding-password">
        <el-input
          id="binding-password"
          v-model="password"
          type="password"
          autocomplete="current-password"
          show-password
          required
          :disabled="busy"
        />
      </el-form-item>
      <template v-if="codeMethod && !action.removing">
        <el-form-item
          v-if="!sentDestination"
          :label="contactLabel(codeMethod)"
          for="binding-destination"
        >
          <el-input
            id="binding-destination"
            v-model="destination"
            :type="codeMethod === 'sms' ? 'tel' : 'email'"
            :autocomplete="codeMethod === 'sms' ? 'tel' : 'email'"
            :maxlength="codeMethod === 'sms' ? 14 : 254"
            :disabled="busy"
            required
          />
        </el-form-item>
        <template v-else>
          <p role="status">验证码已发送至 {{ sentDestination }}。</p>
          <el-form-item label="验证码" for="binding-code">
            <el-input
              id="binding-code"
              v-model="code"
              inputmode="numeric"
              autocomplete="one-time-code"
              maxlength="6"
              :disabled="busy"
              required
            />
          </el-form-item>
        </template>
      </template>
      <el-alert
        v-if="dialogError"
        class="form-alert"
        type="error"
        :title="dialogError"
        :closable="false"
        show-icon
      />
      <div class="binding-actions">
        <el-button :disabled="busy" @click="close">取消</el-button>
        <el-button
          v-if="sentDestination"
          :disabled="busy"
          @click="sentDestination = code = dialogError = ''"
          >重新发送</el-button
        >
        <el-button
          native-type="submit"
          :type="action.removing ? 'danger' : 'primary'"
          :loading="busy"
          >{{ submitLabel() }}</el-button
        >
      </div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.binding-list {
  max-width: 800px;
}

.binding-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 20px 24px;
}

.binding-row + .binding-row {
  border-top: 1px solid var(--el-border-color-lighter);
}

.binding-row h2 {
  margin: 0;
  font-size: 16px;
}

.binding-row p {
  margin: 5px 0 0;
  color: var(--el-text-color-secondary);
  overflow-wrap: anywhere;
}

.binding-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.binding-actions .el-button {
  margin: 0;
}

@media (max-width: 600px) {
  .binding-row {
    align-items: flex-start;
    flex-direction: column;
    padding-inline: 16px;
  }

  .binding-row .binding-actions {
    justify-content: flex-start;
  }
}
</style>
