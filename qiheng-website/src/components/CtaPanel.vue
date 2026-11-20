<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import SmartLink from '@/components/SmartLink.vue'
import { REPO } from '@/config/site'
import type { LinkItem } from '@/types/content'
import { withBase } from '@/utils/assets'

/** 页面底部的蓝色行动区，右下角有探头的吉祥物 */
withDefaults(defineProps<{ title: string; desc: string; primary: string; secondary: LinkItem; github?: string }>(), { github: undefined })
const mascot = withBase('mascot.svg')
</script>

<template>
  <section class="cta" data-reveal="scale" data-nav="" data-anim>
    <img class="cta-cat" :src="mascot" alt="" aria-hidden="true" width="260" height="260" loading="lazy" />
    <h2>{{ title }}</h2>
    <p>{{ desc }}</p>
    <div class="ctas">
      <SmartLink class="btn btn-primary btn-lg" href="/demo">{{ primary }}<AppIcon name="arrow" class="arr" /></SmartLink>
      <SmartLink class="btn btn-lg" :href="secondary.href">{{ secondary.label }}</SmartLink>
      <a v-if="github" class="btn btn-lg" :href="REPO" rel="noopener">{{ github }}</a>
    </div>
  </section>
</template>
