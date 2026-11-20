import { ViteSSG } from 'vite-ssg'
import App from './App.vue'
import { routes } from './router/routes'
import { scrollBehavior } from './router/scroll'
import './styles/index.css'

export const createApp = ViteSSG(App, { routes, base: import.meta.env.BASE_URL, scrollBehavior })
