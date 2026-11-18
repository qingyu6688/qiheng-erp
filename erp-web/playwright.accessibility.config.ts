import { defineConfig } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { prepareTestRuntime, webTestStorage } from './scripts/test-storage'

const storage = webTestStorage()
const outputDir = storage.output('a11y/runner', storage.webRoot + '/.local/ui2-a11y-runner')
Object.assign(process.env, prepareTestRuntime(storage))

const port = Number(process.env.ERP_A11Y_PORT ?? 15179)
if (!Number.isInteger(port) || port < 1024 || port > 65535) {
  throw new Error('ERP_A11Y_PORT 必须是 1024 至 65535 之间的独立测试端口')
}

export default defineConfig({
  testDir: fileURLToPath(new URL('./tests/accessibility', import.meta.url)),
  testMatch: '*.audit.ts',
  outputDir,
  workers: 1,
  globalTimeout: 900_000,
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    channel: 'msedge',
    headless: true,
    trace: 'retain-on-failure',
  },
  webServer: {
    command: `npm run dev -- --host 127.0.0.1 --port ${port} --strictPort`,
    url: `http://127.0.0.1:${port}`,
    timeout: 60000,
    reuseExistingServer: false,
  },
})
