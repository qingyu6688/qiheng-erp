import { existsSync, mkdirSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { posix, win32 } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseEnv } from 'node:util'

interface StorageOptions {
  repoRoot?: string
  platform?: NodeJS.Platform
  environment?: Record<string, string | undefined>
  envText?: string
}
interface TestStorage {
  root?: string
  repoRoot: string
  webRoot: string
  output: (relativePath: string, fallback: string, legacyVariable?: string) => string
  reporterEnvironment: Record<string, string>
  temporaryDirectory?: string
}

const repositoryRoot = fileURLToPath(new URL('../../', import.meta.url))
const reporterVariables = [
  'PLAYWRIGHT_HTML_OUTPUT_DIR',
  'PLAYWRIGHT_JSON_OUTPUT_NAME',
  'PLAYWRIGHT_JUNIT_OUTPUT_NAME',
  'PLAYWRIGHT_BLOB_OUTPUT_DIR',
] as const

export function projectTestStorage(
  projectName: 'erp-web' | 'erp-mobile',
  options: StorageOptions = {},
): TestStorage {
  const platform = options.platform ?? process.platform
  const paths = platform === 'win32' ? win32 : posix
  const repoRoot = paths.resolve(options.repoRoot ?? repositoryRoot)
  const webRoot = paths.join(repoRoot, projectName)
  const environment = options.environment ?? process.env
  const envFile = paths.join(repoRoot, '.env')
  const local = parseEnv(
    options.envText ?? (existsSync(envFile) ? readFileSync(envFile, 'utf8') : ''),
  )
  const setting = (name: string): string | undefined =>
    environment[name]?.trim() || local[name]?.trim() || undefined

  function allowedPath(value: string, label: string): string {
    const result = paths.resolve(repoRoot, value)
    const plain = result.replace(/^\\\\\?\\/, '')
    if (platform === 'win32' && /^c:(?:[\\/]|$)/i.test(plain)) {
      throw new Error(
        `${label} 不能指向 C 盘，请在环境变量或仓库根目录私有 .env 配置 ERP_TEST_ROOT`,
      )
    }
    if (platform === 'win32' && /^[a-z]:[^\\/]/i.test(value)) {
      throw new Error(`${label} 不能使用盘符相对路径，请填写完整的测试目录`)
    }
    return result
  }

  const configured = setting('ERP_TEST_ROOT')
  const root = configured ? allowedPath(configured, 'ERP_TEST_ROOT') : undefined
  for (const name of [...reporterVariables, 'ERP_A11Y_OUTPUT_DIR']) {
    const value = setting(name)
    if (value) allowedPath(value, name)
  }

  function output(relativePath: string, fallback: string, legacyVariable?: string): string {
    if (root) {
      const result = allowedPath(paths.join(root, projectName, relativePath), '测试输出目录')
      const relative = paths.relative(root, result)
      if (
        relative === '..' ||
        relative.startsWith('..' + paths.sep) ||
        paths.isAbsolute(relative)
      ) {
        throw new Error('测试输出目录不能超出 ERP_TEST_ROOT')
      }
      return result
    }
    const legacy = legacyVariable ? setting(legacyVariable) : undefined
    return allowedPath(legacy ? paths.resolve(webRoot, legacy) : fallback, '测试输出目录')
  }

  const reporterEnvironment: Record<string, string> = root
    ? {
        ERP_TEST_ROOT: root,
        PLAYWRIGHT_HTML_OUTPUT_DIR: paths.join(root, projectName, 'playwright/html'),
        PLAYWRIGHT_JSON_OUTPUT_NAME: paths.join(root, projectName, 'playwright/results.json'),
        PLAYWRIGHT_JUNIT_OUTPUT_NAME: paths.join(root, projectName, 'playwright/junit.xml'),
        PLAYWRIGHT_BLOB_OUTPUT_DIR: paths.join(root, projectName, 'playwright/blob'),
      }
    : {}

  return {
    root,
    repoRoot,
    webRoot,
    output,
    reporterEnvironment,
    temporaryDirectory: root ? output('tmp', '') : undefined,
  }
}

export function webTestStorage(options: StorageOptions = {}): TestStorage {
  return projectTestStorage('erp-web', options)
}

export function prepareTestRuntime(storage: TestStorage): Record<string, string> {
  storage.output('tmp', tmpdir())
  if (!storage.temporaryDirectory) return storage.reporterEnvironment
  mkdirSync(storage.temporaryDirectory, { recursive: true })
  return {
    ...storage.reporterEnvironment,
    TEMP: storage.temporaryDirectory,
    TMP: storage.temporaryDirectory,
    TMPDIR: storage.temporaryDirectory,
  }
}
