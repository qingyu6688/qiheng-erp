<script setup lang="ts">
import SectionHead from '@/components/SectionHead.vue'
import type { HomeContent } from '@/types/content'

/** 业务闭环：三条流程，光圈按 --i 的顺序依次经过每个步骤，示意单据在流转 */
defineProps<{ flows: HomeContent['flows'] }>()
</script>

<template>
  <section class="block alt" data-nav="features" data-anim>
    <div class="wrap">
      <SectionHead :eyebrow="flows.eyebrow" :title="flows.title" :lead="flows.lead" />
      <div class="flows" data-stagger>
        <div v-for="flow in flows.items" :key="flow.name" class="flow" data-reveal>
          <div class="name">{{ flow.name }}<small>{{ flow.sub }}</small></div>
          <div class="steps">
            <template v-for="(step, i) in flow.steps" :key="step.label">
              <span v-if="i > 0" class="arrow" aria-hidden="true">{{ step.dot ? '·' : '→' }}</span>
              <span class="step" :class="{ key: step.key }" :style="{ '--i': i }">{{ step.label }}</span>
            </template>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
