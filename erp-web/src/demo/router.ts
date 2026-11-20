import { createRouter, createWebHashHistory } from 'vue-router'
import type { Router, RouterHistory } from 'vue-router'
import { useSessionStore } from '../stores/session'
import DemoShell from './DemoShell.vue'
import { demoReports, demoSalesKinds, demoPurchaseKinds } from './scope'
import { closeDocumentPreview, openDocumentPreview } from './document-preview'

export function createDemoRouter(history: RouterHistory = createWebHashHistory(import.meta.env.BASE_URL)): Router {
const router = createRouter({
  history,
  routes: [{
    path: '/', component: DemoShell, children: [
      { path: '', name: 'workbench', component: () => import('../pages/WorkbenchPage.vue'), meta: { title: '工作台' } },
      ...demoSalesKinds.flatMap(({ path, title }) => [
        { path, component: () => import('../pages/SalesListPage.vue'), props: { kind: path }, meta: { title } },
        { path: path + '/:id(\\d+)', name: path + '-detail', component: () => import('../pages/SalesDetailPage.vue'), props: { kind: path }, meta: { title, demoDocument: true } },
      ]),
      ...demoPurchaseKinds.flatMap(({ path, title }) => [
        { path, component: () => import('../pages/ProcurementListPage.vue'), props: { kind: path }, meta: { title } },
        { path: path + '/:id(\\d+)', name: path + '-detail', component: () => import('../pages/ProcurementDetailPage.vue'), props: { kind: path }, meta: { title, demoDocument: true } },
      ]),
      { path: 'inventory-balances', component: () => import('../pages/InventoryPage.vue'), meta: { title: '实时库存' } },
      { path: 'reports', redirect: '/reports/sales-summary' },
      ...demoReports.map(({ type, title }) => ({
        path: 'reports/' + type, component: () => import('../pages/ReportPage.vue'), props: { type }, meta: { title },
      })),
      { path: ':pathMatch(.*)*', component: () => import('./UnavailablePage.vue'), meta: { title: '演示范围' } },
    ],
  }],
})

router.beforeEach(async (to, from) => {
  await useSessionStore().restore()
  if (to.meta.demoDocument) {
    openDocumentPreview(to)
    if (!from.matched.length) return { path: '/' + to.path.split('/')[1], query: to.query, replace: true }
    return false
  }
  const query = { ...to.query }
  if (to.path !== '/' && to.matched.length && !to.query.dateFrom && !to.query.dateTo) {
    query.dateFrom = '2026-11-01'
    query.dateTo = '2026-11-30'
  }
  if (to.path.endsWith('-aging') && !query.agingDate) query.agingDate = '2026-11-20'
  if (JSON.stringify(query) !== JSON.stringify(to.query)) return { path: to.path, query, replace: true }
  if (from.matched.length) closeDocumentPreview()
  document.title = String(to.meta.title ?? '工作台') + ' · 柒衡 ERP 只读演示'
})
return router
}

export const router = createDemoRouter()
