<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import SmartLink from '@/components/SmartLink.vue'
import { REPO } from '@/config/site'
import { demoConfig, demoTarget } from '@/config/demo'
import { prefersReducedMotion } from '@/hooks/motion'
import { useSeo } from '@/hooks/useSeo'
import { withBase } from '@/utils/assets'

const COUNTDOWN_SECONDS = 3

useSeo({
  title: '在线演示 — 柒衡 ERP',
  description: '跳转到柒衡 ERP 演示系统。',
  path: '/demo/',
  locale: 'zh',
  noindex: true,
})

const mascot = withBase('mascot.svg')
const target = demoConfig.url ? demoTarget(demoConfig.url) : null
const seconds = ref(COUNTDOWN_SECONDS)
// 减少动态效果时不自动跳转，只保留“立即进入”；服务端渲染时先按不自动跳转输出
const autoRedirect = ref(false)
let timer: number | undefined

onMounted(() => {
  if (!target || prefersReducedMotion()) {
    return
  }
  autoRedirect.value = true
  timer = window.setInterval(() => {
    seconds.value -= 1
    if (seconds.value <= 0) {
      window.clearInterval(timer)
      window.location.assign(target)
    }
  }, 1000)
})
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<template>
  <main id="main" class="demo-page">
    <div v-if="target" class="demo-card">
      <img class="demo-cat" :src="mascot" alt="" width="132" height="132" />
      <h1>即将进入演示系统</h1>
      <p>浏览客户、购销存、生产计划、财务账簿和 AI 知识样例。演示使用虚构数据，仅供查看，无需登录。</p>
      <dl>
        <dt>演示地址</dt><dd>{{ target }}</dd>
        <template v-if="demoConfig.tenant"><dt>企业编码</dt><dd>{{ demoConfig.tenant }}</dd></template>
        <template v-if="demoConfig.note"><dt>说明</dt><dd>{{ demoConfig.note }}</dd></template>
      </dl>
      <template v-if="autoRedirect">
        <div class="progress" aria-hidden="true"><i></i></div>
        <p class="countdown" aria-live="polite">{{ seconds }} 秒后自动跳转</p>
      </template>
      <div class="ctas">
        <a class="btn btn-primary btn-lg" :href="target" rel="noopener">立即进入<AppIcon name="arrow" class="arr" /></a>
        <SmartLink class="btn" href="/">返回官网</SmartLink>
      </div>
    </div>
    <div v-else class="demo-card">
      <img class="demo-cat" :src="mascot" alt="" width="132" height="132" />
      <h1>在线演示正在筹备</h1>
      <p>演示环境还没有开放。你可以先在本机运行，或看看界面截图。</p>
      <div class="ctas">
        <SmartLink class="btn btn-primary" href="/quick-start">在本机运行</SmartLink>
        <SmartLink class="btn" href="/#gallery">查看界面截图</SmartLink>
        <a class="btn" :href="REPO" rel="noopener">在 GitHub 关注进展</a>
      </div>
    </div>
  </main>
</template>
