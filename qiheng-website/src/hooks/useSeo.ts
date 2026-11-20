import { useHead } from '@unhead/vue'
import { SITE_URL, VERSION, REPO } from '@/config/site'
import type { Locale } from '@/types/content'

export interface SeoOptions {
  title: string
  description: string
  /** 站内路径，例如 /、/en/、/features/ai/ */
  path: string
  locale: Locale
  ogDescription?: string
  /** 中英文首页互为对应页面时声明 hreflang */
  alternates?: { zh: string; en: string }
  noindex?: boolean
  /** 首页输出 SoftwareApplication 结构化数据 */
  structuredData?: boolean
}

/** 每页的标题、描述、规范地址、分享卡片和语言声明 */
export function useSeo(options: SeoOptions): void {
  const url = SITE_URL + options.path
  const isEn = options.locale === 'en'
  const link: { rel: string; href: string; hreflang?: string }[] = [{ rel: 'canonical', href: url }]
  if (options.alternates) {
    link.push(
      { rel: 'alternate', hreflang: 'zh-CN', href: SITE_URL + options.alternates.zh },
      { rel: 'alternate', hreflang: 'en', href: SITE_URL + options.alternates.en },
      { rel: 'alternate', hreflang: 'x-default', href: SITE_URL + options.alternates.zh },
    )
  }
  const meta = [
    { name: 'description', content: options.description },
    { property: 'og:type', content: 'website' },
    { property: 'og:site_name', content: isEn ? 'Qiheng ERP' : '柒衡 ERP' },
    { property: 'og:title', content: options.title },
    { property: 'og:description', content: options.ogDescription ?? options.description },
    { property: 'og:url', content: url },
    { property: 'og:image', content: `${SITE_URL}/og.png` },
    { property: 'og:image:width', content: '1200' },
    { property: 'og:image:height', content: '630' },
    { property: 'og:locale', content: isEn ? 'en_US' : 'zh_CN' },
    { name: 'twitter:card', content: 'summary_large_image' },
  ]
  if (options.noindex) {
    meta.push({ name: 'robots', content: 'noindex' })
  }
  const script = options.structuredData
    ? [
        {
          type: 'application/ld+json',
          innerHTML: JSON.stringify({
            '@context': 'https://schema.org',
            '@type': 'SoftwareApplication',
            name: isEn ? 'Qiheng ERP' : '柒衡 ERP',
            alternateName: isEn ? '柒衡 ERP' : 'Qiheng ERP',
            applicationCategory: 'BusinessApplication',
            operatingSystem: 'Linux, Windows',
            softwareVersion: VERSION,
            license: 'https://www.gnu.org/licenses/agpl-3.0.html',
            url,
            codeRepository: REPO,
            inLanguage: isEn ? 'en' : 'zh-CN',
            offers: { '@type': 'Offer', price: '0', priceCurrency: isEn ? 'USD' : 'CNY' },
          }),
        },
      ]
    : []
  useHead({
    title: options.title,
    htmlAttrs: { lang: isEn ? 'en' : 'zh-CN' },
    link,
    meta,
    script,
  })
}
