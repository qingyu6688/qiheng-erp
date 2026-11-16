import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import WorkflowCenterPage from '../src/pages/workflow/WorkflowCenterPage.vue'
import * as api from '../src/api/workflow'
import { ApiError } from '../src/api/http'
import type { WorkflowInstance } from '../src/types/workflow'
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
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template:
      '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)"/>',
  },
  ElSelect: true,
  ElOption: true,
  ElAlert: { props: ['title'], template: '<p>{{ title }}</p>' },
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
