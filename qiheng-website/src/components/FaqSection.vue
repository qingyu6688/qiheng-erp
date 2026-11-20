<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import SmartLink from '@/components/SmartLink.vue'
import type { FaqContent } from '@/types/content'

/** 常见问题：左侧标题与反馈入口，右侧可展开的问答；第一条默认展开 */
defineProps<{ faq: FaqContent }>()
</script>

<template>
  <div class="wrap faq-wrap">
    <div class="faq-side" data-reveal>
      <div class="eyebrow">{{ faq.eyebrow }}</div>
      <h2 class="h2">{{ faq.title }}</h2>
      <p v-if="faq.desc"><slot name="desc">{{ faq.desc }}</slot></p>
      <SmartLink v-if="faq.action" class="btn" :href="faq.action.href">{{ faq.action.label }}<AppIcon name="arrow" class="arr" /></SmartLink>
    </div>
    <div class="faq" data-stagger="0.05">
      <details v-for="(item, i) in faq.items" :key="item.q" :open="i === 0" data-reveal>
        <summary>{{ item.q }}</summary>
        <p>{{ item.a }}</p>
      </details>
    </div>
  </div>
</template>
