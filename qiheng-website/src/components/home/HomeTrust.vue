<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import SectionHead from '@/components/SectionHead.vue'
import type { HomeContent } from '@/types/content'

/** 账为什么准：四张说明卡和统计条，数字进入视口时从 0 滚动到目标值 */
defineProps<{ trust: HomeContent['trust'] }>()
</script>

<template>
  <section class="block" data-nav="features">
    <div class="wrap">
      <SectionHead :eyebrow="trust.eyebrow" :title="trust.title" :lead="trust.lead" />
      <div class="trust" data-stagger>
        <div v-for="item in trust.items" :key="item.title" class="trust-item" data-reveal>
          <span class="ico"><AppIcon :name="item.icon" /></span>
          <div>
            <h3>{{ item.title }}</h3>
            <p>{{ item.desc }}</p>
          </div>
        </div>
      </div>
      <div class="stats" data-reveal="scale">
        <div v-for="stat in trust.stats" :key="stat.label" class="stat">
          <div class="v" :data-count="stat.count" :data-suffix="stat.suffix">{{ stat.value }}</div>
          <div class="k">{{ stat.label }}</div>
        </div>
      </div>
    </div>
  </section>
</template>
