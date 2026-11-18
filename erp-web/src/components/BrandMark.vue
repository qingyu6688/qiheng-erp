<script setup lang="ts">
import { Box } from '@element-plus/icons-vue'

defineProps<{ compact?: boolean }>()
</script>
<template>
  <div class="brand" :role="compact ? 'img' : undefined" :aria-label="compact ? '柒衡 ERP' : undefined">
    <span class="brand-icon"><Box aria-hidden="true" /></span><span v-if="!compact">柒衡 ERP</span>
  </div>
</template>
