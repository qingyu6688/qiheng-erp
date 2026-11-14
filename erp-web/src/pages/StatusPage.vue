<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { messageOf } from '../api/http'
import BrandMark from '../components/BrandMark.vue'

const props = defineProps<{ status: 401 | 403 | 404 | 503 }>()
const messages = {
  401: ['登录已过期', '请重新登录后继续操作。'],
  403: ['暂无访问权限', '请联系管理员分配公司和仓库查看权限。'],
  404: ['页面不存在', '请检查地址，或返回当前工作台。'],
  503: ['暂时无法连接服务', '请检查网络与后端服务后重试。'],
}
const info = computed(() => messages[props.status])
const session = useSessionStore()
const router = useRouter()
const error = ref('')
async function leave(): Promise<void> {
  try {
    if (props.status === 403) await session.signOut()
    await router.replace(props.status === 401 || props.status === 403 ? '/login' : '/')
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  }
}
</script>
<template>
  <main class="auth-page">
    <section class="login-panel">
      <BrandMark />
      <div class="status-code">{{ status }}</div>
      <h1>{{ info[0] }}</h1>
      <p>{{ info[1] }}</p>
      <el-alert v-if="error" type="error" :title="error" :closable="false" />
      <el-button type="primary" @click="leave">{{
        status === 401 || status === 403 ? '返回登录' : '返回工作台'
      }}</el-button>
    </section>
  </main>
</template>
