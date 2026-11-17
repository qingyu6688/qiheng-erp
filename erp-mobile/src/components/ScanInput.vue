<script setup lang="ts">
import { getCurrentInstance, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { errorText } from '../api/http'
import { cameraScan, feedback, nativeScan } from '../composables/useScanner'
import { nativeApp } from '../stores/session'

const props = defineProps<{ label: string; modelValue: string; disabled?: boolean; placeholder?: string; pdaFocus?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; scan: [value: string] }>()
const previewId = 'scan-preview-' + getCurrentInstance()?.uid
const focused = ref(false)
const cameraOpen = ref(false)
const error = ref('')
let controller: AbortController | undefined

function focusScanner(): void {
  focused.value = false
  void nextTick(() => { focused.value = true })
}

function scanValue(value: string): void {
  const code = value.trim()
  if (!code) return
  emit('update:modelValue', code)
  emit('scan', code)
}

function input(event: Event): void {
  const value = (event as CustomEvent<{ value?: string }>).detail?.value
  if (typeof value === 'string') emit('update:modelValue', value)
}

async function camera(): Promise<void> {
  error.value = ''
  const onDevice = nativeApp()
  const current = onDevice ? undefined : new AbortController()
  controller = current
  try {
    if (!onDevice) {
      cameraOpen.value = true
      await nextTick()
    }
    const value = current ? await cameraScan(previewId, current.signal) : await nativeScan()
    if (!current?.signal.aborted) scanValue(value)
  } catch (cause: unknown) {
    if (!current?.signal.aborted) {
      error.value = errorText(cause)
      feedback(false)
    }
  } finally {
    if (controller === current) cameraOpen.value = false
    current?.abort()
  }
}

function stop(): void {
  controller?.abort()
  cameraOpen.value = false
}

onUnmounted(stop)
onMounted(() => { if (props.pdaFocus && nativeApp()) focusScanner() })
defineExpose({ focusScanner })
</script>

<template>
  <view class="scan-field">
    <text class="field-label">{{ label }}</text>
    <view class="inline scan-line">
      <input
        class="field"
        :aria-label="label"
        :value="modelValue"
        :disabled="disabled || cameraOpen"
        :focus="focused"
        :placeholder="placeholder ?? '扫码或输入后按回车'"
        confirm-type="go"
        @blur="focused = false"
        @input="input"
        @confirm="scanValue($event.detail.value)"
      >
      <button class="compact secondary" :disabled="disabled || cameraOpen" @click="camera">相机</button>
    </view>
    <view v-if="cameraOpen" class="scan-preview">
      <view :id="previewId" />
      <button class="secondary" @click="stop">关闭相机</button>
    </view>
    <text v-if="error" class="error" role="alert">{{ error }}</text>
  </view>
</template>
