<script setup lang="ts">
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { computed, onMounted, ref, watchEffect } from 'vue'
import { requireServerVersion } from './api/http'
import { provideDisplayDensity } from './hooks/useDisplayDensity'
import { useSessionStore } from './stores/session'

const checking = ref(true)
const error = ref('')
const session = useSessionStore()
const density = provideDisplayDensity(() => session.user?.id)
const sizes = { compact: 'small', default: undefined, comfortable: 'large' } as const
const componentSize = computed(() => sizes[density.value])
watchEffect(() => {
  document.documentElement.dataset.density = density.value
})

async function checkVersion(): Promise<void> {
  checking.value = true
  error.value = ''
  try {
    await requireServerVersion()
  } catch (cause: unknown) {
    error.value = cause instanceof Error ? cause.message : '无法检查服务端版本，请稍后重试。'
  } finally {
    checking.value = false
  }
}

onMounted(checkVersion)
</script>

<template>
  <el-config-provider :size="componentSize" :locale="zhCn">
    <main v-if="checking || error" class="compatibility">
      <p v-if="checking" role="status">正在检查服务端版本…</p>
      <template v-else>
        <el-alert :title="error" type="error" :closable="false" show-icon />
        <el-button @click="checkVersion">重试</el-button>
      </template>
    </main>
    <router-view v-else />
  </el-config-provider>
</template>

<style scoped>
.compatibility {
  max-width: 32rem;
  margin: 12vh auto;
  padding: 1rem;
}
.compatibility button {
  margin-top: 1rem;
}
</style>
