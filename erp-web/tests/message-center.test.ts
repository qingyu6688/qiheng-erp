import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import MessageCenterPage from '../src/pages/MessageCenterPage.vue'
import * as api from '../src/api/platform'
import type { Page, MessageRow } from '../src/api/platform'

vi.mock('../src/api/platform')
const session = reactive({ companyId: '1' })
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
const stubs = {
  ElButton: {
    props: ['nativeType', 'disabled'],
    template: '<button :type="nativeType ?? \'button\'" :disabled="disabled"><slot/></button>',
  },
  ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' },
  ElTable: { name: 'ElTable', props: ['data'], template: '<div>{{JSON.stringify(data)}}</div>' },
  ElTableColumn: true,
  ElCheckbox: true,
  ElSelect: true,
  ElOption: true,
  ElDialog: true,
  ElDrawer: true,
}
const mounted: ReturnType<typeof mount>[] = []
beforeEach(() => {
  vi.resetAllMocks()
  session.companyId = '1'
  vi.mocked(api.inbox).mockResolvedValue({ items: [], nextCursor: null })
})
afterEach(() => mounted.splice(0).forEach((wrapper) => wrapper.unmount()))
function page() {
  const wrapper = mount(MessageCenterPage, { global: { stubs } })
  mounted.push(wrapper)
  return wrapper
}
it('切换公司后旧响应不能回填到当前消息列表', async () => {
  let resolve!: (page: Page<MessageRow>) => void
  vi.mocked(api.inbox)
    .mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      }),
    )
    .mockResolvedValueOnce({
      items: [
        { id: 'new', title: '新公司消息', content: '当前范围', category: 'system', createdAt: '' },
      ],
      nextCursor: null,
    })
  const wrapper = page()
  await flushPromises()
  session.companyId = '2'
  await flushPromises()
  resolve({
    items: [
      { id: 'old', title: '旧公司消息', content: '旧数据', category: 'system', createdAt: '' },
    ],
    nextCursor: null,
  })
  await flushPromises()
  expect(wrapper.text()).toContain('新公司消息')
  expect(wrapper.text()).not.toContain('旧公司消息')
})
it('实时摘要到达后重新读取服务端收件箱，卸载后不再拉取', async () => {
  const wrapper = page()
  await flushPromises()
  expect(api.inbox).toHaveBeenCalledTimes(1)
  window.dispatchEvent(new Event('erp:messages-changed'))
  await flushPromises()
  expect(api.inbox).toHaveBeenCalledTimes(2)
  wrapper.unmount()
  window.dispatchEvent(new Event('erp:messages-changed'))
  await flushPromises()
  expect(api.inbox).toHaveBeenCalledTimes(2)
})
