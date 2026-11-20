<script setup lang="ts">
import { onMounted } from 'vue'
import { useRoute } from 'vue-router'
import SiteFooter from '@/components/SiteFooter.vue'
import SiteNav from '@/components/nav/SiteNav.vue'
import { useLocale } from '@/hooks/useLocale'
import { anchorOffset, highlightTarget } from '@/router/scroll'

const route = useRoute()
const { chrome } = useLocale()

// 直接打开带锚点的地址时，等布局稳定后再定位一次；浏览器原生跳转常被首屏动画和图片加载打断
onMounted(() => {
  if (location.hash.length <= 1) {
    return
  }
  const target = document.getElementById(decodeURIComponent(location.hash.slice(1)))
  if (!target) {
    return
  }
  let userScrolled = false
  const markScrolled = (): void => {
    userScrolled = true
  }
  window.addEventListener('wheel', markScrolled, { passive: true, once: true })
  window.addEventListener('touchmove', markScrolled, { passive: true, once: true })
  const jump = (): void => {
    if (userScrolled) {
      return
    }
    const top = target.getBoundingClientRect().top + window.scrollY - anchorOffset(target)
    window.scrollTo({ top, behavior: 'instant' })
  }
  highlightTarget(target)
  requestAnimationFrame(jump)
  window.addEventListener('load', jump, { once: true })
})
</script>

<template>
  <a class="skip" href="#main">{{ chrome.skip }}</a>
  <SiteNav />
  <RouterView :key="String(route.name ?? route.path)" />
  <SiteFooter v-if="route.meta.footer !== false" />
</template>
