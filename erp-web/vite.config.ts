import { env } from 'node:process'
import { relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { webTestStorage } from './scripts/test-storage'

const testStorage = webTestStorage()
const testMode = Boolean(env.VITEST) || env.NODE_ENV === 'test'
const redirectedTests = Boolean(testStorage.root)
const testOutput = (name: string, fallback: string): string =>
  testStorage.output('vitest/' + name, fallback)

export default defineConfig(({ command }) => ({
  cacheDir:
    testMode || redirectedTests
      ? testOutput('cache', testStorage.webRoot + '/node_modules/.vite')
      : undefined,
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
  optimizeDeps: {
    include: [
      'element-plus/es/components/cascader/index.mjs',
      'element-plus/es/components/drawer/index.mjs',
    ],
  },
  build: {
    emptyOutDir: true,
    rollupOptions: {
      input: {
        app: fileURLToPath(new URL('./index.html', import.meta.url)),
        portal: fileURLToPath(new URL('./portal.html', import.meta.url)),
      },
      output: {
        onlyExplicitManualChunks: true,
        manualChunks(id: string): string | undefined {
          if (id.includes('node_modules/echarts/')) return 'echarts'
          if (id.includes('node_modules/zrender/')) return 'chart-renderer'
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
    proxy: {
      '/api': env.ERP_API_ORIGIN || 'http://127.0.0.1:8080',
      '/open/v1': env.ERP_API_ORIGIN || 'http://127.0.0.1:8080',
      '/ai/v1': env.ERP_AI_ORIGIN || 'http://127.0.0.1:18081',
      '/ws': { target: env.ERP_API_ORIGIN || 'http://127.0.0.1:8080', ws: true },
    },
  },
  preview: {
    proxy: {
      '/api': env.ERP_API_ORIGIN || 'http://127.0.0.1:8080',
      '/open/v1': env.ERP_API_ORIGIN || 'http://127.0.0.1:8080',
      '/ai/v1': env.ERP_AI_ORIGIN || 'http://127.0.0.1:18081',
      '/ws': { target: env.ERP_API_ORIGIN || 'http://127.0.0.1:8080', ws: true },
    },
  },
  test: {
    environment: 'jsdom',
    clearMocks: true,
    include: ['tests/**/*.test.ts'],
    ...(testMode || redirectedTests
      ? {
          coverage: { reportsDirectory: testOutput('coverage', testStorage.webRoot + '/coverage') },
          ...(redirectedTests
            ? {
                outputFile: {
                  json: testOutput('results.json', ''),
                  junit: testOutput('junit.xml', ''),
                  html: testOutput('html/index.html', ''),
                },
                resolveSnapshotPath: (testPath: string, extension: string): string =>
                  testOutput(
                    'snapshots/' + relative(testStorage.webRoot, testPath) + extension,
                    '',
                  ),
              }
            : {}),
        }
      : {}),
  },
}))
