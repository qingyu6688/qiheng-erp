<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import SmartLink from '@/components/SmartLink.vue'
import type { HomeContent } from '@/types/content'

/** 安全与开源：五张能力卡，下方一条 AGPL-3.0 说明 */
defineProps<{ security: HomeContent['security'] }>()
</script>

<template>
  <section class="block alt" data-nav="developers">
    <div class="wrap">
      <div class="sec-grid" data-stagger>
        <div class="sec-intro" data-reveal>
          <div class="eyebrow">{{ security.eyebrow }}</div>
          <h2 class="h2">{{ security.title }}</h2>
          <p class="lead">{{ security.lead }}</p>
        </div>
        <div v-for="tile in security.tiles" :key="tile.title" class="sec-tile" data-reveal>
          <span class="ico"><AppIcon :name="tile.icon" /></span>
          <h3>{{ tile.title }}</h3>
          <p>{{ tile.desc }}</p>
        </div>
      </div>
      <div class="license-bar" data-reveal>
        <div class="lb-head">
          <span class="ico ok"><AppIcon name="check" /></span>
          <div><b>{{ security.license.title }}</b><span>{{ security.license.sub }}</span></div>
        </div>
        <ul>
          <li v-for="point in security.license.points" :key="point">{{ point }}</li>
        </ul>
        <div class="lb-links">
          <SmartLink v-for="link in security.license.links" :key="link.href" :href="link.href" :hreflang="link.hreflang">{{ link.label }}</SmartLink>
        </div>
      </div>
    </div>
  </section>
</template>
