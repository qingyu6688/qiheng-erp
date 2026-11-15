import { lstat, readFile, realpath, rm } from 'node:fs/promises'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const outputPath = resolve(projectRoot, 'dist')

try {
  if (process.argv.length > 2) throw new Error('清理脚本不接收路径参数。')
  const manifest = JSON.parse(await readFile(join(projectRoot, 'package.json'), 'utf8'))
  if (manifest.name !== 'erp-web' || dirname(outputPath) !== projectRoot) {
    throw new Error('未确认前端项目根目录，已停止清理。')
  }
  const actualRoot = await realpath(projectRoot)
  const output = await lstat(outputPath).catch((cause) => {
    if (cause.code === 'ENOENT') return undefined
    throw cause
  })
  if (!output) {
    process.stdout.write('暂无 erp-web/dist，无需清理。\n')
  } else {
    if (output.isSymbolicLink()) throw new Error('erp-web/dist 是链接或目录联接，已停止清理。')
    if (!output.isDirectory()) throw new Error('erp-web/dist 不是目录，已停止清理。')
    const actualOutput = await realpath(outputPath)
    if (relative(actualRoot, actualOutput) !== 'dist') {
      throw new Error('构建输出目录超出前端项目根目录，已停止清理。')
    }
    await rm(outputPath, { recursive: true, force: true, maxRetries: 2, retryDelay: 100 })
    process.stdout.write('已清理 erp-web/dist。\n')
  }
} catch (cause) {
  process.stderr.write((cause instanceof Error ? cause.message : '清理构建目录失败。') + '\n')
  process.exitCode = 1
}
