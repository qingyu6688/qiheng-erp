<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import SectionHead from '@/components/SectionHead.vue'
import SmartLink from '@/components/SmartLink.vue'
import type { HomeContent } from '@/types/content'

/** 功能模块：暂无详情页的模块卡不做成链接，带 id 供导航大菜单深链；AI 卡链到 AI 介绍 */
defineProps<{ modules: HomeContent['modules'] }>()
</script>

<template>
  <section id="features" class="block" data-nav="features">
    <div class="wrap">
      <SectionHead :eyebrow="modules.eyebrow" :title="modules.title" :lead="modules.lead" />
      <div class="grid g4" data-stagger="0.04">
        <div v-for="item in modules.items" :id="item.id" :key="item.id" class="card module" data-reveal>
          <span class="ico"><AppIcon :name="item.icon" /></span>
          <h3>{{ item.title }}</h3>
          <p>{{ item.desc }}</p>
        </div>
        <SmartLink class="card module ai-mod" :href="modules.ai.href" data-reveal>
          <span class="ico"><AppIcon :name="modules.ai.icon" /></span>
          <AppIcon name="ext" class="go" />
          <h3>{{ modules.ai.title }}</h3>
          <p>{{ modules.ai.desc }}</p>
        </SmartLink>
      </div>
    </div>
  </section>
</template>
