<script setup lang="ts">
import { ICONS, type IconName } from './icons'

defineProps<{ name: IconName }>()
</script>

<template>
  <svg class="i" viewBox="0 0 24 24" aria-hidden="true" v-html="ICONS[name]"></svg>
</template>
