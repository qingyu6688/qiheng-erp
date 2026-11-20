<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import SectionHead from '@/components/SectionHead.vue'
import SmartLink from '@/components/SmartLink.vue'
import WikiReviewMock from '@/components/WikiReviewMock.vue'
import { useLocale } from '@/hooks/useLocale'
import type { HomeContent } from '@/types/content'
import { splitPlaceholder } from '@/utils/text'

/** 首页 AI 深色区块：RAG + LLM Wiki 流程条、Wiki 审核示意和能力卡 */
defineProps<{ ai: HomeContent['ai'] }>()
const { locale } = useLocale()
</script>

<template>
  <section id="ai" class="ai-band" data-nav="ai" data-anim>
    <div class="wrap">
      <SectionHead :eyebrow="ai.eyebrow" :title="ai.title" :lead="ai.lead" />

      <div class="pipeline" :aria-label="ai.pipelineLabel" data-stagger="0.1">
        <span class="pipe-track" aria-hidden="true"></span>
        <div v-for="step in ai.pipeline" :key="step.n" class="pipe" :class="{ hi: step.hi }" data-reveal>
          <span class="n">{{ step.n }}</span><b>{{ step.title }}</b><span>{{ step.desc }}</span>
        </div>
      </div>

      <div class="ai-split">
        <div data-reveal>
          <h3>{{ ai.split.title }}</h3>
          <p>{{ ai.split.desc }}</p>
          <ul class="checks">
            <li v-for="check in ai.split.checks" :key="check.b"><AppIcon name="check" /><span><b>{{ check.b }}</b>{{ locale === 'en' ? ': ' : '：' }}{{ check.text }}</span></li>
          </ul>
        </div>
        <WikiReviewMock :wiki="ai.wiki" />
      </div>

      <div class="ai-cards" data-stagger>
        <div v-for="card in ai.cards" :key="card.title" class="ai-card spot" :class="{ wide: card.wide }" data-reveal>
          <span class="ico"><AppIcon :name="card.icon" /></span>
          <h3>{{ card.title }}</h3>
          <p>{{ card.desc }}</p>
          <div v-if="card.ask" class="ask" aria-hidden="true">
            <span class="q">{{ card.ask.q }}</span>
            <span class="a">
              <template v-if="card.ask.amount">{{ splitPlaceholder(card.ask.a, 'amount')[0] }}<b>{{ card.ask.amount }}</b>{{ splitPlaceholder(card.ask.a, 'amount')[1] }}</template>
              <template v-else>{{ card.ask.a }}</template>
            </span>
          </div>
        </div>
      </div>
      <div class="ai-cards ai-eval-row" data-reveal>
        <div class="ai-card spot ai-eval">
          <span class="ico"><AppIcon name="check" /></span>
          <div class="ai-eval-text">
            <h3>{{ ai.evaluation.title }}</h3>
            <p>{{ ai.evaluation.desc }}</p>
          </div>
          <div class="loop" aria-hidden="true">
            <template v-for="(step, i) in ai.evaluation.loop" :key="step"><template v-if="i > 0">→</template><span>{{ step }}</span></template>
          </div>
        </div>
      </div>
      <div class="center ai-more" data-reveal>
        <SmartLink class="btn btn-ghost-dark btn-lg" :href="ai.more.href" :hreflang="ai.more.hreflang">{{ ai.more.label }}<AppIcon name="arrow" class="arr" /></SmartLink>
      </div>
    </div>
  </section>
</template>
