import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import PortalApp from './pages/portal/PortalApp.vue'
import { portalViews } from './types/portal'
import './styles/portal.css'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    ...portalViews.map((view) => ({
      path: view.path,
      name: view.key,
      component: () => import('./pages/portal/PortalWorkspace.vue'),
    })),
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

createApp(PortalApp).use(router).mount('#portal')
