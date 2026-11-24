import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import GlVouchersPage from '../src/pages/gl/GlVouchersPage.vue'
import GlVoucherEditPage from '../src/pages/gl/GlVoucherEditPage.vue'
import * as api from '../src/api/gl'
import * as admin from '../src/api/admin'
import { ApiError } from '../src/api/http'
import type { GlVoucher } from '../src/types/gl'

vi.mock('../src/api/gl')
vi.mock('../src/api/admin')
const session = reactive({ companyId: '1', can: vi.fn(() => true) })
const route = reactive({ params: {} as Record<string, string>, query: {} })
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
}))
const stubs = {
  ElButton: {
    props: ['disabled', 'loading'],
    template: '<button :disabled="disabled || loading"><slot/></button>',
  },
  ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' },
  ElSelect: true,
  ElOption: true,
  ElInput: true,
  ElDatePicker: true,
  ElInputNumber: true,
  ElTag: true,
  ElTable: { props: ['data'], template: '<div class="table">{{JSON.stringify(data)}}</div>' },
  ElTableColumn: true,
  ElFormItem: { template: '<label><slot/></label>' },
  GlAuxFields: true,
  GlOutputActions: true,
  RouterLink: { props: ['to'], template: '<a><slot/></a>' },
  Preview: {
    props: ['kind', 'id'],
    emits: ['close'],
    template: '<aside>{{kind}} {{id}}<button @click="$emit(\'close\')">关闭来源</button></aside>',
  },
}
const wrappers: ReturnType<typeof mount>[] = []
const voucher = {
  id: '10',
  wordId: '20',
  periodId: '30',
  number: 1,
  source: 'manual',
  status: 'draft',
  voucherDate: '2026-10-15',
  makerId: '1',
  version: 0,
  lines: [],
  remark: '旧公司凭证',
  debitTotal: '100.00',
  creditTotal: '100.00',
  attachmentCount: 0,
} satisfies GlVoucher
beforeEach(() => {
  vi.resetAllMocks()
  session.companyId = '1'
  session.can.mockReturnValue(true)
  route.params = {}
  vi.mocked(api.glConfig).mockResolvedValue([])
  vi.mocked(api.glSubjects).mockResolvedValue([])
  vi.mocked(api.glRead).mockResolvedValue({})
  vi.mocked(admin.records).mockResolvedValue({ items: [], nextCursor: null })
  vi.mocked(api.glVouchers).mockResolvedValue([voucher])
})
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()))
it('切换公司时立即清空旧公司凭证，不等待新公司的资料请求', async () => {
  const wrapper = mount(GlVouchersPage, { global: { stubs } })
  wrappers.push(wrapper)
  await flushPromises()
  expect(wrapper.text()).toContain('旧公司凭证')
  vi.mocked(api.glConfig).mockReturnValueOnce(new Promise(() => {}))
  session.companyId = '2'
  await flushPromises()
  expect(wrapper.text()).not.toContain('旧公司凭证')
})
it('迟到的旧公司列表不能覆盖新公司结果', async () => {
  let finish!: (value: GlVoucher[]) => void
  vi.mocked(api.glVouchers)
    .mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    .mockResolvedValueOnce([{ ...voucher, id: '11', remark: '新公司凭证' }])
  const wrapper = mount(GlVouchersPage, { global: { stubs } })
  wrappers.push(wrapper)
  await flushPromises()
  session.companyId = '2'
  await flushPromises()
  finish([voucher])
  await flushPromises()
  expect(wrapper.text()).toContain('新公司凭证')
  expect(wrapper.text()).not.toContain('旧公司凭证')
})
it('无编辑权限时不提供新建凭证入口', async () => {
  session.can.mockImplementation((permission?: string) => permission !== 'gl_voucher:edit')
  const wrapper = mount(GlVouchersPage, { global: { stubs } })
  wrappers.push(wrapper)
  await flushPromises()
  expect(wrapper.text()).not.toContain('新增手工凭证')
})
it('手工凭证网络重试沿用原幂等键，避免重复创建', async () => {
  vi.mocked(api.saveGlVoucher).mockRejectedValue(new ApiError(0, 'NETWORK_ERROR', '网络暂时不可用'))
  const wrapper = mount(GlVoucherEditPage, { global: { stubs, directives: { loading: () => {} } } })
  wrappers.push(wrapper)
  await flushPromises()
  const save = wrapper.findAll('button').find((button) => button.text() === '保存草稿')!
  await save.trigger('click')
  await flushPromises()
  await save.trigger('click')
  await flushPromises()
  expect(api.saveGlVoucher).toHaveBeenCalledTimes(2)
  expect(vi.mocked(api.saveGlVoucher).mock.calls[0]![2]).toBe(
    vi.mocked(api.saveGlVoucher).mock.calls[1]![2],
  )
  expect(wrapper.text()).toContain('网络暂时不可用')
})

it('凭证列表继续加载保留已有凭证，长编号游标原样传给服务端', async () => {
  const rows = Array.from({ length: 200 }, (_, index) => ({
    ...voucher,
    id: String(900000000000000000n - BigInt(index)),
    remark: '凭证' + index,
  }))
  vi.mocked(api.glVouchers)
    .mockResolvedValueOnce(rows)
    .mockResolvedValueOnce([{ ...voucher, id: '899999999999999000', remark: '后续凭证' }])
  const wrapper = mount(GlVouchersPage, { global: { stubs } })
  wrappers.push(wrapper)
  await flushPromises()
  const more = wrapper.findAll('button').find((button) => button.text() === '继续加载')!
  await more.trigger('click')
  await flushPromises()
  expect(vi.mocked(api.glVouchers).mock.calls.at(-1)![0]).toMatchObject({ cursor: rows.at(-1)!.id })
  expect(wrapper.text()).toContain('凭证0')
  expect(wrapper.text()).toContain('后续凭证')
  expect(wrapper.text()).toContain('201')
})

it.each(['sales_return', 'refund_request'])(
  '业务凭证%s的来源只读右侧打开，不触发凭证更正',
  async (sourceType) => {
    route.params = { id: '10' }
    const no = sourceType === 'sales_return' ? 'SR-51' : 'RF-92'
    vi.mocked(api.glVoucher).mockResolvedValue({
      ...voucher,
      source: 'business',
      status: 'posted',
      sourceType,
      sourceId: sourceType === 'sales_return' ? '51' : '92',
      sourceNo: no,
      returnMode: sourceType === 'sales_return' ? 'sourceless' : undefined,
      refundMode: sourceType === 'refund_request' ? 'sourceless_credit' : undefined,
    })
    const wrapper = mount(GlVoucherEditPage, {
      global: { stubs, directives: { loading: () => {} } },
    })
    wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.get('.voucher-source').text()).toContain(
      sourceType === 'sales_return' ? '无原单退货' : '退货信用额退款',
    )
    const source = wrapper.findAll('button').find((button) => button.text() === no)!
    expect(source).toBeDefined()
    await source.trigger('click')
    await flushPromises()
    expect(wrapper.get('aside').text()).toContain(
      sourceType === 'sales_return' ? 'sales-returns 51' : 'refund-requests 92',
    )
    await wrapper.get('aside button').trigger('click')
    expect(wrapper.find('aside').exists()).toBe(false)
    expect(api.saveGlVoucher).not.toHaveBeenCalled()
    expect(route.params.id).toBe('10')
  },
)
