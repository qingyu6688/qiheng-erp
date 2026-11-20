import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import App from '../App.vue'
import { installRequestAdapter } from '../api/http'
import { demoAdapter } from '../api/demo'
import { router } from './router'
import '../styles/theme.scss'
import '../styles/app.css'
import '../styles/art-business.css'
import '../styles/workspace.css'

installRequestAdapter(demoAdapter)
createApp(App).provide('erp.readOnlyPreview', true)
  .provide('erp.navigationAllowed', (path: string): boolean => ['/receipts', '/inventory-balances', '/shipments', '/reports/sales-summary'].includes(path))
  .use(createPinia()).use(ElementPlus).use(router).mount('#app')
