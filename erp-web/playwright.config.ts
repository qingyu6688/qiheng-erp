import { defineConfig } from '@playwright/test'
import { env } from 'node:process'

export default defineConfig({
  testDir: './tests/e2e',
  workers: 1,
  globalTimeout: 180_000,
  use: {
    baseURL: 'http://127.0.0.1:15173', headless: true, channel: env.ERP_BROWSER_CHANNEL,
    actionTimeout: 10_000, trace: 'retain-on-failure',
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
      timeout: 60_000,
      reuseExistingServer: false,
    },
  ],
})
