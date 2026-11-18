<script setup lang="ts">
import type { StatusTone } from '../types/status'

withDefaults(defineProps<{ label: string; tone?: StatusTone }>(), { tone: 'neutral' })
</script>

<template>
  <span class="status-tag" :data-tone="tone">{{ label }}</span>
</template>

<style scoped>
.status-tag {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding-inline: 8px;
  border-radius: var(--erp-radius-sm);
  color: var(--erp-text-2);
  background: var(--erp-fill-2);
  font-size: 12px;
  line-height: 20px;
  white-space: nowrap;
}

.status-tag[data-tone='info'] {
  color: var(--erp-primary-7);
  background: var(--erp-primary-1);
}

.status-tag[data-tone='success'] {
  color: var(--erp-success-text);
  background: var(--erp-success-bg);
}

.status-tag[data-tone='warning'] {
  color: var(--erp-warning-text);
  background: var(--erp-warning-bg);
}

.status-tag[data-tone='danger'] {
  color: var(--erp-danger-text);
  background: var(--erp-danger-bg);
}
</style>
