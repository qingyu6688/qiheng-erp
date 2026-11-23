import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import InboundInvoiceLedger from '../src/components/einvoice/InboundInvoiceLedger.vue'
import * as api from '../src/api/e-invoice'
import { invoice } from './fixtures/inbound-invoices'
import type { InboundInvoiceRow } from '../src/types/inbound-invoices'

vi.mock('../src/api/e-invoice')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
const session = reactive<{ companyId: string; can: (permission: string) => boolean }>({
  companyId: '1',
  can: () => true,
})
const allowLeave = vi.fn(() => true)
const Drawer = defineComponent({
  name: 'InboundInvoiceDrawer',
  props: ['id'],
  emits: ['close', 'saved'],
  setup(_, { expose }) {
    expose({ mayLeave: allowLeave })
  },
  template:
    '<aside>发票详情 {{id}}<button @click="$emit(\'close\')">关闭详情</button><button @click="$emit(\'saved\',id)">保存详情</button></aside>',
})
const Select = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template:
    '<select :value="modelValue ?? \'\'" @change="$emit(\'update:modelValue\',$event.target.value)"><option value="">全部</option><slot/></select>',
})
const stubs = {
  InboundInvoiceDrawer: Drawer,
  ElButton: {
    props: ['nativeType', 'disabled', 'loading'],
    template: '<button :type="nativeType ?? \'button\'" :disabled="disabled"><slot/></button>',
  },
  ElInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template:
      '<input :value="modelValue" @input="$emit(\'update:modelValue\',$event.target.value)"/>',
  },
  ElSelect: Select,
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{label}}</option>' },
  ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' },
  ElTable: {
    name: 'ElTable',
    props: ['data'],
    template:
      '<div><div class="el-table__body-wrapper"><div class="el-scrollbar__wrap" style="overflow:auto;width:50px;height:20px"><div style="width:200px;height:60px">滚动</div></div></div><slot/></div>',
  },
  ElTableColumn: { name: 'ElTableColumn', props: ['label'], template: '<div>{{label}}</div>' },
}
const wrappers: ReturnType<typeof mount>[] = []
function page(): ReturnType<typeof mount> {
  const wrapper = mount(InboundInvoiceLedger, { global: { stubs } })
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
  allowLeave.mockReturnValue(true)
  session.companyId = '1'
  session.can = () => true
  vi.mocked(api.inboundInvoices).mockResolvedValue([invoice()])
})
afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
})

describe('进项台账筛选与右侧详情', () => {
  it('保存详情沿用已应用筛选、游标和滚动位置，关闭无需路由跳转', async () => {
    vi.mocked(api.inboundInvoices).mockResolvedValue(
      Array.from({ length: 30 }, (_, index) => invoice({ id: String(100 - index) })),
    )
    const wrapper = page()
    await flushPromises()
    await wrapper.get('#inbound-status-filter').setValue('difference')
    await wrapper.get('#inbound-source-filter').setValue('manual')
    await wrapper.get('#inbound-search').setValue('验收')
    await wrapper.get('form[aria-label="进项台账筛选"]').trigger('submit')
    await flushPromises()
    await click(wrapper, '下一页')
    const lastQuery = vi.mocked(api.inboundInvoices).mock.calls.at(-1)!
    expect(lastQuery.slice(0, 2)).toEqual([
      '71',
      { matchStatus: 'difference', source: 'manual', keyword: '验收' },
    ])
    await click(wrapper, '登记手工票')
    const scroll = wrapper.get<HTMLElement>('.el-scrollbar__wrap').element
    scroll.scrollLeft = 38
    scroll.scrollTop = 17
    await click(wrapper, '保存详情')
    expect(vi.mocked(api.inboundInvoices).mock.calls.at(-1)!.slice(0, 2)).toEqual(
      lastQuery.slice(0, 2),
    )
    expect(scroll.scrollLeft).toBe(38)
    expect(scroll.scrollTop).toBe(17)
    await click(wrapper, '关闭详情')
    expect(wrapper.findComponent({ name: 'InboundInvoiceDrawer' }).exists()).toBe(false)
    expect(wrapper.get<HTMLInputElement>('#inbound-search').element.value).toBe('验收')
    expect(wrapper.text()).toContain('第 2 页')
  })
  it('权限控制登记入口，刷新只使用已应用条件，旧请求不能覆盖新查询', async () => {
    session.can = (permission) => permission !== 'einv:inbound_register'
    let resolve!: (rows: InboundInvoiceRow[]) => void
    vi.mocked(api.inboundInvoices)
      .mockReturnValueOnce(
        new Promise((done) => {
          resolve = done
        }),
      )
      .mockResolvedValueOnce([invoice({ id: 'NEW' })])
    const wrapper = page()
    await flushPromises()
    await wrapper.get('#inbound-search').setValue('尚未应用的搜索')
    await click(wrapper, '刷新台账')
    resolve([invoice({ id: 'OLD' })])
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ElTable' }).props('data')[0].id).toBe('NEW')
    expect(vi.mocked(api.inboundInvoices).mock.calls[1]![1]).toEqual({})
    expect(vi.mocked(api.inboundInvoices).mock.calls[0]![2]?.aborted).toBe(true)
    expect(wrapper.text()).not.toContain('登记手工票')
  })
  it('详情未确认时阻止离开，切换公司或卸载中止读取', async () => {
    const wrapper = page()
    await flushPromises()
    await click(wrapper, '登记手工票')
    allowLeave.mockReturnValue(false)
    expect((wrapper.vm as unknown as { mayLeave(): boolean }).mayLeave()).toBe(false)
    session.companyId = '2'
    await flushPromises()
    expect(wrapper.findComponent({ name: 'InboundInvoiceDrawer' }).exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'ElTable' }).props('data')).toEqual([])
    expect(vi.mocked(api.inboundInvoices).mock.calls[0]![2]?.aborted).toBe(true)
  })
})
