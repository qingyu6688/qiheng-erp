import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import { basename, join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { prepareTestRuntime, projectTestStorage } from './test-storage.ts'

const projectName = basename(process.cwd())
if (!['erp-web', 'erp-mobile'].includes(projectName)) {
  throw new Error('请在 erp-web 或 erp-mobile 目录运行测试')
}
const storage = projectTestStorage(projectName)
Object.assign(process.env, prepareTestRuntime(storage))
const [runner, ...args] = process.argv.slice(2)
const require = createRequire(join(process.cwd(), 'package.json'))

if (runner === 'vitest' && projectName === 'erp-mobile') {
  // uni-app 保留 Vite 5；原生只读加载配置后内联传给 Vitest，避免旧版配置 bundle 落盘。
  const { parseCLI, startVitest } = await import(pathToFileURL(require.resolve('vitest/node')).href)
  const parsed = parseCLI(['vitest', ...args])
  const configPath =
    typeof parsed.options.config === 'string'
      ? resolve(parsed.options.config)
      : join(process.cwd(), 'vitest.config.ts')
  const { default: configuration } = await import(pathToFileURL(configPath).href)
  const { test, ...vite } =
    typeof configuration === 'function'
      ? await configuration({ command: 'serve', mode: 'test' })
      : configuration
  await startVitest(
    'test',
    parsed.filter,
    {
      ...test,
      ...parsed.options,
      coverage: { ...test.coverage, ...parsed.options.coverage },
      root: process.cwd(),
      config: false,
    },
    { ...vite, configFile: false },
  )
} else {
  const executable =
    runner === 'vitest'
      ? require.resolve('vitest/vitest.mjs')
      : runner === 'playwright'
        ? require.resolve('@playwright/test/cli')
        : undefined
  if (!executable) throw new Error('测试入口仅支持 vitest 或 playwright')
  const options = runner === 'vitest' ? [...args, '--configLoader', 'runner'] : args
  const child = spawn(process.execPath, [executable, ...options], {
    stdio: 'inherit',
    env: process.env,
  })
  child.once('error', () => {
    process.stderr.write('测试进程未能启动，请检查依赖安装。\n')
    process.exitCode = 1
  })
  child.once('exit', (code) => {
    process.exitCode = code ?? 1
  })
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => child.kill(signal))
}
