<script setup lang="ts">
import type { HomeContent } from '@/types/content'

/** 技术栈跑马灯：内容复制一份首尾相接，第二份对读屏隐藏 */
defineProps<{ stack: HomeContent['stack'] }>()
</script>

<template>
  <section class="stack" :aria-label="stack.ariaLabel" data-nav="" data-anim>
    <p class="label">{{ stack.label }}</p>
    <div class="marquee">
      <div class="marquee-track">
        <span v-for="item in stack.items" :key="item" class="tech"><i></i>{{ item }}</span>
        <span v-for="item in stack.items" :key="`copy-${item}`" class="tech" aria-hidden="true"><i></i>{{ item }}</span>
      </div>
    </div>
  </section>
</template>
