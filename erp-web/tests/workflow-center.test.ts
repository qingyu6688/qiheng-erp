import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import WorkflowCenterPage from '../src/pages/workflow/WorkflowCenterPage.vue'
import * as api from '../src/api/workflow'
import { ApiError } from '../src/api/http'
import type { WorkflowInstance, WorkflowListRow } from '../src/types/workflow'
vi.mock('../src/api/workflow')
const session = reactive({ companyId: '1', user: { id: '2' }, can: () => false })
const route = reactive<{ params: { id?: string } }>({ params: { id: '90' } })
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ replace: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
}))
const value: WorkflowInstance = {
  id: '90',
  businessType: 'sales_order',
  businessId: '10',
  businessNo: 'SO-10',
  title: '订单复核',
  status: 'running',
  starterId: '1',
  modelVersion: 1,
  version: 0,
  startedAt: '2026-11-15',
  summary: {},
  tasks: [
    {
      id: '9',
      instanceId: '90',
      nodeKey: 'manager',
      nodeName: '经理复核',
      nodeKind: 'approver',
      assigneeId: '2',
      status: 'pending',
      version: 7,
      createdAt: '2026-11-15',
    },
  ],
  actions: [],
  canCancel: false,
  canTerminate: false,
}
const stubs = {
  ElDrawer: { props: ['modelValue'], template: '<aside v-if="modelValue"><slot/></aside>' },
  ElDialog: {
    props: ['modelValue'],
    template:
      '<section v-if="modelValue" role="dialog"><slot/><footer><slot name="footer"/></footer></section>',
  },
  ElForm: { props: ['disabled'], template: '<fieldset :disabled="disabled"><slot/></fieldset>' },
  ElFormItem: { template: '<label><slot/></label>' },
  ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot/></button>' },
  ElInput: {
    props: ['modelValue', 'type'],
    emits: ['update:modelValue'],
    template:
      '<textarea v-if="type === \'textarea\'" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)"/><input v-else :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)"/>',
  },
  ElSelect: true,
  ElOption: true,
  ElAlert: { props: ['title'], template: '<p>{{ title }}</p>' },
  ElDropdown: { template: '<div><slot/></div>' },
  ElDropdownItem: true,
  ElDropdownMenu: true,
  ElTag: { template: '<span><slot/></span>' },
  ElTable: true,
  ElTableColumn: true,
  RouterLink: { template: '<a><slot/></a>' },
}
const wrappers: ReturnType<typeof mount>[] = []
beforeEach(() => {
  vi.resetAllMocks()
  session.companyId = '1'
  route.params.id = '90'
  vi.mocked(api.workflowList).mockResolvedValue({ items: [], nextCursor: null })
  vi.mocked(api.workflowInstance).mockResolvedValue(structuredClone(value))
})
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()))
function page() {
  const wrapper = mount(WorkflowCenterPage, { props: { kind: 'todo' }, global: { stubs } })
  wrappers.push(wrapper)
  return wrapper
}
function button(wrapper: ReturnType<typeof mount>, text: string) {
  const result = wrapper.findAll('button').find((item) => item.text() === text)
  if (!result) throw new Error('找不到按钮：' + text)
  return result
}
it('审批结果未知时冻结版本、意见和幂等键重试', async () => {
  vi.mocked(api.workflowTaskAction)
    .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络超时'))
    .mockResolvedValueOnce({ id: '9', instanceId: '90', status: 'approved' })
  const wrapper = page()
  await flushPromises()
  await button(wrapper, '同意').trigger('click')
  await wrapper.get('textarea').setValue('核对通过')
  await button(wrapper, '确认同意').trigger('click')
  await flushPromises()
  const first = vi.mocked(api.workflowTaskAction).mock.calls[0]
  expect(first?.[2]).toMatchObject({ version: 7, comment: '核对通过' })
  expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
  await button(wrapper, '使用原请求重试').trigger('click')
  await flushPromises()
  expect(vi.mocked(api.workflowTaskAction).mock.calls[1]).toEqual(first)
})
it('其他处理人的任务不展示同意按钮', async () => {
  vi.mocked(api.workflowInstance).mockResolvedValue({
    ...structuredClone(value),
    tasks: [{ ...value.tasks[0]!, assigneeId: '3' }],
  })
  const wrapper = page()
  await flushPromises()
  expect(wrapper.find('.flow-task-action').exists()).toBe(false)
  expect(wrapper.findAll('button').some((item) => item.text() === '同意')).toBe(false)
})
it('切换公司后旧流程详情不能回填', async () => {
  let complete!: (value: WorkflowInstance) => void
  vi.mocked(api.workflowInstance)
    .mockReturnValueOnce(
      new Promise((resolve) => {
        complete = resolve
      }),
    )
    .mockRejectedValueOnce(new ApiError(404, 'NOT_FOUND', '不在当前公司'))
  const wrapper = page()
  session.companyId = '2'
  await flushPromises()
  complete(structuredClone(value))
  await flushPromises()
  expect(wrapper.text()).not.toContain('经理复核')
  expect(wrapper.text()).toContain('不在当前公司')
})

