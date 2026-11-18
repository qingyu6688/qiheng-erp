<script setup lang="ts">
withDefaults(defineProps<{ compact?: boolean; label?: string }>(), { label: '柒衡 ERP' })

const iconUrl = `${import.meta.env.BASE_URL}qiheng-icon.svg`
</script>

<template>
  <div class="brand" :role="compact ? 'img' : undefined" :aria-label="compact ? label : undefined">
    <img class="brand-icon" :src="iconUrl" alt="" width="36" height="36" />
    <span v-if="!compact">{{ label }}</span>
  </div>
</template>

<style scoped>
.brand-icon {
  display: block;
  width: var(--brand-icon-size, 36px);
  height: var(--brand-icon-size, 36px);
  flex: none;
}
</style>
