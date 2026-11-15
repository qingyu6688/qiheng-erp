// @vitest-environment node
import { afterEach, describe, expect, it } from 'vitest'
import { spawnSync } from 'node:child_process'
import { lstat, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { basename, dirname, isAbsolute, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const prefix = 'erp-dist-clean-'
const fixtures: string[] = []
async function fixture(
  name = 'erp-web',
): Promise<{ root: string; outside: string; script: string }> {
  const directory = await mkdtemp(join(tmpdir(), prefix))
  fixtures.push(directory)
  const root = join(directory, 'erp-web'),
    outside = join(directory, 'outside')
  await mkdir(join(root, 'scripts'), { recursive: true })
  await mkdir(outside)
  const script = join(root, 'scripts', 'clean-dist.mjs')
  await writeFile(
    script,
    await readFile(join(dirname(fileURLToPath(import.meta.url)), '../scripts/clean-dist.mjs')),
  )
  await writeFile(join(root, 'package.json'), JSON.stringify({ name }))
  return { root, outside, script }
}
function clean(script: string, cwd: string, args: string[] = []): ReturnType<typeof spawnSync> {
  return spawnSync(process.execPath, [script, ...args], { cwd, encoding: 'utf8' })
}
afterEach(async () => {
  for (const directory of fixtures.splice(0)) {
    const child = relative(resolve(tmpdir()), resolve(directory))
    if (isAbsolute(child) || child.startsWith('..') || !basename(directory).startsWith(prefix)) {
      throw new Error('测试清理目录不在预期临时目录中')
    }
    await rm(directory, { recursive: true, force: true })
  }
})
describe('构建前安全清理', () => {
  it('从其他工作目录运行时只清理指定dist，并兼容首次构建', async () => {
    const { root, outside, script } = await fixture()
    await mkdir(join(root, 'dist', 'assets'), { recursive: true })
    await writeFile(join(root, 'dist', 'assets', 'index-old.js'), '旧产物')
    await writeFile(join(root, 'keep.txt'), '项目资料')
    expect(clean(script, outside).status).toBe(0)
    await expect(lstat(join(root, 'dist'))).rejects.toHaveProperty('code', 'ENOENT')
    expect(await readFile(join(root, 'keep.txt'), 'utf8')).toBe('项目资料')
    expect(clean(script, outside).status).toBe(0)
  })
  it('拒绝dist目录联接，保留链接目标文件', async () => {
    const { root, outside, script } = await fixture()
    await writeFile(join(outside, 'keep.txt'), '外部资料')
    await symlink(outside, join(root, 'dist'), process.platform === 'win32' ? 'junction' : 'dir')
    const result = clean(script, root)
    expect(result.status).toBe(1)
    expect(String(result.stderr)).toContain('是链接或目录联接')
    expect(await readFile(join(outside, 'keep.txt'), 'utf8')).toBe('外部资料')
    expect((await lstat(join(root, 'dist'))).isSymbolicLink()).toBe(true)
  })
  it('项目身份不匹配时拒绝删除', async () => {
    const { root, script } = await fixture('other-project')
    await mkdir(join(root, 'dist'))
    await writeFile(join(root, 'dist', 'keep.txt'), '保留')
    const result = clean(script, root)
    expect(result.status).toBe(1)
    expect(String(result.stderr)).toContain('未确认前端项目根目录')
    expect(await readFile(join(root, 'dist', 'keep.txt'), 'utf8')).toBe('保留')
  })
  it('拒绝外部路径参数', async () => {
    const { root, outside, script } = await fixture()
    await mkdir(join(root, 'dist'))
    await writeFile(join(root, 'dist', 'keep.txt'), '保留')
    const result = clean(script, root, [outside])
    expect(result.status).toBe(1)
    expect(String(result.stderr)).toContain('不接收路径参数')
    expect(await readFile(join(root, 'dist', 'keep.txt'), 'utf8')).toBe('保留')
  })
})
