<script setup lang="ts">
defineProps<{ ok: boolean; title: string; detail?: string }>()
</script>

<template>
  <view class="scan-feedback" :class="ok ? 'scan-feedback-ok' : 'scan-feedback-error'"
    :role="ok ? 'status' : 'alert'">
    <text class="scan-feedback-icon" aria-hidden="true">{{ ok ? '✓' : '!' }}</text>
    <view>
      <text class="scan-feedback-title">{{ title }}</text>
      <text v-if="detail" class="scan-feedback-detail">{{ detail }}</text>
    </view>
  </view>
</template>

<style scoped>
.scan-feedback { display: flex; align-items: center; gap: 12px; padding: 12px; border-radius: var(--mobile-radius); }
.scan-feedback-icon { display: flex; flex: 0 0 48px; height: 48px; align-items: center; justify-content: center; border-radius: 50%; background: #fff; font-size: 28px; font-weight: 700; }
.scan-feedback-title, .scan-feedback-detail { display: block; overflow-wrap: anywhere; }
.scan-feedback-title { font-size: 18px; font-weight: 700; }
.scan-feedback-detail { font-size: 16px; }
.scan-feedback-ok { min-height: 128px; flex-direction: column; justify-content: center; text-align: center; color: var(--mobile-success); background: var(--mobile-success-bg); }
.scan-feedback-ok .scan-feedback-icon { flex-basis: 64px; width: 64px; height: 64px; font-size: 36px; }
.scan-feedback-ok .scan-feedback-title { font-size: 22px; }
.scan-feedback-error { color: var(--mobile-danger); background: var(--mobile-danger-bg); }
</style>
