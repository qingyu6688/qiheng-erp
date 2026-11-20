<script setup lang="ts">
import { ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import CtaPanel from '@/components/CtaPanel.vue'
import FaqSection from '@/components/FaqSection.vue'
import SectionHead from '@/components/SectionHead.vue'
import SmartLink from '@/components/SmartLink.vue'
import LintReportMock from '@/components/ai/LintReportMock.vue'
import { REPO, repoDoc } from '@/config/site'
import { usePageEffects } from '@/hooks/usePageEffects'
import { useSeo } from '@/hooks/useSeo'
import type { FaqContent, IconCard } from '@/types/content'

useSeo({
  title: 'AI 能力 — 柒衡 ERP',
  description: '柒衡 ERP 的 AI 模块：RAG 混合检索知识库、会自我维护的 LLM Wiki、智能查数、单据解释与门户客服，权限不外溢，模型可私有化。',
  ogDescription: 'RAG 混合检索知识库、会自我维护的 LLM Wiki、智能查数和单据解释，权限不外溢，模型可私有化。',
  path: '/features/ai/',
  locale: 'zh',
})

const root = ref<HTMLElement | null>(null)
usePageEffects(root)

// 只写 erp-ai 已实现的能力，对照表见《官网方案》第 4.1 节；AI 录单尚未实现，不写
const pipeline = [
  { n: '01 入库', title: '上传文档', desc: '按租户、角色设置可见范围' },
  { n: '02 解析', title: '按章节切分', desc: '保留标题层级，可预览调整' },
  { n: '03 检索', title: '混合召回', desc: '关键词 + bge-m3 向量，RRF 融合', hi: true },
  { n: '04 重排', title: '精排去噪', desc: 'bge-reranker，低分片段丢弃', hi: true },
  { n: '05 Wiki', title: '沉淀主题页', desc: '起草、审核、发布、体检', hi: true },
  { n: '06 回答', title: '带引用作答', desc: '无依据时明确拒答' },
]
const ragChecks = [
  { b: '混合召回', text: '关键词 + bge-m3 向量，可选 OpenSearch 或内置检索' },
  { b: '重排阈值', text: 'bge-reranker 打分低于阈值的片段不进入回答' },
  { b: '逐段引用', text: '结论后标注来源文档和章节，点开即可核对原文' },
  { b: '可见范围', text: '检索前先按租户和角色过滤，看不到的文档不会被引用' },
]
const wikiChecks = [
  { b: '起草与修订', text: '新资料入库后自动生成或更新对应主题页，以差异形式提交' },
  { b: '审核流转', text: '发布、驳回、归档由管理员操作，每次修订保留记录' },
  { b: '每周体检', text: '检查数值矛盾、来源失效、断链、孤立页和半年无人访问的页面' },
  { b: '跟着资料更新', text: '源文档换版或调整可见范围后，相关主题页自动排队重新起草' },
]
const queryChecks = [
  { b: '查数', text: '客户欠款、库存余量、订单履约、到期应付等常见问题' },
  { b: '解释单据', text: '为什么不能发货、为什么关账没通过，给出原因和处理入口' },
  { b: '只读边界', text: 'AI 不能新建、审批、过账或付款，所有写操作仍由人在系统里完成' },
]
const controls: IconCard[] = [
  { icon: 'chat', title: '门户智能客服', desc: '客户在门户提问时先识别意图，只回答本单位的订单、发货和对账，处理不了的自动转成人工工单。' },
  { icon: 'pulse', title: '模型网关', desc: '统一接入 OpenAI 兼容接口和 Anthropic，可接私有化部署的开源模型。按场景配置路由和备用模型。' },
  { icon: 'chart', title: '额度与日志', desc: '按租户设置调用额度，记录每次调用的场景、模型、耗时和用量，便于成本核算和问题追溯。' },
  { icon: 'globe', title: '出境审批', desc: '境外模型默认关闭，需要部署开关和管理员确认两道放行，满足数据不出境的要求。' },
  { icon: 'check', title: '上线前评测', desc: '内置评测集，衡量回答正确率、引用准确率和拒答率。改提示词或换模型后重跑，分数不降再发布。' },
  { icon: 'server', title: '独立部署', desc: 'AI 作为独立服务运行，ERP 通过内部接口调用。不启用时主流程完全不受影响。' },
]
const faq: FaqContent = {
  eyebrow: '常见问题',
  title: '关于 AI 模块',
  desc: '部署和配置步骤见《人工智能使用与验收》。',
  items: [
    { q: '不启用 AI 能正常使用 ERP 吗？', a: '可以。AI 是可选的独立服务，进销存、财务等主流程不依赖它。' },
    { q: '数据会发给外部模型吗？', a: '取决于你配置的模型。接入私有化部署的模型时数据不出内网；境外模型默认关闭，需要部署开关和管理员确认两道放行。' },
    { q: 'AI 会不会看到无权限的数据？', a: '不会。查数以提问人本人身份调用 ERP 接口，知识检索前先按租户和角色过滤，权限校验都在服务端完成。' },
    { q: '需要什么硬件？', a: '使用云端模型时，AI 服务本身只需普通应用服务器；自建向量模型和重排模型时建议配备 GPU。具体见部署说明。' },
  ],
}
const aiGuide = repoDoc('使用说明/M13人工智能使用与验收.md')
</script>

<template>
  <main id="main" ref="root">
    <section class="ai-band page-top" data-anim>
      <div class="wrap">
        <p class="crumb" data-reveal><SmartLink href="/">首页</SmartLink> / AI 能力</p>
        <h1 data-reveal style="--d: 0.08s">让知识有出处，<br />让数据开口说话</h1>
        <p class="lead" data-reveal style="--d: 0.16s">RAG 混合检索加 LLM Wiki，把散落的制度、手册和经验整理成一套审核过的知识库；AI 助手在你的权限范围内查数据、解释单据。独立服务部署，不启用也不影响 ERP 主流程。</p>
        <div class="ctas" data-reveal style="--d: 0.24s">
          <SmartLink class="btn btn-primary btn-lg" href="/quick-start">了解部署方式<AppIcon name="arrow" class="arr" /></SmartLink>
          <a class="btn btn-lg btn-ghost-dark" :href="`${REPO}/tree/main/erp-ai`" rel="noopener">查看 erp-ai 源码</a>
        </div>
        <div class="pipeline page-pipeline" aria-label="RAG + LLM Wiki 知识处理流程" data-stagger="0.1">
          <span class="pipe-track" aria-hidden="true"></span>
          <div v-for="step in pipeline" :key="step.n" class="pipe" :class="{ hi: step.hi }" data-reveal>
            <span class="n">{{ step.n }}</span><b>{{ step.title }}</b><span>{{ step.desc }}</span>
          </div>
        </div>
      </div>
    </section>

    <section class="block">
      <div class="wrap feature-row">
        <div data-reveal>
          <div class="eyebrow">知识问答 · RAG</div>
          <h3>回答有出处，找不到就说没有</h3>
          <p>关键词检索擅长精确的编号和术语，向量检索擅长换个说法的提问。两路结果用 RRF 融合后再由重排模型打分，只把真正相关的片段交给大模型。</p>
          <ul class="checks">
            <li v-for="check in ragChecks" :key="check.b"><AppIcon name="check" /><span><b>{{ check.b }}</b>：{{ check.text }}</span></li>
          </ul>
        </div>
        <div class="chat" aria-label="知识问答示例" data-reveal="scale">
          <div class="msg me">客户退货后，退款可以直接登记吗？</div>
          <div class="msg ai">不可以。退货单过账后系统会生成应收冲减<sup>[1]</sup>；退款需要先提交申请，由另一名财务复核后才能登记<sup>[2]</sup>。<span class="src">来源：[1] 业务规则 · 销售退货&#12288;[2] 用户使用手册 · 退款申请</span></div>
          <div class="msg me">退款能退到客户的其他账户吗？</div>
          <div class="msg ai">知识库里没有找到相关规定，建议咨询财务负责人。</div>
        </div>
      </div>
    </section>

    <section class="block alt">
      <div class="wrap feature-row rev">
        <div data-reveal>
          <div class="eyebrow">LLM Wiki</div>
          <h3>模型负责整理，人负责把关</h3>
          <p>传统 RAG 每次从零散片段临时拼答案，同一个问题不同人问可能得到不同说法。LLM Wiki 先让模型把资料整理成互相链接的主题页，再由管理员审核发布，问答优先引用审核过的页面。</p>
          <ul class="checks">
            <li v-for="check in wikiChecks" :key="check.b"><AppIcon name="check" /><span><b>{{ check.b }}</b>：{{ check.text }}</span></li>
          </ul>
        </div>
        <LintReportMock />
      </div>
    </section>

    <section class="block">
      <div class="wrap feature-row">
        <div data-reveal>
          <div class="eyebrow">智能查数 · 单据解释</div>
          <h3>用说话的方式查业务数据</h3>
          <p>AI 助手通过一组只读业务工具查询 ERP，以提问人自己的身份调用，字段权限、公司、仓库和部门范围照常生效。结果与报表同源，并标明数据截止时间。</p>
          <ul class="checks">
            <li v-for="check in queryChecks" :key="check.b"><AppIcon name="check" /><span><b>{{ check.b }}</b>：{{ check.text }}</span></li>
          </ul>
        </div>
        <div class="chat" aria-label="智能查数示例" data-reveal="scale">
          <div class="msg me">华东商贸本月还欠我们多少？</div>
          <div class="msg ai">
            华东商贸当前未核销应收 <b>¥186,420.00</b>，其中逾期 30 天以上 ¥42,800.00。
            <table>
              <tbody>
                <tr><td>SO-2026110012 · 未收</td><td>¥98,620.00</td></tr>
                <tr><td>SO-2026110027 · 部分收款</td><td>¥45,000.00</td></tr>
                <tr><td>SO-2026100088 · 逾期 34 天</td><td>¥42,800.00</td></tr>
              </tbody>
            </table>
            <span class="src">数据截至 2026-11-18 10:32 · 与“应收账龄”报表同源</span>
          </div>
          <div class="msg me">SO-2026110012 为什么还不能发货？</div>
          <div class="msg ai">客户已超信用额度 ¥36,420.00，订单被冻结。可登记收款后释放，或由销售经理申请临时额度。<span class="src">处理入口：往来与资金 → 信用额度</span></div>
        </div>
      </div>
    </section>

    <section class="block alt">
      <div class="wrap">
        <SectionHead eyebrow="管理与安全" title="企业可控的 AI" lead="模型、额度、日志和评测都在管理员手里，换模型不用改业务代码。" />
        <div class="grid g3" data-stagger>
          <div v-for="item in controls" :key="item.title" class="sec-tile" data-reveal>
            <span class="ico"><AppIcon :name="item.icon" /></span>
            <h3>{{ item.title }}</h3>
            <p>{{ item.desc }}</p>
          </div>
        </div>
      </div>
    </section>

    <section class="block">
      <FaqSection :faq="faq">
        <template #desc>部署和配置步骤见<a :href="aiGuide" rel="noopener">《人工智能使用与验收》</a>。</template>
      </FaqSection>
    </section>

    <CtaPanel title="从实际业务流程开始了解" desc="在线演示提供采购、销售、库存和资金的只读浏览。AI 助手需要单独部署与配置，本次演示不包含 AI。" primary="在线演示" :secondary="{ label: '快速开始', href: '/quick-start' }" />
  </main>
</template>
