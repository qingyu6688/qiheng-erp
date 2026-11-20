import { RELEASE_URL, REPO, VERSION, repoDoc } from '@/config/site'
import type { Locale, SiteChrome } from '@/types/content'

const zh: SiteChrome = {
  brand: '柒衡 ERP',
  home: '柒衡 ERP 首页',
  mainNav: '主导航',
  github: 'GitHub 仓库',
  demo: '在线演示',
  soon: '筹备中',
  openMenu: '打开菜单',
  closeMenu: '关闭菜单',
  skip: '跳到正文',
  copy: '复制',
  copied: '已复制',
  copyFail: '请手动复制',
  lang: { short: 'EN', name: 'English', title: 'Switch to English', href: '/en', hreflang: 'en' },
  nav: [
    {
      key: 'features',
      label: '产品',
      groups: [
        {
          title: '业务',
          items: [
            { label: '销售管理', desc: '订单、预留、发货签收', href: '/#m-sales', icon: 'cart' },
            { label: '采购管理', desc: '请购、质检、收货入库', href: '/#m-purchase', icon: 'bag' },
            { label: '库存与追溯', desc: '批次、序列号、保质期', href: '/#m-inventory', icon: 'box' },
            { label: '仓储作业', desc: '上架、拣货、PDA 扫码', href: '/#m-wms', icon: 'home' },
          ],
        },
        {
          title: '财务与生产',
          items: [
            { label: '往来与资金', desc: '收付款、核销、账龄', href: '/#m-cash', icon: 'card' },
            { label: '财务总账', desc: '凭证复核、报表、结账', href: '/#m-gl', icon: 'ledger' },
            { label: '生产管理', desc: 'BOM、工单、月末成本', href: '/#m-mfg', icon: 'factory' },
            { label: '报表与预警', desc: '购销存报表、业务预警', href: '/#m-report', icon: 'chart' },
          ],
        },
        {
          title: '平台',
          items: [
            { label: '客户关系与门户', desc: '商机合同、自助对账', href: '/#m-crm', icon: 'user' },
            { label: '审批与平台', desc: '流程设计、单点登录', href: '/#m-flow', icon: 'flow' },
            { label: '基础资料', desc: '多公司、仓库、商品', href: '/#m-base', icon: 'base' },
          ],
        },
      ],
    },
    { key: 'ai', label: 'AI 能力', href: '/features/ai', badge: '新' },
    { key: 'scenarios', label: '适用场景', href: '/#scenarios' },
    { key: 'quick-start', label: '快速开始', href: '/quick-start' },
    {
      key: 'developers',
      label: '开发者',
      items: [
        { label: '技术架构', desc: '模块划分、部署方式与资源需求', href: '/#architecture', icon: 'layers' },
        { label: '设计文档', desc: '业务规则、数据库与分模块设计', href: `${REPO}/tree/main/docs/设计/详细设计`, icon: 'doc' },
        { label: '接口设计', desc: 'REST 接口、开放平台与 Webhook', href: repoDoc('设计/接口设计.md'), icon: 'api' },
        { label: '源代码', desc: 'AGPL-3.0，欢迎提交 Issue 和 PR', href: REPO, icon: 'github' },
      ],
    },
    {
      key: 'resources',
      label: '资源',
      items: [
        { label: '下载', desc: `${VERSION} 部署包与校验文件`, href: '/quick-start#download', icon: 'download' },
        { label: '更新日志', desc: '每个版本的新增与修复', href: repoDoc('更新日志.md'), icon: 'clock' },
        { label: '用户手册', desc: '按岗位整理的操作说明', href: repoDoc('产品/用户使用手册.md'), icon: 'book' },
        { label: '问题反馈', desc: '在 GitHub Issues 提交', href: `${REPO}/issues`, icon: 'chat' },
      ],
    },
  ],
  mega: {
    eyebrow: 'AI 能力 · 可选模块',
    title: 'RAG + LLM Wiki 知识库',
    desc: '回答有出处的知识问答；在权限范围内查数据、解释单据。',
    chips: ['混合召回', '重排', 'Wiki 审核'],
    more: '了解 AI 能力',
    href: '/features/ai',
    foot: '12 个模块按需启用，仓储作业可按仓开通',
    all: { label: '查看全部模块', href: '/#features' },
  },
  footer: {
    desc: '面向商贸、批发与轻制造团队的开源进销存和业财管理系统。',
    cols: [
      { title: '产品', links: [{ label: '产品功能', href: '/#features' }, { label: 'AI 能力', href: '/features/ai' }, { label: '适用场景', href: '/#scenarios' }, { label: '在线演示', href: '/demo' }] },
      { title: '开始使用', links: [{ label: '快速开始', href: '/quick-start' }, { label: '下载', href: '/quick-start#download' }, { label: '用户手册', href: repoDoc('产品/用户使用手册.md') }] },
      { title: '开发者', links: [{ label: '技术架构', href: '/#architecture' }, { label: '设计文档', href: `${REPO}/tree/main/docs/设计/详细设计` }, { label: '源代码', href: REPO }] },
      { title: '社区', links: [{ label: '问题反馈', href: `${REPO}/issues` }, { label: 'AGPL-3.0 许可证', href: `${REPO}/blob/main/LICENSE` }] },
    ],
    copyright: '© 2026 柒衡 ERP 贡献者 · 代码以 AGPL-3.0-only 发布',
    note: '本站不使用 Cookie 和统计脚本',
  },
}

