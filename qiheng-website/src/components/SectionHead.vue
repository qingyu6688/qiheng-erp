<script setup lang="ts">
withDefaults(defineProps<{ eyebrow: string; title: string; lead?: string; center?: boolean }>(), { lead: undefined, center: true })
</script>

<template>
  <div class="head" :class="{ center }" data-reveal>
    <div class="eyebrow">{{ eyebrow }}</div>
    <h2 class="h2">{{ title }}</h2>
    <p v-if="lead" class="lead">{{ lead }}</p>
  </div>
</template>
