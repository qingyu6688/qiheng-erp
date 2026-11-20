import { describe, expect, it } from 'vitest'
import { CHROME } from '@/content/chrome'
import { homeEn } from '@/content/home.en'
import { homeZh } from '@/content/home.zh'
import { SITEMAP_PATHS } from '@/config/site'
import { routes } from '@/router/routes'
import type { HomeContent, Locale } from '@/types/content'

const HOME: Record<Locale, HomeContent> = { zh: homeZh, en: homeEn }
const ROUTE_PATHS = new Set(routes.map((route) => route.path).filter((path) => !path.includes(':')))

/** 首页上带 id 的区块和模块卡，导航锚点只能指向这些 */
function homeAnchors(content: HomeContent): Set<string> {
  const ids = ['scenarios', 'ai', 'features', 'gallery', 'architecture', 'about', ...content.modules.items.map((item) => item.id)]
  if (content.getStarted) {
    ids.push('get-started')
  }
  return new Set(ids)
}

function internalLinks(locale: Locale): string[] {
  const chrome = CHROME[locale]
  const content = HOME[locale]
  const links = [
    chrome.lang.href,
    chrome.mega.href,
    chrome.mega.all.href,
    ...chrome.nav.flatMap((entry) => [entry.href ?? '', ...(entry.items ?? []).map((item) => item.href), ...(entry.groups ?? []).flatMap((group) => group.items.map((item) => item.href))]),
    ...chrome.footer.cols.flatMap((col) => col.links.map((link) => link.href)),
    content.hero.secondary.href,
    content.ai.more.href,
    content.modules.ai.href,
    content.cta.secondary.href,
  ]
  return links.filter((href) => href.startsWith('/'))
}

describe('中英文首页内容', () => {
  it('各区块条目数一致', () => {
    expect(homeEn.modules.items).toHaveLength(homeZh.modules.items.length)
    expect(homeEn.gallery.shots.map((shot) => shot.key)).toEqual(homeZh.gallery.shots.map((shot) => shot.key))
    expect(homeEn.trust.stats.map((stat) => stat.count)).toEqual(homeZh.trust.stats.map((stat) => stat.count))
    expect(homeEn.ai.pipeline).toHaveLength(homeZh.ai.pipeline.length)
    expect(homeEn.security.tiles).toHaveLength(homeZh.security.tiles.length)
    expect(homeEn.faq.items).toHaveLength(homeZh.faq.items.length)
  })

  it('统计条的显示值与滚动目标一致', () => {
    for (const content of Object.values(HOME)) {
      for (const stat of content.trust.stats) {
        expect(stat.value).toBe((stat.count ?? 0).toLocaleString('en-US') + (stat.suffix ?? ''))
      }
    }
  })
})

describe('站内链接', () => {
  it.each<Locale>(['zh', 'en'])('%s 版导航、页脚和内容里的站内地址都有对应路由和锚点', (locale) => {
    for (const href of internalLinks(locale)) {
      const [path = '', hash] = href.split('#')
      expect(ROUTE_PATHS.has(path), `${href} 没有对应路由`).toBe(true)
      if (hash) {
        const target = path === '/en' ? homeEn : path === '/' ? homeZh : null
        if (target) {
          expect(homeAnchors(target).has(hash), `${href} 的锚点不存在`).toBe(true)
        }
      }
    }
  })

  it('站点地图里的页面都有路由', () => {
    for (const path of SITEMAP_PATHS) {
      expect(ROUTE_PATHS.has(path === '/' ? '/' : path.replace(/\/$/, ''))).toBe(true)
    }
  })
})
