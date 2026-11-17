import { promises as fs } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const platform = process.argv[2]
if (platform !== 'h5' && platform !== 'app') throw new Error('请指定 H5 或 App 构建目标')
const root = resolve(fileURLToPath(new URL('../', import.meta.url)))
const built = resolve(root, process.env.UNI_OUTPUT_DIR || join('dist', 'build', platform))
const destination = join(built, 'third-party-licenses')
const licenseFiles = /^(?:licen[cs]es?|notice|copying)(?:[._-].*)?$/i
const known = new Map()

async function manifest(directory) {
  try {
    return JSON.parse(await fs.readFile(join(directory, 'package.json'), 'utf8'))
  } catch (cause) {
    if (cause?.code !== 'ENOENT') throw cause
    return null
  }
}

async function installed(from, name) {
  if (!/^(?:@[\w.-]+\/)?[\w.-]+$/.test(name) || name.includes('..'))
    throw new Error('依赖包名称无效')
  for (let directory = from; directory !== dirname(directory); directory = dirname(directory)) {
    const folder = join(directory, 'node_modules', name)
    const metadata = await manifest(folder)
    if (metadata) return { folder, metadata }
    if (directory === root) break
  }
  return null
}

async function add(from, name, optional) {
  const fetched = await installed(from, name)
  if (!fetched) {
    if (optional) return
    throw new Error(`构建依赖未安装：${name}`)
  }
  const { folder, metadata } = fetched
  const version = metadata.version
  if (typeof version !== 'string' || !/^[\w.+-]+$/.test(version)) throw new Error('依赖包版本无效')
  const id = `${name}@${version}`
  if (known.has(id)) return
  known.set(id, { name, version, folder, license: metadata.license, homepage: metadata.homepage })
  const optionalNames = Object.keys(metadata.optionalDependencies ?? {})
  for (const child of Object.keys(metadata.dependencies ?? {})) {
    await add(folder, child, optionalNames.includes(child))
  }
  for (const child of optionalNames) await add(folder, child, true)
}

await fs.access(built)
const project = await manifest(root)
for (const name of Object.keys(project.dependencies)) await add(root, name, false)
await fs.mkdir(destination, { recursive: true })
const rows = ['# 移动端第三方运行时依赖', '']
for (const dependency of [...known.values()].sort((a, b) =>
  (a.name + a.version).localeCompare(b.name + b.version))) {
  const files = (await fs.readdir(dependency.folder, { withFileTypes: true }))
    .filter((file) => file.isFile() && licenseFiles.test(file.name))
  const directory = join(destination, dependency.name, dependency.version)
  if (files.length) await fs.mkdir(directory, { recursive: true })
  for (const file of files) {
    await fs.copyFile(join(dependency.folder, file.name), join(directory, file.name))
  }
  const label = typeof dependency.license === 'string' ? dependency.license : '见上游声明'
  const link = dependency.homepage ? ` [上游项目](${dependency.homepage})` : ''
  rows.push(`- ${dependency.name} ${dependency.version} · ${label}${link}`)
}
await fs.writeFile(join(destination, '依赖许可清单.md'), rows.join('\n') + '\n', 'utf8')
console.log(`已保留 ${known.size} 项移动端运行时依赖的许可清单及随包许可文件`)
