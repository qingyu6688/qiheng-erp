import type { RouteRecordRaw } from 'vue-router'

/** 与 config/site.ts 的 SITEMAP_PATHS 保持一致；页面按路由懒加载 */
export const routes: RouteRecordRaw[] = [
  { path: '/', name: 'home', component: () => import('@/pages/HomePage.vue'), meta: { locale: 'zh' } },
  { path: '/en', name: 'home-en', component: () => import('@/pages/HomePage.vue'), meta: { locale: 'en' } },
  { path: '/features/ai', name: 'ai', component: () => import('@/pages/AiPage.vue'), meta: { locale: 'zh', navKey: 'ai' } },
  { path: '/quick-start', name: 'quick-start', component: () => import('@/pages/QuickStartPage.vue'), meta: { locale: 'zh', navKey: 'quick-start' } },
  { path: '/demo', name: 'demo', component: () => import('@/pages/DemoPage.vue'), meta: { locale: 'zh', footer: false } },
  // 预渲染为 404/index.html，构建结束时复制到 dist/404.html 供 GitHub Pages 使用
  { path: '/404', name: 'not-found', component: () => import('@/pages/NotFoundPage.vue'), meta: { locale: 'zh', footer: false } },
  { path: '/:pathMatch(.*)*', component: () => import('@/pages/NotFoundPage.vue'), meta: { locale: 'zh', footer: false } },
]
