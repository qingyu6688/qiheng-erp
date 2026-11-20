<script setup lang="ts">
import { computed, provide, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Box, House, ShoppingCart, Tickets, DataAnalysis, Menu, User, Tools, Wallet, Fold, Expand, ArrowDown } from '@element-plus/icons-vue'
import BrandMark from '../components/BrandMark.vue'
import DisplayDensitySelect from '../components/DisplayDensitySelect.vue'
import DemoDocumentDrawer from './DemoDocumentDrawer.vue'
import { demoReports, demoSalesKinds, demoPurchaseKinds, demoCrmPages, demoManufacturingPages, demoFinancePages } from './scope'
import { recentWorkPages } from '../utils/workbench-preferences'
import type { RecentWorkPage } from '../utils/workbench-preferences'

const route = useRoute()
const menuOpen = ref(false)
const sidebarCollapsed = ref(false)
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
  { title: '销售', icon: Tickets, items: demoSalesKinds },
  { title: '采购', icon: ShoppingCart, items: demoPurchaseKinds },
  { title: '客户关系', icon: User, items: demoCrmPages },
  { title: '库存', icon: Box, items: [{ path: 'inventory-balances', title: '实时库存' }, { path: 'warehouse-locations', title: '仓库库位' }] },
  { title: '生产', icon: Tools, items: demoManufacturingPages },
  { title: '财务', icon: Wallet, items: demoFinancePages },
  { title: '业务报表', icon: DataAnalysis, items: demoReports.map((item) => ({ path: 'reports/' + item.type, title: item.title })) },
]
</script>

<template>
  <div class="demo-layout" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
    <a class="demo-skip" href="#demo-content" @click.prevent="focusContent">跳到主要内容</a>
    <aside class="demo-sidebar" :class="{ open: menuOpen }" @keydown.esc="menuOpen = false">
      <router-link to="/" class="demo-brand" @click="menuOpen = false"><BrandMark compact /><strong>柒衡 ERP <small>只读演示</small></strong></router-link>
      <nav aria-label="演示导航" @click="(event) => { if ((event.target as HTMLElement).closest('a')) menuOpen = false }">
        <router-link to="/" class="demo-home" exact-active-class="active"><House /><span>工作台</span></router-link>
        <details v-for="group in groups" :key="group.title" :open="sidebarCollapsed || group.items.some((item) => route.path.startsWith('/' + item.path))" :class="{ 'is-active': group.items.some((item) => route.path.startsWith('/' + item.path)) }">
          <summary @click="(event) => { if (sidebarCollapsed) event.preventDefault() }"><component :is="group.icon" /><span>{{ group.title }}</span><ArrowDown class="demo-chevron" /></summary>
          <div class="demo-module-items">
            <strong class="demo-flyout-title">{{ group.title }}</strong>
            <router-link v-for="item in group.items" :key="item.path" :to="'/' + item.path" active-class="active">{{ item.title }}</router-link>
          </div>
        </details>
      </nav>
      <a class="demo-back" href="https://qiheng.site/">← 返回产品官网</a>
    </aside>
    <div class="demo-body">
      <header class="demo-topbar">
        <button class="demo-menu" aria-label="展开导航" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen"><Menu /></button>
        <button class="demo-collapse" :aria-label="sidebarCollapsed ? '展开侧栏' : '收起侧栏'" :aria-expanded="!sidebarCollapsed" @click="sidebarCollapsed = !sidebarCollapsed"><component :is="sidebarCollapsed ? Expand : Fold" /></button>
        <div class="demo-topbar-right"><DisplayDensitySelect /><span>柒衡商贸 · 示例公司</span><span class="demo-avatar">访</span></div>
      </header>
      <div class="demo-notice" role="note"><strong>只读演示</strong><span>虚构数据 · 样例截至 2026-11-20 · 可筛选、查看单据与报表</span><a href="https://qiheng.site/quick-start/">部署完整版本 ↗</a></div>
      <main id="demo-content" class="demo-content content" tabindex="-1"><router-view :key="route.path" /></main>
      <footer class="demo-footer">柒衡 ERP 2.0 · 演示数据不代表真实经营情况 · <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">鄂ICP备2026068130号-1</a></footer>
      <DemoDocumentDrawer />
    </div>
  </div>
</template>

