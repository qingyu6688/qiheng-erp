import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import InboundInvoiceDrawer from '../src/components/einvoice/InboundInvoiceDrawer.vue'
import * as api from '../src/api/e-invoice'
import { invoice, match, preview, source } from './fixtures/inbound-invoices'

vi.mock('../src/api/e-invoice')
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }))
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
const session = reactive({ companyId: '1', can: () => true })
const wrappers: ReturnType<typeof mount>[] = []
const stubs = {
  ElDrawer: {
    name: 'ElDrawer',
    props: ['title', 'direction', 'showClose'],
    template: '<aside><h2>{{title}}</h2><slot/><slot name="footer"/></aside>',
  },
  ElButton: {
    props: ['disabled', 'loading'],
    template: '<button :disabled="disabled"><slot/></button>',
  },
  ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' },
  DocumentLineTable: { props: ['rows'], template: '<div><slot/></div>' },
  ElTableColumn: true,
  ElInput: true,
  ElSelect: true,
  ElOption: true,
  InboundMatchTable: {
    props: ['rows', 'historical'],
    template: '<div v-for="row in rows" :key="row.id">{{ row.releasedReason }}</div>',
  },
}
function page(): ReturnType<typeof mount> {
  const wrapper = mount(InboundInvoiceDrawer, { props: { id: '21' }, global: { stubs } })
  wrappers.push(wrapper)
  return wrapper
}
async function click(wrapper: ReturnType<typeof mount>, label: string): Promise<void> {
  const target = wrapper.findAll('button').find((button) => button.text() === label)
  if (!target) throw new Error('缺少按钮：' + label)
  await target.trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  session.companyId = '1'
  session.can = () => true
  vi.mocked(api.inboundInvoice).mockResolvedValue(invoice())
  vi.mocked(api.inboundSources).mockResolvedValue({ items: [source()], nextCursor: null })
  vi.mocked(api.inboundSuppliers).mockResolvedValue({ items: [], nextCursor: null })
})
afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
})
it('右侧详情同时呈现差异和票面未分完，金额精确、零值弱化并保留历史原因', async () => {
  vi.mocked(api.inboundInvoice).mockResolvedValue(
    invoice({
      matchStatus: 'difference',
      absoluteDifference: '0.01',
      fullyAllocated: false,
      history: {
        items: [match({ releasedReason: '采购调价已过账', releasedAt: '2026-11-23T02:00:00Z' })],
        nextCursor: null,
      },
    }),
  )
  const wrapper = page()
  await flushPromises()
  expect(wrapper.findComponent({ name: 'ElDrawer' }).props('direction')).toBe('rtl')
  expect(wrapper.text()).toContain('有差异')
  expect(wrapper.text()).toContain('票面尚未分配完')
  expect(wrapper.text()).toContain('采购调价已过账')
  expect(wrapper.find('.financial-amount.is-muted').exists()).toBe(true)
})
it('核对更改未预览时禁用保存，数电票和终态不显示手工改写动作', async () => {
  vi.mocked(api.inboundInvoice).mockResolvedValue(invoice({ source: 'einv' }))
  const wrapper = page()
  await flushPromises()
  expect(wrapper.text()).not.toContain('更正手工票')
  expect(wrapper.text()).not.toContain('作废手工票')
  await click(wrapper, '核对采购应付')
  expect(
    wrapper
      .findAll('button')
      .find((button) => button.text() === '保存核对')
      ?.attributes('disabled'),
  ).toBeDefined()
  vi.mocked(api.previewInboundInvoice).mockResolvedValue(preview())
  await click(wrapper, '预览核对')
  expect(wrapper.text()).toContain('服务器核对预览')
  vi.mocked(api.inboundInvoice).mockResolvedValue(invoice({ status: 'red_issued', source: 'einv' }))
  await click(wrapper, '放弃修改')
  await click(wrapper, '刷新详情')
  expect(wrapper.text()).toContain('已红冲')
  expect(wrapper.text()).not.toContain('核对采购应付')
})
