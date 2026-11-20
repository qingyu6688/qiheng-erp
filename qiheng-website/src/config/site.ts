/** 仓库、站点地址与版本号。发布新版本时只改这里 */
export const REPO = 'https://github.com/qingyu6688/qiheng-erp'
export const SITE_URL = 'https://qingyu6688.github.io/qiheng-erp'
export const VERSION = '2.0.0-rc.1'
export const RELEASE_URL = `${REPO}/releases/tag/v${VERSION}`

/** 预渲染并写入站点地图的页面，与 router/routes.ts 保持一致 */
export const SITEMAP_PATHS: readonly string[] = ['/', '/en/', '/features/ai/', '/quick-start/']

/** GitHub 上 docs/ 目录下文档的地址 */
export function repoDoc(path: string): string {
  return `${REPO}/blob/main/docs/${path}`
}
