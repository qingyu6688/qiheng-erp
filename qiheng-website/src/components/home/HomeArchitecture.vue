<script setup lang="ts">
import SmartLink from '@/components/SmartLink.vue'
import type { HomeContent } from '@/types/content'

/** 部署与架构：分层技术栈和按《部署说明》整理的资源预算 */
defineProps<{ architecture: HomeContent['architecture'] }>()
</script>

<template>
  <section id="architecture" class="block" data-nav="developers">
    <div class="wrap arch">
      <div data-reveal>
        <div class="eyebrow">{{ architecture.eyebrow }}</div>
        <h2 class="h2">{{ architecture.title }}</h2>
        <p class="lead">{{ architecture.lead }}</p>
        <div class="layers" data-stagger>
          <div v-for="layer in architecture.layers" :key="layer.label" class="layer" data-reveal>
            <b>{{ layer.label }}</b>
            <div class="chips">
              <span v-for="chip in layer.chips" :key="chip.text" class="chip" :class="{ p: chip.primary }">{{ chip.text }}</span>
            </div>
          </div>
        </div>
      </div>
      <div class="spec-card" data-reveal="scale">
        <table class="spec">
          <thead>
            <tr><th>{{ architecture.table.head[0] }}</th><th>{{ architecture.table.head[1] }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in architecture.table.rows" :key="row[0]"><td>{{ row[0] }}</td><td>{{ row[1] }}</td></tr>
          </tbody>
        </table>
        <p class="spec-foot">
          {{ architecture.foot.before }}<SmartLink :href="architecture.foot.link.href" :hreflang="architecture.foot.link.hreflang">{{ architecture.foot.link.label }}</SmartLink>{{ architecture.foot.after }}
        </p>
      </div>
    </div>
  </section>
</template>
