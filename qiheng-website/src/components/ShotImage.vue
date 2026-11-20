<script setup lang="ts">
import { computed } from 'vue'
import { shotSrc, shotSrcset, type ShotKey } from '@/utils/assets'

/** 界面截图：WebP 1 倍、2 倍两档，按屏幕密度选图；首屏截图优先加载，其余懒加载 */
const props = withDefaults(defineProps<{ shot: ShotKey; alt: string; eager?: boolean }>(), { eager: false })
const isPhone = computed(() => props.shot === 'pda')
</script>

<template>
  <img
    :src="shotSrc(shot)"
    :srcset="shotSrcset(shot)"
    :sizes="isPhone ? '240px' : '(max-width: 1120px) 100vw, 1060px'"
    :width="isPhone ? 344 : 1440"
    :height="isPhone ? 704 : 820"
    :alt="alt"
    :loading="eager ? 'eager' : 'lazy'"
    :fetchpriority="eager ? 'high' : undefined"
  />
</template>
