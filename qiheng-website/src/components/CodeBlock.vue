<script setup lang="ts">
import { computed, ref } from 'vue'
import { useLocale } from '@/hooks/useLocale'

const COPY_RESET_MS = 1600

/** 带“复制”按钮的代码块，以 # 开头的行显示为注释色 */
const props = defineProps<{ code: string }>()
const { chrome } = useLocale()
const lines = computed(() => props.code.split('\n').map((text) => ({ text, comment: text.trimStart().startsWith('#') })))
const status = ref<'idle' | 'copied' | 'failed'>('idle')
const label = computed(() => (status.value === 'copied' ? chrome.value.copied : status.value === 'failed' ? chrome.value.copyFail : chrome.value.copy))

async function copy(): Promise<void> {
  try {
    await navigator.clipboard.writeText(props.code)
    status.value = 'copied'
  } catch {
    // 非 https 或浏览器拒绝剪贴板权限时，提示用户手动选中复制
    status.value = 'failed'
  }
  window.setTimeout(() => {
    status.value = 'idle'
  }, COPY_RESET_MS)
}
</script>

<template>
  <pre class="code"><button class="copy" type="button" @click="copy">{{ label }}</button><span v-for="(line, i) in lines" :key="i" :class="{ c: line.comment }">{{ line.text }}{{ i < lines.length - 1 ? '\n' : '' }}</span></pre>
</template>
