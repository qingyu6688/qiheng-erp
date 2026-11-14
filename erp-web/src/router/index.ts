import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '../stores/session'
import LoginPage from '../pages/LoginPage.vue'
import StatusPage from '../pages/StatusPage.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginPage },
    { path: '/401', name: 'expired', component: StatusPage, props: { status: 401 } },
    { path: '/403', name: 'forbidden', component: StatusPage, props: { status: 403 } },
    {
      path: '/service-unavailable',
      name: 'unavailable',
      component: StatusPage,
      props: { status: 503 },
    },
    {
      path: '/',
      component: () => import('../components/AppShell.vue'),
      children: [
        { path: '', redirect: '/warehouses' },
        {
          path: 'warehouses',
          name: 'warehouses',
          component: () => import('../pages/WarehousePage.vue'),
        },
        {
          path: 'account/password',
          name: 'password',
          component: () => import('../pages/PasswordPage.vue'),
        },
      ],
    },
    { path: '/:pathMatch(.*)*', name: 'missing', component: StatusPage, props: { status: 404 } },
  ],
})

router.beforeEach(async (to) => {
  const session = useSessionStore()
  if (['expired', 'forbidden', 'missing', 'unavailable'].includes(String(to.name))) return true
  try {
    await session.restore()
  } catch {
    // 登录允许手动重试，其他页面保留服务不可用的独立状态。
    if (to.name !== 'login') return { name: 'unavailable' }
  }
  if (!session.user) return to.name === 'login' ? true : { name: 'login' }
  if (session.user.mustChangePassword && to.name !== 'password') return { name: 'password' }
  if (to.name === 'login') return { name: 'warehouses' }
  if (to.name === 'warehouses' && (!session.company || !session.can('warehouse:view')))
    return { name: 'forbidden' }
  return true
})
