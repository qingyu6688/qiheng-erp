<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import SectionHead from '@/components/SectionHead.vue'
import SmartLink from '@/components/SmartLink.vue'
import type { HomeContent } from '@/types/content'

/** 2.1 新增：六张能力卡，下方是更新日志和演示两个入口 */
defineProps<{ release: HomeContent['release'] }>()
</script>

<template>
  <section class="block alt release-block" data-nav="features">
    <div class="wrap">
      <SectionHead :eyebrow="release.eyebrow" :title="release.title" :lead="release.lead" />
      <div class="grid g3" data-stagger>
        <div v-for="item in release.items" :key="item.title" class="sec-tile" data-reveal>
          <span class="ico"><AppIcon :name="item.icon" /></span>
          <h3>{{ item.title }}</h3>
          <p>{{ item.desc }}</p>
        </div>
      </div>
      <div class="release-links" data-reveal>
        <SmartLink
          v-for="(link, index) in release.links"
          :key="link.href"
          class="btn"
          :class="{ 'btn-primary': index === 1 }"
          :href="link.href"
          :hreflang="link.hreflang"
        >{{ link.label }}<AppIcon name="arrow" class="arr" /></SmartLink>
      </div>
    </div>
  </section>
</template>

<style scoped>
.release-links {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 12px;
  margin-top: 32px;
}
</style>
