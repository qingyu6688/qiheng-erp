<script setup lang="ts">
import SmartLink from '@/components/SmartLink.vue'
import { REPO } from '@/config/site'
import { useSeo } from '@/hooks/useSeo'
import { withBase } from '@/utils/assets'

useSeo({ title: '页面不存在 — 柒衡 ERP', description: '你要找的页面不存在。', path: '/404/', locale: 'zh', noindex: true })
const mascot = withBase('mascot.svg')
</script>

<template>
  <main id="main" class="notfound">
    <div>
      <div class="code" aria-hidden="true">404</div>
      <img :src="mascot" alt="" width="200" height="200" />
      <h1>这个箱子是空的</h1>
      <p>你要找的页面不存在，可能已经搬走了。</p>
      <div class="ctas">
        <SmartLink class="btn btn-primary btn-lg" href="/">回到首页</SmartLink>
        <SmartLink class="btn btn-lg" href="/quick-start">快速开始</SmartLink>
        <a class="btn btn-lg" :href="`${REPO}/issues`" rel="noopener">反馈问题</a>
      </div>
    </div>
  </main>
</template>
