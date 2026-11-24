import { copyFileSync, existsSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'
import type {} from 'vite-ssg'
import { SITE_URL, SITEMAP_PATHS } from './src/config/site'

// GitHub Pages 项目站点挂在 /qiheng-erp/ 下；绑定自有域名时用 SITE_BASE=/ 构建
const base = process.env.SITE_BASE ?? '/qiheng-erp/'
const outDir = process.env.SITE_OUTPUT_DIR ?? 'dist'

export default defineConfig({
  base,
  build: { outDir },
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  ssgOptions: {
    dirStyle: 'nested',
    formatting: 'none',
    script: 'async',
    onFinished() {
      const dist = resolve(fileURLToPath(new URL('.', import.meta.url)), outDir)
      // GitHub Pages 只认根目录的 404.html
      const notFound = resolve(dist, '404/index.html')
      if (existsSync(notFound)) {
        copyFileSync(notFound, resolve(dist, '404.html'))
      }
      const today = new Date().toISOString().slice(0, 10)
      const urls = SITEMAP_PATHS.map((path) => `  <url><loc>${SITE_URL}${path}</loc><lastmod>${today}</lastmod></url>`)
      writeFileSync(
        resolve(dist, 'sitemap.xml'),
        `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls.join('\n')}\n</urlset>\n`,
      )
    },
  },
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.spec.ts'],
  },
})
