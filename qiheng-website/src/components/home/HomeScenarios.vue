<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import SectionHead from '@/components/SectionHead.vue'
import type { HomeContent } from '@/types/content'

/** 适用场景：一大两小的拼贴，大卡片里带应收示意条形图 */
defineProps<{ scenarios: HomeContent['scenarios'] }>()
</script>

<template>
  <section id="scenarios" class="block" data-nav="scenarios">
    <div class="wrap">
      <SectionHead :eyebrow="scenarios.eyebrow" :title="scenarios.title" :lead="scenarios.lead" />
      <div class="bento" data-stagger>
        <article v-for="(tile, i) in scenarios.tiles" :key="tile.title" class="tile" :class="i === 0 ? 'b-lg' : 'b-sm'" data-reveal>
          <span class="ico"><AppIcon :name="tile.icon" /></span>
          <h3>{{ tile.title }}</h3>
          <p>{{ tile.desc }}</p>
          <ul>
            <li v-for="point in tile.points" :key="point">{{ point }}</li>
          </ul>
          <div v-if="i === 0" class="mini">
            <div class="mini-card" :aria-label="scenarios.mini.label">
              <div class="mh"><span>{{ scenarios.mini.title }}</span><span>{{ scenarios.mini.note }}</span></div>
              <div v-for="row in scenarios.mini.rows" :key="row.name" class="mini-row">
                <span>{{ row.name }}</span>
                <span class="bar-track"><i :class="{ warn: row.warn }" :style="{ '--w': row.ratio }"></i></span>
                <span class="amt">{{ row.amount }}</span>
              </div>
            </div>
          </div>
        </article>
      </div>
    </div>
  </section>
</template>
