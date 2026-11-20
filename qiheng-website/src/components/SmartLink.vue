<script setup lang="ts">
import { computed } from 'vue'

/** 站内地址用 RouterLink（保留单页跳转和锚点滚动），站外地址用普通链接并加 rel="noopener" */
const props = defineProps<{ href: string; hreflang?: string }>()
const external = computed(() => /^https?:\/\//.test(props.href))
</script>

<template>
  <a v-if="external" :href="href" rel="noopener" :hreflang="hreflang"><slot /></a>
  <RouterLink v-else :to="href" :hreflang="hreflang"><slot /></RouterLink>
</template>
