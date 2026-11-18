import { defineConfig } from '@playwright/test'
import { env } from 'node:process'
import { prepareTestRuntime, webTestStorage } from './scripts/test-storage'

const storage = webTestStorage()
const outputDir = storage.output('playwright/results', storage.webRoot + '/test-results')
Object.assign(env, prepareTestRuntime(storage))

export default defineConfig({
  testDir: './tests/e2e',
  outputDir,
  snapshotPathTemplate: storage.root
    ? storage.output('playwright/snapshots/{testFilePath}/{arg}{ext}', '')
    : undefined,
  workers: 1,
  globalTimeout: 600_000,
  use: {
    baseURL: 'http://127.0.0.1:15173',
    headless: true,
    channel: env.ERP_BROWSER_CHANNEL,
    locale: 'zh-CN',
    actionTimeout: 10_000,
    trace: 'retain-on-failure',
  },
  webServer: [
    {
      command: 'pwsh -NoProfile -File ../scripts/project.ps1 e2e-api',
      url: 'http://127.0.0.1:18080/actuator/health',
      timeout: 120_000,
      reuseExistingServer: false,
    },
    {
      command: 'pwsh -NoProfile -File ../scripts/project.ps1 e2e-web',
      url: 'http://127.0.0.1:15173',
      timeout: 120_000,
      reuseExistingServer: false,
    },
  ],
})