const en: SiteChrome = {
  brand: 'Qiheng ERP',
  home: 'Qiheng ERP home',
  mainNav: 'Main',
  github: 'GitHub repository',
  demo: 'Live demo',
  soon: 'Coming soon',
  openMenu: 'Open menu',
  closeMenu: 'Close menu',
  skip: 'Skip to content',
  copy: 'Copy',
  copied: 'Copied',
  copyFail: 'Copy manually',
  lang: { short: '中文', name: '中文', title: '切换到中文', href: '/', hreflang: 'zh-CN' },
  nav: [
    {
      key: 'features',
      label: 'Product',
      groups: [
        {
          title: 'Operations',
          items: [
            { label: 'Sales', desc: 'Orders, reservations, delivery', href: '/en#m-sales', icon: 'cart' },
            { label: 'Purchasing', desc: 'Requisitions, inspection, receipts', href: '/en#m-purchase', icon: 'bag' },
            { label: 'Inventory & traceability', desc: 'Lots, serial numbers, expiry', href: '/en#m-inventory', icon: 'box' },
            { label: 'Warehouse operations', desc: 'Putaway, picking, PDA scanning', href: '/en#m-wms', icon: 'home' },
          ],
        },
        {
          title: 'Finance & production',
          items: [
            { label: 'Receivables & cash', desc: 'Receipts, payments, matching, aging', href: '/en#m-cash', icon: 'card' },
            { label: 'General ledger', desc: 'Reviewed vouchers, statements, close', href: '/en#m-gl', icon: 'ledger' },
            { label: 'Manufacturing', desc: 'BOMs, work orders, month-end cost', href: '/en#m-mfg', icon: 'factory' },
            { label: 'Reports & alerts', desc: 'Operational reports and alerts', href: '/en#m-report', icon: 'chart' },
          ],
        },
        {
          title: 'Platform',
          items: [
            { label: 'CRM & customer portal', desc: 'Deals, contracts, self-service statements', href: '/en#m-crm', icon: 'user' },
            { label: 'Approvals & platform', desc: 'Workflow designer, SSO, MFA', href: '/en#m-flow', icon: 'flow' },
            { label: 'Master data', desc: 'Companies, warehouses, SKUs', href: '/en#m-base', icon: 'base' },
          ],
        },
      ],
    },
    { key: 'ai', label: 'AI', href: '/en#ai', badge: 'New' },
    { key: 'scenarios', label: 'Use cases', href: '/en#scenarios' },
    { key: 'quick-start', label: 'Get started', href: '/en#get-started' },
    {
      key: 'developers',
      label: 'Developers',
      items: [
        { label: 'Architecture', desc: 'Modules, deployment and sizing', href: '/en#architecture', icon: 'layers' },
        { label: 'Design docs (Chinese)', desc: 'Business rules, database, module designs', href: `${REPO}/tree/main/docs/设计/详细设计`, icon: 'doc', hreflang: 'zh-CN' },
        { label: 'API design (Chinese)', desc: 'REST API, open platform and webhooks', href: repoDoc('设计/接口设计.md'), icon: 'api', hreflang: 'zh-CN' },
        { label: 'Source code', desc: 'AGPL-3.0. Issues and pull requests welcome', href: REPO, icon: 'github' },
      ],
    },
    {
      key: 'resources',
      label: 'Resources',
      items: [
        { label: 'Download', desc: `${VERSION} package and checksum`, href: RELEASE_URL, icon: 'download' },
        { label: 'README (English)', desc: 'Overview and running it locally', href: `${REPO}#english`, icon: 'book' },
        { label: 'Changelog (Chinese)', desc: 'What changed in each release', href: repoDoc('更新日志.md'), icon: 'clock', hreflang: 'zh-CN' },
        { label: 'Report an issue', desc: 'Open a GitHub issue', href: `${REPO}/issues`, icon: 'chat' },
      ],
    },
  ],
  mega: {
    eyebrow: 'AI · optional module',
    title: 'RAG + LLM Wiki knowledge base',
    desc: 'Answers that cite their sources. Look up figures and explain blocked documents within your permissions.',
    chips: ['Hybrid retrieval', 'Reranking', 'Reviewed Wiki'],
    more: 'Explore AI',
    href: '/en#ai',
    foot: '12 modules, turn on only what you need',
    all: { label: 'All modules', href: '/en#features' },
  },
  footer: {
    desc: 'Open-source inventory and accounting for trading, wholesale and light-manufacturing teams.',
    cols: [
      { title: 'Product', links: [{ label: 'Features', href: '/en#features' }, { label: 'AI', href: '/en#ai' }, { label: 'Use cases', href: '/en#scenarios' }, { label: 'Live demo', href: '/demo' }] },
      { title: 'Get started', links: [{ label: 'Get started', href: '/en#get-started' }, { label: 'Download', href: RELEASE_URL }, { label: 'README (English)', href: `${REPO}#english` }] },
      { title: 'Developers', links: [{ label: 'Architecture', href: '/en#architecture' }, { label: 'Design docs (Chinese)', href: `${REPO}/tree/main/docs/设计/详细设计`, hreflang: 'zh-CN' }, { label: 'Source code', href: REPO }] },
      { title: 'Community', links: [{ label: 'Report an issue', href: `${REPO}/issues` }, { label: 'AGPL-3.0 license', href: `${REPO}/blob/main/LICENSE` }] },
    ],
    copyright: '© 2026 Qiheng ERP contributors · Released under AGPL-3.0-only',
    note: 'No cookies, no analytics scripts',
  },
}

export const CHROME: Record<Locale, SiteChrome> = { zh, en }
