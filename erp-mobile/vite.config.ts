import uniPlugin from '@dcloudio/vite-plugin-uni'
import { defineConfig, loadEnv } from 'vite'

const uni =
  typeof uniPlugin === 'function'
    ? uniPlugin
    : (uniPlugin as unknown as { default: typeof uniPlugin }).default

export default defineConfig(({ mode }) => {
  const settings = loadEnv(mode, process.cwd(), 'ERP_')
  return {
    plugins: [uni()],
    server: {
      proxy: {
        '/api': {
          target: settings.ERP_API_ORIGIN || process.env.ERP_API_ORIGIN || 'http://127.0.0.1:8080',
          changeOrigin: true,
        },
      },
    },
  }
})
