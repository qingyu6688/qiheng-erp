<script setup lang="ts">
import { computed, ref } from 'vue'
import CtaPanel from '@/components/CtaPanel.vue'
import FaqSection from '@/components/FaqSection.vue'
import AiBand from '@/components/home/AiBand.vue'
import HomeAbout from '@/components/home/HomeAbout.vue'
import HomeArchitecture from '@/components/home/HomeArchitecture.vue'
import HomeFlows from '@/components/home/HomeFlows.vue'
import HomeGallery from '@/components/home/HomeGallery.vue'
import HomeGetStarted from '@/components/home/HomeGetStarted.vue'
import HomeHero from '@/components/home/HomeHero.vue'
import HomeModules from '@/components/home/HomeModules.vue'
import HomeScenarios from '@/components/home/HomeScenarios.vue'
import HomeSecurity from '@/components/home/HomeSecurity.vue'
import HomeStack from '@/components/home/HomeStack.vue'
import HomeTrust from '@/components/home/HomeTrust.vue'
import { homeEn } from '@/content/home.en'
import { homeZh } from '@/content/home.zh'
import { useLocale } from '@/hooks/useLocale'
import { usePageEffects } from '@/hooks/usePageEffects'
import { useScrollSpy } from '@/hooks/useScrollSpy'
import { useSeo } from '@/hooks/useSeo'

/** 中英文首页共用这个页面，内容按路由上的语言切换 */
const { locale } = useLocale()
const content = computed(() => (locale.value === 'en' ? homeEn : homeZh))

useSeo({
  title: content.value.seo.title,
  description: content.value.seo.description,
  ogDescription: content.value.seo.ogDescription,
  path: locale.value === 'en' ? '/en/' : '/',
  locale: locale.value,
  alternates: { zh: '/', en: '/en/' },
  structuredData: true,
})

const root = ref<HTMLElement | null>(null)
usePageEffects(root)
useScrollSpy(root)
</script>

<template>
  <main id="main" ref="root">
    <HomeHero :hero="content.hero" />
    <HomeStack :stack="content.stack" />
    <HomeScenarios :scenarios="content.scenarios" />
    <HomeFlows :flows="content.flows" />
    <HomeTrust :trust="content.trust" />
    <AiBand :ai="content.ai" />
    <HomeModules :modules="content.modules" />
    <HomeGallery :gallery="content.gallery" />
    <HomeArchitecture :architecture="content.architecture" />
    <HomeSecurity :security="content.security" />
    <HomeAbout :about="content.about" />
    <HomeGetStarted v-if="content.getStarted" :start="content.getStarted" />
    <section class="block faq-block" data-nav="resources">
      <FaqSection :faq="content.faq" />
    </section>
    <CtaPanel :title="content.cta.title" :desc="content.cta.desc" :primary="content.cta.primary" :secondary="content.cta.secondary" :github="content.cta.github" />
  </main>
</template>
