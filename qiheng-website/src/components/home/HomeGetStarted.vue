<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import CodeBlock from '@/components/CodeBlock.vue'
import InlineText from '@/components/InlineText.vue'
import SectionHead from '@/components/SectionHead.vue'
import SmartLink from '@/components/SmartLink.vue'
import type { HomeContent } from '@/types/content'

/** 英文首页的 Get started：英文暂无独立的快速开始页，安装和源码运行放在首页内 */
defineProps<{ start: NonNullable<HomeContent['getStarted']> }>()
</script>

<template>
  <section id="get-started" class="block" data-nav="quick-start">
    <div class="wrap">
      <SectionHead :eyebrow="start.eyebrow" :title="start.title" :lead="start.lead" />
      <div class="gs-grid" data-stagger>
        <div v-for="card in start.cards" :key="card.title" class="gs-card" data-reveal>
          <span class="ico"><AppIcon :name="card.icon" /></span>
          <h3>{{ card.title }}</h3>
          <p><InlineText :text="card.desc" /></p>
          <CodeBlock :code="card.code" />
          <p v-if="card.note" class="gs-note"><InlineText :text="card.note" /></p>
          <div class="gs-links">
            <SmartLink
              v-for="link in card.links"
              :key="link.href"
              class="btn"
              :class="{ 'btn-primary': link.primary }"
              :href="link.href"
              :hreflang="link.hreflang"
            >
              {{ link.label }}<AppIcon v-if="link.primary" name="arrow" class="arr" />
            </SmartLink>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