const row = (id: string, batchGroup: string | null = '10:1:manager'): WorkflowListRow => ({
  id,
  instanceId: id + '0',
  title: '订单 ' + id,
  businessType: 'sales_order',
  businessNo: 'SO-' + id,
  nodeName: '经理复核',
  starterName: '王晨',
  totalAmount: '21.50',
  taskVersion: 7,
  batchGroup,
  status: 'running',
  itemCreatedAt: '2026-11-15T08:00:00Z',
})

it('左右预览使用来源单据返回的明细，处理后打开下一条', async () => {
  route.params.id = undefined
  vi.mocked(api.workflowList)
    .mockResolvedValueOnce({ items: [row('9'), row('8')], nextCursor: null })
    .mockResolvedValue({ items: [row('8')], nextCursor: null })
  vi.mocked(api.workflowInstance)
    .mockResolvedValueOnce({ ...value, title: '订单 9' })
    .mockResolvedValue({
      ...value,
      id: '80',
      businessId: '11',
      title: '订单 8',
      tasks: [{ ...value.tasks[0]!, id: '8' }],
    })
  vi.mocked(api.workflowSourcePreview).mockResolvedValue({
    customer: { name: '华东贸易' },
    lines: [
      {
        sku: { name: '配件' },
        qty: '2',
        unitPrice: '10.75',
        receivableAmount: '21.50',
        unitCost: '9876.12',
      },
    ],
  })
  vi.mocked(api.workflowTaskAction).mockResolvedValue({
    id: '9',
    instanceId: '90',
    status: 'approved',
  })
  const wrapper = page()
  await flushPromises()
  expect(wrapper.get('[aria-label="待办列表"]').text()).toContain('王晨')
  expect(wrapper.get('[aria-label="待办详情"]').text()).toContain('华东贸易')
  expect(wrapper.get('[aria-label="单据明细"]').text()).toContain('配件')
  expect(wrapper.get('[aria-label="单据明细"]').text()).not.toContain('9876.12')
  await button(wrapper, '同意').trigger('click')
  await button(wrapper, '确认同意').trigger('click')
  await flushPromises()
  expect(wrapper.get('[aria-label="待办详情"] h2').text()).toBe('订单 8')
})

it('批量只能选择同版本同环节，无确认响应时保留原请求并列出逐项结果', async () => {
  route.params.id = undefined
  vi.mocked(api.workflowList).mockResolvedValue({
    items: [row('9'), row('8'), row('7', '10:2:manager'), row('6', null)],
    nextCursor: null,
  })
  vi.mocked(api.workflowBatchApprove)
    .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络超时'))
    .mockResolvedValueOnce([
      { id: '9', approved: true },
      { id: '8', approved: false, message: '审批状态已变化' },
    ])
  const wrapper = page()
  await flushPromises()
  const checkboxes = wrapper.findAll('input[type="checkbox"]')
  expect(checkboxes).toHaveLength(3)
  await checkboxes[0]!.setValue(true)
  expect(checkboxes[2]!.attributes('disabled')).toBeDefined()
  await checkboxes[1]!.setValue(true)
  await button(wrapper, '批量同意（2）').trigger('click')
  await button(wrapper, '确认批量同意').trigger('click')
  await flushPromises()
  await button(wrapper, '使用原请求重试').trigger('click')
  await flushPromises()
  const calls = vi.mocked(api.workflowBatchApprove).mock.calls
  expect(calls[0]?.[0]).toEqual({
    group: '10:1:manager',
    items: [
      { id: '9', version: 7 },
      { id: '8', version: 7 },
    ],
  })
  expect(calls[1]).toEqual(calls[0])
  const results = wrapper.get('[aria-label="批量审批结果"]')
  expect(results.text()).toContain('订单 9 · 已同意')
  expect(results.text()).toContain('订单 8 · 处理失败（审批状态已变化）')
})

it('流程仍可查看时会明确展示现有规则造成的处理限制', async () => {
  vi.mocked(api.workflowInstance).mockResolvedValue({
    ...value,
    tasks: [{ ...value.tasks[0]!, canHandle: false }],
  })
  const wrapper = page()
  await flushPromises()
  expect(wrapper.text()).toContain('当前权限或经办复核规则限制了任务处理')
  expect(wrapper.find('footer[aria-label="审批操作"]').exists()).toBe(false)
})
