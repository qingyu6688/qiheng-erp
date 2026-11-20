<script setup lang="ts">
import MascotSvg from '@/components/MascotSvg.vue'
import type { HomeContent } from '@/types/content'

/** 关于柒衡：内联吉祥物（探头、眨眼动画）和名字释义 */
defineProps<{ about: HomeContent['about'] }>()
</script>

<template>
  <section id="about" class="block brand-story" data-nav="" data-anim>
    <div class="wrap brand-grid">
      <div class="mascot-stage" data-reveal="scale">
        <span class="mascot-halo" aria-hidden="true"></span>
        <MascotSvg :label="about.mascotLabel" clip-id="face-clip-brand" />
      </div>
      <div data-reveal>
        <div class="eyebrow">{{ about.eyebrow }}</div>
        <h2 class="h2">{{ about.titleLines[0] }}<br />{{ about.titleLines[1] }}</h2>
        <dl class="name-meaning">
          <div v-for="item in about.meanings" :key="item.char">
            <dt lang="zh-CN">{{ item.char }}</dt>
            <dd><template v-if="item.romanized"><b>{{ item.romanized }}</b>, </template>{{ item.text }}</dd>
          </div>
        </dl>
        <p class="brand-note">{{ about.note }}</p>
      </div>
    </div>
  </section>
</template>