<style scoped>
.demo-layout { --demo-sidebar-width: 224px; display: flex; min-height: 100vh; background: var(--erp-page); }
.demo-sidebar { width: var(--demo-sidebar-width); background: var(--erp-bg); border-right: 1px solid var(--erp-border-1); position: fixed; inset: 0 auto 0 0; display: flex; flex-direction: column; z-index: 30; }
.demo-brand { display: flex; align-items: center; gap: 10px; min-height: 72px; padding: 16px 20px; color: var(--erp-text-1); text-decoration: none; flex-shrink: 0; }
.demo-brand :deep(svg) { width: 32px; height: 32px; }
.demo-brand strong { font-size: 17px; white-space: nowrap; }
.demo-brand small { display: block; color: var(--erp-text-3); font-size: 11px; font-weight: 400; margin-top: 2px; letter-spacing: 2px; }
nav { flex: 1; overflow-y: auto; padding: 4px 12px 20px; }
nav details { margin-top: 4px; }
nav summary { cursor: pointer; display: flex; gap: 10px; align-items: center; min-height: 42px; padding: 8px 12px; border-radius: var(--erp-radius); color: var(--erp-text-2); font-size: 14px; list-style: none; }
nav summary::-webkit-details-marker { display: none; }
nav svg { width: 18px; height: 18px; flex-shrink: 0; }
nav .demo-chevron { width: 12px; height: 12px; margin-left: auto; transform: rotate(-90deg); }
nav details[open] .demo-chevron { transform: none; }
nav .is-active > summary { color: var(--erp-primary-6); }
nav a { display: flex; align-items: center; gap: 10px; min-height: 34px; padding: 6px 12px; color: var(--erp-text-2); text-decoration: none; border-radius: var(--erp-radius); font-size: 13px; }
nav a:hover, nav summary:hover { background: var(--erp-fill-1); }
nav a.active { background: var(--erp-primary-1); color: var(--erp-primary-7); font-weight: 600; }
nav .demo-home { min-height: 42px; padding-left: 12px; font-size: 14px; }
.demo-module-items { padding: 2px 0 8px 28px; }
.demo-flyout-title { display: none; }
.demo-back { margin: 0 20px 20px; padding-top: 15px; border-top: 1px solid var(--erp-border-1); color: var(--erp-text-3); font-size: 12px; text-decoration: none; }
.demo-body { margin-left: var(--demo-sidebar-width); width: calc(100% - var(--demo-sidebar-width)); min-width: 0; display: flex; flex-direction: column; }
.demo-topbar { min-height: 56px; background: var(--erp-bg); display: flex; align-items: center; justify-content: space-between; padding: 0 24px; border-bottom: 1px solid var(--erp-border-1); }
.demo-topbar-right { display: flex; align-items: center; gap: 20px; font-size: 12px; color: var(--erp-text-3); }
.demo-avatar { display: grid; place-items: center; width: 30px; height: 30px; background: var(--erp-primary-1); color: var(--erp-primary-7); border-radius: var(--erp-radius-pill); }
.demo-notice { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 10px 24px; background: var(--erp-primary-1); font-size: 12px; color: var(--erp-text-2); }
.demo-notice strong, .demo-notice a { color: var(--erp-primary-7); }
.demo-notice a { margin-left: auto; text-decoration: none; }
.demo-content { width: 100%; max-width: none; padding: 24px; min-width: 0; flex: 1; }
.demo-footer { padding: 16px 24px 24px; color: var(--erp-text-3); font-size: 11px; }
.demo-footer a { color: inherit; text-decoration: none; }
.demo-menu, .demo-collapse { display: grid; place-items: center; border: 0; background: transparent; color: var(--erp-text-2); width: 34px; height: 34px; padding: 8px; border-radius: var(--erp-radius); cursor: pointer; }
.demo-collapse:hover { background: var(--erp-fill-1); }
.demo-menu { display: none; }
.demo-skip { position: fixed; top: -60px; z-index: 100; padding: 12px; background: var(--erp-bg); }
.demo-skip:focus { top: 4px; }
@media (min-width: 641px) {
  .sidebar-collapsed { --demo-sidebar-width: 72px; }
  .sidebar-collapsed .demo-brand { justify-content: center; padding: 20px 10px; }
  .sidebar-collapsed .demo-brand strong, .sidebar-collapsed .demo-back, .sidebar-collapsed .demo-chevron { display: none; }
  .sidebar-collapsed nav { padding-inline: 8px; }
  .sidebar-collapsed nav summary, .sidebar-collapsed .demo-home { flex-direction: column; justify-content: center; gap: 2px; min-height: 52px; padding: 6px 0; font-size: 11px; }
  .sidebar-collapsed .demo-module-items { display: none; position: fixed; inset: 76px auto 20px 72px; width: 224px; overflow-y: auto; padding: 12px; border: 1px solid var(--erp-border-1); border-radius: var(--erp-radius-card); background: var(--erp-bg); box-shadow: var(--erp-shadow-3); }
  .sidebar-collapsed details:hover .demo-module-items, .sidebar-collapsed details:focus-within .demo-module-items { display: block; }
  .sidebar-collapsed .demo-flyout-title { display: block; padding: 4px 12px 10px; font-size: 14px; }
}
@media (max-width: 900px) { .demo-content { padding: 18px; } .demo-topbar, .demo-notice { padding-left: 18px; padding-right: 18px; } .demo-topbar-right > span:not(.demo-avatar) { display: none; } }
@media (max-width: 640px) { .demo-sidebar { display: none; top: 56px; width: 224px; box-shadow: var(--erp-shadow-3); } .demo-sidebar.open { display: flex; } .demo-body { width: 100%; margin-left: 0; } .demo-menu { display: grid; } .demo-collapse { display: none; } .demo-content { padding: 16px 10px; } .demo-notice { gap: 4px 10px; } .demo-notice a { margin-left: 0; } .demo-topbar-right { gap: 10px; } }
</style>
