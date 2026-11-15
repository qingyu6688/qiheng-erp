import { env } from 'node:process'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ command }) => ({
  define: {
    'import.meta.env.VITE_DEV_TENANT': JSON.stringify(
      command === 'serve' ? env.ERP_DEV_TENANT_CODE || 'DEMO' : '',
    ),
    'import.meta.env.VITE_DEV_USER': JSON.stringify(command === 'serve' ? 'admin' : ''),
    'import.meta.env.VITE_DEV_PASSWORD': JSON.stringify(
      command === 'serve' ? env.ERP_DEV_ADMIN_PASSWORD || '' : '',
    ),
  },
  plugins: [vue()],
  build: {
    emptyOutDir: true,
    rollupOptions: {
      output: {
        onlyExplicitManualChunks: true,
        manualChunks(id: string): string | undefined {
          if (id.includes('node_modules/@element-plus')) return 'element-icons'
          if (id.includes('node_modules/element-plus')) return 'element'
          if (/node_modules\/(?:@vue|vue|vue-router|pinia)\//.test(id)) return 'vue'
          if (
            /node_modules\/(?:lodash|lodash-es|@ctrl\/tinycolor|@floating-ui|@popperjs|@sxzz)\//.test(
              id,
            )
          )
            return 'element-utils'
          return undefined
        },
      },
    },
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: { '/api': env.ERP_API_ORIGIN || 'http://127.0.0.1:8080' },
  },
  test: { environment: 'jsdom', clearMocks: true, include: ['tests/**/*.test.ts'] },
}))
