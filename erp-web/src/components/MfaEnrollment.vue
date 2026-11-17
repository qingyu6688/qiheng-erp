<script setup lang="ts">
import { ref, watch } from 'vue'
import QRCode from 'qrcode'
import type { MfaSetup } from '../api/platform-security'

const props = defineProps<{ setup: MfaSetup }>()
const image = ref('')
watch(
  () => props.setup.uri,
  (uri, _, onCleanup) => {
    let active = true
    image.value = ''
    onCleanup(() => {
      active = false
    })
    void QRCode.toDataURL(uri, { width: 168, margin: 2 })
      .then((value: string) => {
        if (active) image.value = value
      })
      .catch(() => {
        if (active) image.value = ''
      })
  },
  { immediate: true },
)
</script>

<template>
  <div class="mfa-instructions">
    <p>用验证器扫描二维码，或手动输入密钥，然后填写验证器显示的验证码。</p>
    <img v-if="image" :src="image" width="168" height="168" alt="验证器绑定二维码" />
    <p>
      <code>{{ setup.secret }}</code>
    </p>
  </div>
</template>

<style scoped>
.mfa-instructions {
  margin-block: 16px;
}
.mfa-instructions img {
  display: block;
}
.mfa-instructions code {
  overflow-wrap: anywhere;
  user-select: all;
}
</style>
