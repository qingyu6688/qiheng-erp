<script setup lang="ts">
import { computed } from 'vue'
import SmartLink from '@/components/SmartLink.vue'
import { useLocale } from '@/hooks/useLocale'
import { withBase } from '@/utils/assets'

const { locale, chrome } = useLocale()
const mascot = withBase('mascot.svg')
const homePath = computed(() => (locale.value === 'en' ? '/en' : '/'))
</script>

<template>
  <footer class="site">
    <div class="wrap">
      <div class="cols">
        <div>
          <SmartLink class="brand" :href="homePath"><img class="logo-mark" :src="mascot" alt="" width="32" height="32" />{{ chrome.brand }}</SmartLink>
          <p class="footer-desc">{{ chrome.footer.desc }}</p>
        </div>
        <div v-for="col in chrome.footer.cols" :key="col.title">
          <h4>{{ col.title }}</h4>
          <ul>
            <li v-for="link in col.links" :key="link.href + link.label">
              <SmartLink :href="link.href" :hreflang="link.hreflang">{{ link.label }}</SmartLink>
            </li>
          </ul>
        </div>
      </div>
      <div class="bottom">
        <span>{{ chrome.footer.copyright }}</span><span>{{ chrome.footer.note }}</span>
      </div>
      <p class="icp-record">
        <SmartLink href="https://beian.miit.gov.cn/" hreflang="zh-CN">鄂ICP备2026068130号-1</SmartLink>
      </p>
    </div>
  </footer>
</template>
