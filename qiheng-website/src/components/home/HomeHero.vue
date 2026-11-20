<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import BrowserFrame from '@/components/BrowserFrame.vue'
import ShotImage from '@/components/ShotImage.vue'
import SmartLink from '@/components/SmartLink.vue'
import { RELEASE_URL } from '@/config/site'
import type { HomeContent } from '@/types/content'

/** 首屏：内容逐行出现（data-in 的 CSS 动画），截图旁的小卡片缓慢浮动 */
defineProps<{ hero: HomeContent['hero'] }>()
const FLOAT_CLASSES = ['f1', 'f2', 'f3']
</script>

<template>
  <section class="hero" data-nav="" data-anim>
    <span class="glow g1" aria-hidden="true"></span>
    <span class="glow g2" aria-hidden="true"></span>
    <div class="wrap">
      <a class="announce" :href="RELEASE_URL" rel="noopener" data-in style="--d: 0.05s">
        <span class="tag">{{ hero.announce.tag }}</span>{{ hero.announce.text }}<AppIcon name="chevron" class="announce-arrow" />
      </a>
      <h1>
        <span class="line" data-in style="--d: 0.15s">{{ hero.titleLines[0] }}</span>
        <em class="line" data-in style="--d: 0.3s">{{ hero.titleLines[1] }}</em>
      </h1>
      <p class="sub" data-in style="--d: 0.45s">{{ hero.sub }}</p>
      <div class="ctas" data-in style="--d: 0.6s">
        <SmartLink class="btn btn-primary btn-lg" href="/demo">{{ hero.primary }}<AppIcon name="arrow" class="arr" /></SmartLink>
        <SmartLink class="btn btn-lg" :href="hero.secondary.href">{{ hero.secondary.label }}</SmartLink>
      </div>
      <div class="meta" data-in style="--d: 0.7s">
        <span v-for="item in hero.meta" :key="item"><AppIcon name="check" />{{ item }}</span>
      </div>

      <div class="shot-wrap" data-in style="--d: 0.8s">
        <div v-for="(card, i) in hero.floats" :key="card.title" class="float-card" :class="FLOAT_CLASSES[i]" aria-hidden="true">
          <span class="ico" :class="{ ok: card.ok }"><AppIcon :name="card.icon" /></span>
          <span><b>{{ card.title }}</b>{{ card.text }}</span>
        </div>
        <BrowserFrame :url="hero.shot.url" :label="hero.shot.label">
          <ShotImage shot="workbench" :alt="hero.shot.alt" eager />
        </BrowserFrame>
        <div class="phone-shot"><ShotImage shot="pda" :alt="hero.phoneAlt" /></div>
      </div>
    </div>
  </section>
</template>
