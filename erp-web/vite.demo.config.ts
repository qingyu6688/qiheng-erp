import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: '/experience/',
  plugins: [vue(), {
    name: 'readonly-demo-entry',
    transformIndexHtml: {
      order: 'pre',
      handler(html): string {
        return html.replace('/src/main.ts', '/src/demo/main.ts')
          .replace('<title>柒衡 ERP</title>', '<title>柒衡 ERP · 只读演示</title><meta name="robots" content="noindex,nofollow" /><meta http-equiv="Content-Security-Policy" content="connect-src \'none\'; object-src \'none\'; form-action \'none\'" />')
      },
    },
  }],
  build: {
    outDir: 'dist-demo',
    emptyOutDir: true,
    rollupOptions: {
      input: fileURLToPath(new URL('./index.html', import.meta.url)),
      output: {
        manualChunks(id): string | undefined {
          if (/node_modules\/(echarts|zrender)\//.test(id)) return 'charts'
          if (id.includes('node_modules/element-plus/')) return 'element'
          return undefined
        },
      },
    },
  },
  server: { host: '127.0.0.1', port: 5176, strictPort: true },
  preview: { host: '127.0.0.1', port: 5176, strictPort: true },
})
