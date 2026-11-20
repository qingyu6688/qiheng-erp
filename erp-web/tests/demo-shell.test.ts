import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import DemoShell from '../src/demo/DemoShell.vue'

vi.mock('vue-router', () => ({ useRoute: () => ({ path: '/sales-orders', fullPath: '/sales-orders', meta: { title: '销售订单' } }) }))

function shell() {
  return mount(DemoShell, {
    global: {
      stubs: {
        RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
        RouterView: true, DemoDocumentDrawer: true, BrandMark: true, DisplayDensitySelect: true,
      },
    },
  })
}

describe('演示新侧栏', () => {
  it('收起和展开保持所有开放模块入口，顶栏不重复页面标题', async () => {
    const wrapper = shell()
    const links = wrapper.findAll('nav a').map((link) => link.attributes('href'))
    expect(links).toEqual(expect.arrayContaining(['/crm/customers', '/warehouse-locations', '/mfg/boms', '/mfg/work-orders', '/gl/vouchers', '/gl/ledgers']))
    await wrapper.get('button[aria-label="收起侧栏"]').trigger('click')
    expect(wrapper.classes()).toContain('sidebar-collapsed')
    expect(wrapper.get('button[aria-label="展开侧栏"]').attributes('aria-expanded')).toBe('false')
    expect(wrapper.findAll('nav a').map((link) => link.attributes('href'))).toEqual(links)
    await wrapper.get('button[aria-label="展开侧栏"]').trigger('click')
    expect(wrapper.classes()).not.toContain('sidebar-collapsed')
    expect(wrapper.get('header').text()).not.toContain('销售订单')
    wrapper.unmount()
  })

  it('手机菜单展开分组后仍可选择，选择链接才收起导航', async () => {
    const wrapper = shell()
    await wrapper.get('button[aria-label="展开导航"]').trigger('click')
    expect(wrapper.get('aside').classes()).toContain('open')
    await wrapper.get('summary').trigger('click')
    expect(wrapper.get('aside').classes()).toContain('open')
    await wrapper.get('nav a[href="/crm/customers"]').trigger('click')
    expect(wrapper.get('aside').classes()).not.toContain('open')
    wrapper.unmount()
  })
})
