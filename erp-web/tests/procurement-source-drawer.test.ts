import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import ProcurementSourceDrawer from '../src/components/procurement/ProcurementSourceDrawer.vue'
import { procurementDocument } from '../src/api/procurement'
import type { ProcurementDocument } from '../src/types/procurement'

vi.mock('../src/api/procurement')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
const session = reactive({ companyId: '1', can: () => true })
const wrappers: ReturnType<typeof mount>[] = []
function document(id: string): ProcurementDocument {
  return {
    id,
    documentNo: 'GR-' + id,
    status: 'posted',
    version: 1,
    allowedActions: [],
    supplierName: '验收供应商',
    warehouseName: '验收仓',
    businessDate: '2026-11-18',
    payableAmount: '100.00',
    taxAmount: '0.00',
    lines: [],
  }
}
function page(): ReturnType<typeof mount> {
  const wrapper = mount(ProcurementSourceDrawer, {
    props: { id: '10' },
    global: {
      stubs: {
        ElDrawer: {
          name: 'ElDrawer',
          props: ['title', 'direction'],
          template: '<aside><h2>{{title}}</h2><slot/><slot name="footer"/></aside>',
        },
        ElButton: { template: '<button><slot/></button>' },
        ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' },
        DocumentLineTable: true,
        ElTableColumn: true,
      },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}
beforeEach(() => {
  vi.resetAllMocks()
  session.companyId = '1'
  vi.mocked(procurementDocument).mockResolvedValue(document('10'))
})
afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
})
it('读取收货原单并保留只读操作，关闭不写入数据', async () => {
  const wrapper = page()
  await flushPromises()
  expect(wrapper.text()).toContain('GR-10')
  expect(wrapper.text()).toContain('验收供应商')
  expect(wrapper.text()).toContain('已过账')
  expect(wrapper.text()).not.toContain('待签收')
  expect(wrapper.findComponent({ name: 'ElDrawer' }).props('direction')).toBe('rtl')
  expect(wrapper.findAll('button').map((button) => button.text())).toEqual(['刷新单据', '关闭'])
  await wrapper.findAll('button')[1]!.trigger('click')
  expect(wrapper.emitted('close')).toHaveLength(1)
})
it('切换公司中止旧请求，旧结果不能回填', async () => {
  let resolve!: (value: ProcurementDocument) => void
  vi.mocked(procurementDocument)
    .mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      }),
    )
    .mockResolvedValueOnce(document('20'))
  const wrapper = page()
  await flushPromises()
  const signal = vi.mocked(procurementDocument).mock.calls[0]![2]
  session.companyId = '2'
  await flushPromises()
  resolve(document('OLD'))
  await flushPromises()
  expect(signal?.aborted).toBe(true)
  expect(wrapper.text()).toContain('GR-20')
  expect(wrapper.text()).not.toContain('GR-OLD')
  wrapper.unmount()
  expect(vi.mocked(procurementDocument).mock.calls[1]![2]?.aborted).toBe(true)
})
