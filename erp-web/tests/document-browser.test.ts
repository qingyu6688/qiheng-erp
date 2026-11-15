import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import DocumentBrowserPanel from '../src/components/DocumentBrowserPanel.vue'
import DocumentEditorFrame from '../src/components/DocumentEditorFrame.vue'
import * as api from '../src/api/document-browser'
import * as admin from '../src/api/admin'
import { ApiError } from '../src/api/http'
import { documentStateTag, quantityDifference } from '../src/types/document-ui'

const route = reactive({ path: '/sales-orders', query: {} as Record<string, string> })
const security = reactive({ companyId: '1' })
const navigation = vi.hoisted(() => ({ replace: vi.fn() }))
vi.mock('../src/api/document-browser')
vi.mock('../src/api/admin')
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => navigation }))
vi.mock('../src/stores/session', () => ({
  useSessionStore: () => ({
    get companyId() {
      return security.companyId
    },
    can: () => true,
  }),
}))
const input = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template:
    '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
})
const select = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template:
    '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>',
})
const stubs = {
  RouterLink: true,
  ElTag: true,
  ElInput: input,
  ElSelect: select,
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  ElButton: {
    props: ['nativeType', 'disabled'],
    template: '<button :type="nativeType" :disabled="disabled"><slot /></button>',
  },
  ElCheckbox: true,
  ElAlert: { props: ['title'], template: '<p role="alert">{{ title }}</p>' },
  ElTable: { props: ['data'], template: '<div>{{ JSON.stringify(data) }}</div>' },
  ElTableColumn: true,
  ElDialog: { template: '<div role="dialog"><slot /><slot name="footer" /></div>' },
}
beforeEach(() => {
  vi.resetAllMocks()
  route.path = '/sales-orders'
  route.query = {}
  security.companyId = '1'
  vi.mocked(api.browseDocuments).mockResolvedValue({
    items: [],
    nextCursor: null,
    total: 0,
    statusCounts: { draft: 2, approved: 4 },
  })
  vi.mocked(admin.allRecords).mockImplementation(async (path) =>
    path === 'partners'
      ? [{ id: '9007199254740993', name: '客户甲' }]
      : [{ id: '30', name: '主仓' }],
  )
})
describe('单据列表筛选与状态', () => {
  it.each(['purchase-requisitions', 'document-reversals'])(
    '不为%s提供服务端不支持的仓库筛选',
    async (kind) => {
      const wrapper = mount(DocumentBrowserPanel, { props: { kind }, global: { stubs } })
      await flushPromises()
      expect(wrapper.find('#' + kind + '-warehouse').exists()).toBe(false)
      expect(admin.allRecords).not.toHaveBeenCalledWith('warehouses', true)
      wrapper.unmount()
    },
  )
  it('库存调整列表不展示服务端没有提供的头部合计', async () => {
    const wrapper = mount(DocumentBrowserPanel, {
      props: { kind: 'stock-adjustments' },
      global: { stubs },
    })
    await flushPromises()
    expect(
      wrapper
        .findAllComponents({ name: 'ElTableColumn' })
        .some(
          (column) =>
            column.attributes('label') === '金额（元）' || column.props('label') === '金额（元）',
        ),
    ).toBe(false)
    wrapper.unmount()
  })
  it('第三页刷新后恢复游标历史，上一页仍回到第二页', async () => {
    const page = {
      items: [{ id: '99', documentNo: 'SO-01', version: 0, status: 'draft', allowedActions: [] }],
      total: 60,
      statusCounts: { draft: 60 },
    }
    vi.mocked(api.browseDocuments)
      .mockResolvedValueOnce({ ...page, nextCursor: '30' })
      .mockResolvedValueOnce({ ...page, nextCursor: '20' })
      .mockResolvedValue({ ...page, nextCursor: '10' })
    const wrapper = mount(DocumentBrowserPanel, {
      props: { kind: 'sales-orders' },
      global: { stubs },
    })
    await flushPromises()
    const next = (): ReturnType<typeof wrapper.get> =>
      wrapper.findAll('button').find((button) => button.text() === '下一页')!
    await next().trigger('click')
    await flushPromises()
    await next().trigger('click')
    await flushPromises()
    expect(navigation.replace).toHaveBeenLastCalledWith({
      path: '/sales-orders',
      query: { cursor: '20', status: '', pageCursors: '30,20' },
    })
    route.query = { cursor: '20', status: '', pageCursors: '30,20' }
    await flushPromises()
    expect(wrapper.get('.table-footer').text()).toContain('第 41—41 条')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '上一页')!
      .trigger('click')
    await flushPromises()
    expect(api.browseDocuments).toHaveBeenLastCalledWith(
      'sales-orders',
      expect.objectContaining({ cursor: '30' }),
      expect.any(AbortSignal),
    )
    wrapper.unmount()
  })
  it('URL中的非法游标历史不进入分页请求', async () => {
    route.query = { cursor: '20', pageCursors: '30,非法,20' }
    const wrapper = mount(DocumentBrowserPanel, {
      props: { kind: 'sales-orders' },
      global: { stubs },
    })
    await flushPromises()
    expect(api.browseDocuments).toHaveBeenLastCalledWith(
      'sales-orders',
      expect.objectContaining({ cursor: '20' }),
      expect.any(AbortSignal),
    )
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '上一页')!
      .trigger('click')
    await flushPromises()
    expect(vi.mocked(api.browseDocuments).mock.calls.at(-1)?.[1].cursor).toBeUndefined()
    wrapper.unmount()
  })
  it('首次读取地址栏日期与客户，大ID保留为字符串', async () => {
    route.query = {
      dateFrom: '2026-11-01',
      dateTo: '2026-11-15',
      partnerId: '9007199254740993',
      status: 'approved',
    }
    const wrapper = mount(DocumentBrowserPanel, {
      props: { kind: 'sales-orders' },
      global: { stubs },
    })
    await flushPromises()
    expect(api.browseDocuments).toHaveBeenLastCalledWith(
      'sales-orders',
      expect.objectContaining({
        dateFrom: '2026-11-01',
        dateTo: '2026-11-15',
        partnerId: '9007199254740993',
        status: 'approved',
        withTotal: true,
      }),
      expect.any(AbortSignal),
    )
    expect(
      wrapper
        .findAll('[role=tab]')
        .find((tab) => tab.text().includes('全部'))
        ?.text(),
    ).toBe('全部 6')
    wrapper.unmount()
  })
  it('状态页签更新URL，刷新条件可恢复', async () => {
    const wrapper = mount(DocumentBrowserPanel, {
      props: { kind: 'sales-orders' },
      global: { stubs },
    })
    await flushPromises()
    await wrapper
      .findAll('[role=tab]')
      .find((tab) => tab.text().includes('待审核'))!
      .trigger('click')
    await flushPromises()
    expect(navigation.replace).toHaveBeenLastCalledWith({
      path: '/sales-orders',
      query: { status: 'pending' },
    })
    expect(api.browseDocuments).toHaveBeenLastCalledWith(
      'sales-orders',
      expect.objectContaining({ status: 'pending' }),
      expect.any(AbortSignal),
    )
    wrapper.unmount()
  })
  it('收付款默认待确认，但地址栏显式全部时不覆盖', async () => {
    const wrapper = mount(DocumentBrowserPanel, { props: { kind: 'payments' }, global: { stubs } })
    await flushPromises()
    expect(api.browseDocuments).toHaveBeenLastCalledWith(
      'payments',
      expect.objectContaining({ status: 'pending_confirm' }),
      expect.any(AbortSignal),
    )
    route.query = { status: '' }
    await flushPromises()
    expect(vi.mocked(api.browseDocuments).mock.calls.at(-1)?.[1]).not.toHaveProperty('status')
    wrapper.unmount()
  })
  it('公司切换终止旧请求，旧响应不能覆盖新公司', async () => {
    let resolve: (value: Awaited<ReturnType<typeof api.browseDocuments>>) => void = () => undefined
    vi.mocked(api.browseDocuments).mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      }),
    )
    const wrapper = mount(DocumentBrowserPanel, {
      props: { kind: 'sales-orders' },
      global: { stubs },
    })
    const signal = vi.mocked(api.browseDocuments).mock.calls[0]![2]!
    security.companyId = '2'
    await flushPromises()
    expect(signal.aborted).toBe(true)
    resolve({
      items: [
        { id: '1', status: 'draft', version: 0, allowedActions: [], documentNo: '旧公司的单据' },
      ],
      nextCursor: null,
      total: 1,
      statusCounts: {},
    })
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧公司的单据')
    wrapper.unmount()
  })
  it('日期查询失败显示实际原因，重试后恢复', async () => {
    vi.mocked(api.browseDocuments).mockRejectedValueOnce(
      new ApiError(400, 'VALIDATION_FAILED', '结束日期不能早于开始日期'),
    )
    const wrapper = mount(DocumentBrowserPanel, {
      props: { kind: 'sales-orders' },
      global: { stubs },
    })
    await flushPromises()
    expect(wrapper.get('[role=alert]').text()).toContain('结束日期不能早于开始日期')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[role=alert]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('独立编辑容器保留底部操作，只有弹窗模式使用dialog', () => {
    const wrapper = mount(DocumentEditorFrame, {
      props: { inline: true, title: '新建销售订单', busy: false },
      slots: { default: '<input aria-label="订单客户" />', footer: '<button>保存订单</button>' },
      global: { stubs },
    })
    expect(wrapper.find('[role=dialog]').exists()).toBe(false)
    expect(wrapper.get('.order-editor-footer').text()).toBe('保存订单')
    wrapper.unmount()
  })
})
describe('状态与签收差异', () => {
  it('状态颜色区分待处理、完成和冲销', () => {
    expect(documentStateTag('shipments', 'posted')).toBe('primary')
    expect(documentStateTag('sales-orders', 'pending')).toBe('warning')
    expect(documentStateTag('payments', 'confirmed')).toBe('success')
    expect(documentStateTag('shipments', 'reversed')).toBe('danger')
  })
  it('大数量和六位小数差值不经过浮点计算', () => {
    expect(quantityDifference('9007199254740993.000001', '9007199254740992.999999')).toBe(
      '0.000002',
    )
    expect(quantityDifference('4', '3.500000')).toBe('0.5')
    expect(quantityDifference('4', '4')).toBe('0')
    expect(quantityDifference('4', '5')).toBe('—')
    expect(quantityDifference('4', '错误')).toBe('—')
  })
})
