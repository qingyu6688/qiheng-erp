import { defineConfig } from 'vitest/config'
import { relative } from 'node:path'
import { projectTestStorage } from '../erp-web/scripts/test-storage.ts'

const storage = projectTestStorage('erp-mobile')
const output = (name: string, fallback: string): string =>
  storage.output('vitest/' + name, fallback)

export default defineConfig({
  cacheDir: output('cache', storage.webRoot + '/node_modules/.vite'),
  test: {
    include: ['tests/**/*.test.ts'],
    coverage: {
      reportsDirectory: output('coverage', storage.webRoot + '/coverage'),
    },
    ...(storage.root
      ? {
          outputFile: {
            json: output('results.json', ''),
            junit: output('junit.xml', ''),
            html: output('html/index.html', ''),
          },
          resolveSnapshotPath: (testPath: string, extension: string): string =>
            output('snapshots/' + relative(storage.webRoot, testPath) + extension, ''),
        }
      : {}),
  },
})
