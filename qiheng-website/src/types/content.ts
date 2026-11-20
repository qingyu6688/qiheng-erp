import type { IconName } from '@/components/icons'
import type { ShotKey } from '@/utils/assets'

export type Locale = 'zh' | 'en'

/** 导航分组的 key，也用于首页区块的滚动高亮 */
export type NavKey = 'features' | 'ai' | 'scenarios' | 'quick-start' | 'developers' | 'resources'

/** 站内地址以 / 开头（可带 #锚点），站外地址以 http 开头 */
export interface LinkItem {
  label: string
  href: string
  /** 链接指向另一种语言的页面时填写，例如 zh-CN */
  hreflang?: string
}

export interface MenuItem extends LinkItem {
  desc: string
  icon: IconName
}

export interface NavEntry {
  key: NavKey
  label: string
  href?: string
  badge?: string
  /** 大菜单：分组列出模块 */
  groups?: { title: string; items: MenuItem[] }[]
  /** 普通下拉 */
  items?: MenuItem[]
}

export interface SiteChrome {
  brand: string
  home: string
  mainNav: string
  github: string
  demo: string
  soon: string
  openMenu: string
  closeMenu: string
  skip: string
  copy: string
  copied: string
  copyFail: string
  /** 切换到另一种语言 */
  lang: { short: string; name: string; title: string; href: string; hreflang: string }
  nav: NavEntry[]
  mega: { eyebrow: string; title: string; desc: string; chips: string[]; more: string; href: string; foot: string; all: LinkItem }
  footer: { desc: string; cols: { title: string; links: LinkItem[] }[]; copyright: string; note: string }
}

export interface SectionHeadContent {
  eyebrow: string
  title: string
  lead?: string
}

export interface IconCard {
  icon: IconName
  title: string
  desc: string
}

export interface HomeContent {
  seo: { title: string; description: string; ogDescription: string }
  hero: {
    announce: { tag: string; text: string }
    titleLines: [string, string]
    sub: string
    primary: string
    secondary: LinkItem
    meta: string[]
    floats: { icon: IconName; ok?: boolean; title: string; text: string }[]
    shot: { url: string; alt: string; label: string }
    phoneAlt: string
  }
  stack: { label: string; ariaLabel: string; items: string[] }
  scenarios: SectionHeadContent & {
    tiles: (IconCard & { points: string[] })[]
    mini: { label: string; title: string; note: string; rows: { name: string; amount: string; ratio: number; warn?: boolean }[] }
  }
  flows: SectionHeadContent & {
    items: { name: string; sub: string; steps: { label: string; key?: boolean; dot?: boolean }[] }[]
  }
  trust: SectionHeadContent & {
    items: IconCard[]
    stats: { value: string; count?: number; suffix?: string; label: string }[]
  }
  ai: SectionHeadContent & {
    pipelineLabel: string
    pipeline: { n: string; title: string; desc: string; hi?: boolean }[]
    split: { title: string; desc: string; checks: { b: string; text: string }[] }
    wiki: WikiReviewContent
    cards: (IconCard & { wide?: boolean; ask?: { q: string; a: string; amount?: string } })[]
    evaluation: { title: string; desc: string; loop: string[] }
    more: LinkItem
  }
  modules: SectionHeadContent & {
    items: (IconCard & { id: string })[]
    ai: IconCard & { href: string }
  }
  gallery: SectionHeadContent & {
    tabsLabel: string
    shots: { key: ShotKey; label: string; url: string; caption: string }[]
  }
  architecture: SectionHeadContent & {
    layers: { label: string; chips: { text: string; primary?: boolean }[] }[]
    table: { head: [string, string]; rows: [string, string][] }
    foot: { before: string; link: LinkItem; after: string }
  }
  security: SectionHeadContent & {
    tiles: IconCard[]
    license: { title: string; sub: string; points: string[]; links: LinkItem[] }
  }
  about: SectionHeadContent & {
    titleLines: [string, string]
    mascotLabel: string
    meanings: { char: string; romanized?: string; text: string }[]
    note: string
  }
  getStarted?: SectionHeadContent & {
    cards: { icon: IconName; title: string; desc: string; code: string; note?: string; links: (LinkItem & { primary?: boolean })[] }[]
  }
  faq: FaqContent
  cta: { title: string; desc: string; primary: string; secondary: LinkItem; github: string }
}

export interface WikiReviewContent {
  ariaLabel: string
  title: string
  tag: string
  meta: string
  heading: string
  before: string
  removed: string
  added: string
  /** [1] 后和 [2] 后的标点，中英文不同 */
  sep: string
  end: string
  refs: string
  reject: string
  publish: string
}

export interface FaqContent {
  eyebrow: string
  title: string
  desc?: string
  action?: LinkItem
  items: { q: string; a: string }[]
}
