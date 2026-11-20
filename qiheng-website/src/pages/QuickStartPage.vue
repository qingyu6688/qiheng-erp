<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import CodeBlock from '@/components/CodeBlock.vue'
import SmartLink from '@/components/SmartLink.vue'
import { RELEASE_URL, VERSION, repoDoc } from '@/config/site'
import { canObserve } from '@/hooks/motion'
import { useSeo } from '@/hooks/useSeo'

useSeo({
  title: '快速开始 — 柒衡 ERP',
  description: `下载柒衡 ERP ${VERSION}，或在 Windows 从源码运行，完成第一笔采购和销售。`,
  path: '/quick-start/',
  locale: 'zh',
})

const MANUAL = repoDoc('产品/用户使用手册.md')
const side = [
  { title: '开始使用', links: [
    { label: '快速开始', href: '/quick-start', current: true },
    { label: '部署到服务器', href: repoDoc('运维/部署说明.md') },
    { label: '从 1.x 升级到 2.0', href: repoDoc('运维/1.x升级到2.0指南.md') },
  ] },
  { title: '使用指南', links: [
    { label: '资料与期初', href: `${MANUAL}#期初录入` },
    { label: '采购到付款', href: `${MANUAL}#请购与采购订单` },
    { label: '销售到收款', href: `${MANUAL}#下单与审批` },
    { label: '月末结账', href: `${MANUAL}#关闭与重新打开会计期间` },
  ] },
  { title: 'AI 模块', links: [
    { label: '能力介绍', href: '/features/ai' },
    { label: '启用 AI 服务', href: repoDoc('使用说明/M13人工智能使用与验收.md') },
  ] },
]
const toc = [
  { id: 'download', label: '安装 2.0 候选版' },
  { id: 'source', label: '从源码运行' },
  { id: 'first', label: '完成第一笔业务' },
  { id: 'ai', label: '启用 AI 服务' },
]
const files = [
  { name: `qiheng-erp-${VERSION}.zip`, desc: '后端、前端、移动端和数据库迁移脚本' },
  { name: `qiheng-erp-${VERSION}.zip.sha256`, desc: 'SHA-256 校验值' },
]
const checksumCode = `# PowerShell：比对输出与 .sha256 文件中的值\nGet-FileHash .\\qiheng-erp-${VERSION}.zip -Algorithm SHA256`
const setupCode = [
  `git clone --branch v${VERSION} --single-branch https://github.com/qingyu6688/qiheng-erp.git`,
  'cd qiheng-erp',
  '# JDK 和 Redis 路径按本机安装位置填写',
  "pwsh -NoProfile -File ./scripts/prepare-local.ps1 -JavaHome 'D:/tools/jdk-21' -RedisDirectory 'D:/tools/redis'",
  'npm --prefix erp-web ci',
].join('\n')

// 右侧“本页内容”随滚动高亮当前章节
const activeId = ref(toc[0]?.id ?? '')
const main = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null
onMounted(() => {
  if (!main.value || !canObserve()) {
    return
  }
  observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          activeId.value = entry.target.id
        }
      })
    },
    { rootMargin: '-20% 0px -70% 0px' },
  )
  main.value.querySelectorAll('h2[id]').forEach((heading) => observer?.observe(heading))
})
onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <div class="doc">
    <aside class="doc-side" aria-label="文档目录">
      <template v-for="group in side" :key="group.title">
        <h4>{{ group.title }}</h4>
        <SmartLink v-for="link in group.links" :key="link.label" :href="link.href" :aria-current="'current' in link ? 'page' : undefined">{{ link.label }}</SmartLink>
      </template>
    </aside>

    <main id="main" ref="main" class="doc-main">
      <div class="crumb">文档 / 开始使用</div>
      <h1>快速开始</h1>
      <p>两种方式任选：直接安装 2.0 候选版部署包，或者在 Windows 上从源码运行。只想先看看效果，可以用<SmartLink href="/demo">在线演示</SmartLink>，无需安装。</p>

      <h2 id="download">安装 2.0 候选版</h2>
      <p>从发布页下载部署包和校验文件，先校验再解压。</p>
      <table class="spec">
        <thead><tr><th>文件</th><th>说明</th></tr></thead>
        <tbody>
          <tr v-for="file in files" :key="file.name"><td><a :href="RELEASE_URL" rel="noopener">{{ file.name }}</a></td><td>{{ file.desc }}</td></tr>
        </tbody>
      </table>
      <CodeBlock :code="checksumCode" />
      <p>解压后按部署说明配置 JDK 21、MySQL 8.0.46、Redis 和入口代理，再初始化企业和管理员。</p>
      <div class="tip warn"><b>候选版提示</b>{{ VERSION }} 是预发布版本，适合试用和验收。正式用于生产前请先在测试环境完成升级演练。</div>

      <h2 id="source">在 Windows 从源码运行</h2>
      <p>准备 Git、PowerShell 7、JDK 21、Maven、Node.js 24、npm 和 Redis，启动 MySQL 8.0.46。首次安装请按本机环境说明准备建库与授权账号，在当前终端设置 <code>DB_USERNAME</code> 和 <code>DB_PASSWORD</code>。</p>
      <CodeBlock :code="setupCode" />
      <p>在项目根目录开两个终端，分别启动后端和前端：</p>
      <CodeBlock code="pwsh -File ./scripts/project.ps1 server" />
      <CodeBlock code="pwsh -File ./scripts/project.ps1 web" />
      <p>打开 <code>http://127.0.0.1:5173</code>，企业编码 <code>DEMO</code>，用户名 <code>admin</code>，初始密码见本机私有 <code>.env</code> 中的 <code>ERP_DEV_ADMIN_PASSWORD</code>。</p>
      <div class="tip info"><b>密码不在文档里</b>初始密码只写在你本机的 <code>.env</code>，不会出现在仓库和官网中。</div>

      <h2 id="first">完成第一笔业务</h2>
      <ol>
        <li><b>选择公司并准备资料</b>：维护仓库、客户、供应商、商品和结算账户，确认业务日期在开放的会计期间。</li>
        <li><b>按需导入期初</b>：已有库存或往来余额时，先录入期初并复核过账。</li>
        <li><b>完成一笔采购</b>：审批采购订单，收货过账，登记付款并与应付核销。</li>
        <li><b>完成一笔销售</b>：审批销售订单，发货并登记签收，收款后与应收核销。</li>
        <li><b>按岗位核对结果</b>：仓库看库存和收发记录，财务看应收应付和资金报表。</li>
      </ol>

      <h2 id="ai">可选：启用 AI 服务</h2>
      <p>AI 模块是独立服务，不启用也不影响上面的流程。启用后可以导入知识文档、使用知识问答和智能查数。配置模型提供方、向量与重排模型的步骤见<a :href="repoDoc('使用说明/M13人工智能使用与验收.md')" rel="noopener">《人工智能使用与验收》</a>。</p>

      <div class="doc-pager">
        <SmartLink href="/"><small>上一篇</small>产品概览</SmartLink>
        <a :href="repoDoc('运维/部署说明.md')" rel="noopener"><small>下一篇</small>部署到服务器</a>
      </div>
    </main>

    <nav class="doc-toc" aria-label="本页目录">
      <b>本页内容</b>
      <SmartLink v-for="item in toc" :key="item.id" :class="{ on: activeId === item.id }" :href="`/quick-start#${item.id}`">{{ item.label }}</SmartLink>
    </nav>
  </div>
</template>
