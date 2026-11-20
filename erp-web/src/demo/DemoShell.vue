<script setup lang="ts">
import { computed, provide, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Box, House, ShoppingCart, Tickets, DataAnalysis, Menu } from '@element-plus/icons-vue'
import BrandMark from '../components/BrandMark.vue'
import DisplayDensitySelect from '../components/DisplayDensitySelect.vue'
import DemoDocumentDrawer from './DemoDocumentDrawer.vue'
import { demoReports, demoSalesKinds, demoPurchaseKinds } from './scope'
import { recentWorkPages } from '../utils/workbench-preferences'
import type { RecentWorkPage } from '../utils/workbench-preferences'

const route = useRoute()
const menuOpen = ref(false)
const title = computed(() => String(route.meta.title ?? '工作台'))
const recent = ref<RecentWorkPage[]>([])
provide(recentWorkPages, recent)
watch(() => route.path, (path) => {
  if (path === '/') return
  recent.value = [{ path: route.fullPath, title: title.value }, ...recent.value.filter((item) => item.path.split('?')[0] !== path)].slice(0, 5)
})
function focusContent(): void {
  document.getElementById('demo-content')?.focus()
}
const groups = [
  { title: '采购', icon: ShoppingCart, items: demoPurchaseKinds },
  { title: '销售', icon: Tickets, items: demoSalesKinds },
  { title: '库存', icon: Box, items: [{ path: 'inventory-balances', title: '实时库存' }] },
  { title: '业务报表', icon: DataAnalysis, items: demoReports.map((item) => ({ path: 'reports/' + item.type, title: item.title })) },
]
</script>

<template>
  <div class="demo-layout">
    <a class="demo-skip" href="#demo-content" @click.prevent="focusContent">跳到主要内容</a>
    <aside class="demo-sidebar" :class="{ open: menuOpen }">
      <router-link to="/" class="demo-brand" @click="menuOpen = false"><BrandMark compact /><strong>柒衡 ERP <small>只读演示</small></strong></router-link>
      <nav aria-label="演示导航" @click="menuOpen = false">
        <router-link to="/" class="demo-home" exact-active-class="active"><House />工作台</router-link>
        <section v-for="group in groups" :key="group.title">
          <h2><component :is="group.icon" />{{ group.title }}</h2>
          <router-link v-for="item in group.items" :key="item.path" :to="'/' + item.path" active-class="active">{{ item.title }}</router-link>
        </section>
      </nav>
      <a class="demo-back" href="https://qiheng.site/">← 返回产品官网</a>
    </aside>
    <div class="demo-body">
      <header class="demo-topbar">
        <button class="demo-menu" aria-label="展开导航" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen"><Menu /></button>
        <span>{{ title }}</span><div class="demo-topbar-right"><DisplayDensitySelect /><span>柒衡商贸 · 示例公司</span><span class="demo-avatar">访</span></div>
      </header>
      <div class="demo-notice" role="note"><strong>只读演示</strong><span>虚构数据 · 样例截至 2026-11-20 · 可筛选、查看单据与报表</span><a href="https://qiheng.site/quick-start/">部署完整版本 ↗</a></div>
      <main id="demo-content" class="demo-content" tabindex="-1"><router-view :key="route.path" /></main>
      <footer class="demo-footer">柒衡 ERP 2.0 · 演示数据不代表真实经营情况 · <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">鄂ICP备2026068130号-1</a></footer>
      <DemoDocumentDrawer />
    </div>
  </div>
</template>

<style scoped>
.demo-layout { display: flex; min-height: 100vh; background: var(--erp-fill-2, #f5f7fa); }
.demo-sidebar { width: 220px; background: white; border-right: 1px solid #e8edf3; position: fixed; inset: 0 auto 0 0; overflow: auto; display: flex; flex-direction: column; z-index: 30; }
.demo-brand { display: flex; align-items: center; gap: 12px; height: 84px; padding: 20px; color: #1b2b45; text-decoration: none; flex-shrink: 0; }
.demo-brand :deep(svg) { width: 34px; height: 34px; }
.demo-brand strong { font-size: 18px; }.demo-brand small { display: block; color: #7e8ca0; font-size: 11px; font-weight: 400; margin-top: 2px; letter-spacing: 2px; }
nav { padding: 4px 12px 20px; } nav section { margin-top: 15px; } nav h2 { display: flex; gap: 8px; align-items: center; font-size: 11px; font-weight: 500; color: #77859a; padding: 0 14px; margin: 0 0 5px; } nav svg { width: 15px; height: 15px; } nav a { display: flex; align-items: center; gap: 9px; padding: 7px 16px 7px 36px; color: #44536a; text-decoration: none; border-radius: 6px; font-size: 13px; } nav a:hover { background: #f3f6fa; } nav a.active { background: #eaf1ff; color: #225fcd; font-weight: 600; } nav .demo-home { padding-left: 14px; }
.demo-back { margin: auto 20px 20px; padding-top: 15px; border-top: 1px solid #e8edf3; color: #63758e; font-size: 12px; text-decoration: none; }
.demo-body { margin-left: 220px; width: calc(100% - 220px); min-width: 0; display: flex; flex-direction: column; }
.demo-topbar { height: 60px; background: white; display: flex; align-items: center; justify-content: space-between; padding: 0 28px; border-bottom: 1px solid #e8edf3; }
.demo-topbar-right { display: flex; align-items: center; gap: 20px; font-size: 12px; color: #6a7890; }.demo-avatar { display: grid; place-items: center; width: 30px; height: 30px; background: #eaf1ff; color: #225fcd; border-radius: 50%; }
.demo-notice { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 10px 28px; background: #edf4ff; font-size: 12px; color: #536c94; }.demo-notice strong { color: #225fcd; }.demo-notice a { margin-left: auto; text-decoration: none; color: #225fcd; }
.demo-content { padding: 24px 28px; min-width: 0; flex: 1; }.demo-footer { padding: 16px 28px 24px; color: #8996a9; font-size: 11px; }.demo-footer a { color: inherit; text-decoration: none; }
.demo-menu { display: none; }.demo-skip { position: fixed; top: -60px; z-index: 100; padding: 12px; background: white; }.demo-skip:focus { top: 4px; }
@media (max-width: 900px) { .demo-sidebar { width: 190px; }.demo-body { margin-left: 190px; width: calc(100% - 190px); }.demo-content { padding: 18px; }.demo-topbar, .demo-notice { padding-left: 18px; padding-right: 18px; }.demo-topbar-right > span:not(.demo-avatar) { display: none; } }
@media (max-width: 640px) { .demo-sidebar { display: none; top: 60px; width: 220px; box-shadow: 12px 0 30px #23344722; }.demo-sidebar.open { display: flex; }.demo-body { width: 100%; margin-left: 0; }.demo-menu { display: block; border: 0; background: none; width: 24px; height: 24px; padding: 0; }.demo-content { padding: 16px 10px; }.demo-notice { gap: 4px 10px; }.demo-notice a { margin-left: 0; }.demo-topbar-right { gap: 10px; } }
</style>
