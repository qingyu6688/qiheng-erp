<script setup lang="ts">
import ScreenshotTabs from '@/components/ScreenshotTabs.vue'
import SectionHead from '@/components/SectionHead.vue'
import type { HomeContent } from '@/types/content'

const AUTOPLAY_MS = 6000

defineProps<{ gallery: HomeContent['gallery'] }>()
</script>

<template>
  <section id="gallery" class="block alt gallery" data-nav="features" data-anim>
    <div class="wrap">
      <SectionHead :eyebrow="gallery.eyebrow" :title="gallery.title" :lead="gallery.lead" />
      <ScreenshotTabs :shots="gallery.shots" :label="gallery.tabsLabel" :autoplay-ms="AUTOPLAY_MS" />
    </div>
  </section>
</template>
