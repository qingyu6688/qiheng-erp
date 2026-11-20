import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { defineComponent, h } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import CodeBlock from '@/components/CodeBlock.vue'
import SiteFooter from '@/components/SiteFooter.vue'
import SmartLink from '@/components/SmartLink.vue'

const Empty = defineComponent({ render: () => h('div') })

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: Empty, meta: { locale: 'zh' } },
      { path: '/en', component: Empty, meta: { locale: 'en' } },
    ],
  })
}

describe('SmartLink', () => {
  it('站外地址渲染成普通链接并加 rel="noopener"', async () => {
    const router = makeRouter()
    await router.push('/')
    const wrapper = mount(SmartLink, { props: { href: 'https://github.com/qingyu6688/qiheng-erp' }, slots: { default: 'GitHub' }, global: { plugins: [router] } })
    const a = wrapper.get('a')
    expect(a.attributes('rel')).toBe('noopener')
    expect(a.attributes('href')).toBe('https://github.com/qingyu6688/qiheng-erp')
  })

  it('站内地址走路由，保留锚点', async () => {
    const router = makeRouter()
    await router.push('/')
    const wrapper = mount(SmartLink, { props: { href: '/en#ai' }, slots: { default: 'AI' }, global: { plugins: [router] } })
    expect(wrapper.get('a').attributes('href')).toBe('/en#ai')
    expect(wrapper.get('a').attributes('rel')).toBeUndefined()
  })
})

describe('SiteFooter', () => {
  it.each(['/', '/en'])('在 %s 页显示网站备案号和工信部链接', async (path) => {
    const router = makeRouter()
    await router.push(path)
    const wrapper = mount(SiteFooter, { global: { plugins: [router], stubs: { RouterLink: true } } })
    const record = wrapper.get('footer a[href="https://beian.miit.gov.cn/"]')
    expect(record.text()).toBe('鄂ICP备2026068130号-1')
  })
})

describe('CodeBlock', () => {
  it('# 开头的行显示为注释色，按钮文案随页面语言', async () => {
    const router = makeRouter()
    await router.push('/en')
    const wrapper = mount(CodeBlock, { props: { code: '# comment\nnpm ci' }, global: { plugins: [router] } })
    const lines = wrapper.findAll('pre span')
    expect(lines).toHaveLength(2)
    expect(lines[0]?.classes()).toContain('c')
    expect(lines[1]?.classes()).not.toContain('c')
    expect(wrapper.get('button.copy').text()).toBe('Copy')
    expect(wrapper.get('pre').text()).toContain('npm ci')
  })
})
