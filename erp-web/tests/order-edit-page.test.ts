import { beforeEach, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import OrderEditPage from '../src/pages/OrderEditPage.vue'
import * as sales from '../src/api/sales'
import * as procurement from '../src/api/procurement'
import type { SalesDocument } from '../src/types/sales'

const route = reactive({ params: {} as Record<string, string> })
const navigation = vi.hoisted(() => ({
  push: vi.fn(),
  replace: vi.fn(),
  guard: undefined as (() => boolean) | undefined,
}))
vi.mock('../src/api/sales')
vi.mock('../src/api/procurement')
vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => navigation,
  onBeforeRouteLeave: (guard: () => boolean) => {
    navigation.guard = guard
  },
}))
const stubs = {
  ElAlert: { props: ['title'], template: '<p role="alert">{{ title }}</p>' },
  OrderEditor: {
    name: 'OrderEditor',
    props: { inline: Boolean, original: Object },
    emits: ['dirty', 'saved'],
    template:
      '<section class="editor"><button @click="$emit(\'dirty\', true)">修改订单</button><button @click="$emit(\'saved\', { id: \'10\' })">保存订单</button></section>',
  },
  ProcurementEditor: {
    props: { inline: Boolean, original: Object },
    template: '<section class="purchase-editor" />',
  },
}
const order: SalesDocument = {
  id: '10',
  documentNo: 'SO-1',
  status: 'draft',
  version: 2,
  allowedActions: ['update'],
  lines: Array.from({ length: 50 }, (_, index) => ({
    id: String(index + 1),
    skuId: '40',
    qty: '1.000000',
  })),
}
beforeEach(() => {
  vi.resetAllMocks()
  route.params = {}
  navigation.guard = undefined
  vi.mocked(sales.document).mockResolvedValue(order)
  vi.mocked(procurement.procurementDocument).mockResolvedValue(order)
})
it('新建订单使用页面编辑容器，未保存修改离开需确认，保存后直接跳详情', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  const wrapper = mount(OrderEditPage, { props: { kind: 'sales-orders' }, global: { stubs } })
  await flushPromises()
  expect(wrapper.find('[role=dialog]').exists()).toBe(false)
  expect(navigation.guard?.()).toBe(true)
  await wrapper.get('.editor button').trigger('click')
  expect(navigation.guard?.()).toBe(false)
  expect(confirm).toHaveBeenCalledWith('修改尚未保存，确定离开此页面？')
  await wrapper.findAll('.editor button')[1]!.trigger('click')
  await flushPromises()
  expect(navigation.replace).toHaveBeenCalledWith('/sales-orders/10')
  expect(navigation.guard?.()).toBe(true)
  wrapper.unmount()
  confirm.mockRestore()
})
it('刷新编辑页读取服务端草稿及50行，不依赖上个页面状态', async () => {
  route.params = { id: '10' }
  const wrapper = mount(OrderEditPage, { props: { kind: 'sales-orders' }, global: { stubs } })
  await flushPromises()
  expect(sales.document).toHaveBeenCalledWith('sales-orders', '10')
  const editor = wrapper.findComponent({ name: 'OrderEditor' })
  expect(editor.props('original').lines).toHaveLength(50)
  expect(editor.props('inline')).toBe(true)
  wrapper.unmount()
})
it('已不能编辑的订单显示当前状态提示，禁止显示编辑表单', async () => {
  route.params = { id: '10' }
  vi.mocked(procurement.procurementDocument).mockResolvedValue({
    ...order,
    status: 'approved',
    allowedActions: ['post'],
  })
  const wrapper = mount(OrderEditPage, { props: { kind: 'purchase-orders' }, global: { stubs } })
  await flushPromises()
  expect(wrapper.get('[role=alert]').text()).toContain('当前不能编辑')
  expect(wrapper.find('.purchase-editor').exists()).toBe(false)
  wrapper.unmount()
})
