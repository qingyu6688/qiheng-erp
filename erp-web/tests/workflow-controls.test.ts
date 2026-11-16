import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import WorkflowStarterSelector from '../src/components/workflow/WorkflowStarterSelector.vue'
import WorkflowProgressPanel from '../src/components/workflow/WorkflowProgressPanel.vue'
import * as api from '../src/api/workflow'
import { ApiError } from '../src/api/http'
import type { StartOptions } from '../src/types/workflow'
vi.mock('../src/api/workflow')
const session = reactive({ companyId: '1' })
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
const stubs = {
  ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot/></button>' },
  ElAlert: { props: ['title'], template: '<p role="alert">{{ title }}</p>' },
  ElSelect: true,
  ElOption: true,
  ElForm: { template: '<form><slot/></form>' },
  ElFormItem: { template: '<label><slot/></label>' },
  ElTable: { props: ['data'], template: '<div>{{ JSON.stringify(data) }}</div>' },
  ElTableColumn: true,
  RouterLink: { props: ['to'], template: '<a :href="to"><slot/></a>' },
}
const wrappers: ReturnType<typeof mount>[] = []
function selector() {
  const wrapper = mount(WorkflowStarterSelector, {
    props: { businessType: 'sales_order', businessId: '10', modelValue: {} },
    global: { stubs },
  })
  wrappers.push(wrapper)
  return wrapper
}
beforeEach(() => {
  vi.resetAllMocks()
  session.companyId = '1'
  vi.mocked(api.workflowStartOptions).mockResolvedValue({ nodes: [] })
})
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()))
it('没有自选节点时仍核对流程后才允许提交', async () => {
  const wrapper = selector()
  expect(wrapper.emitted('ready')?.at(-1)).toEqual([false])
  await flushPromises()
  expect(wrapper.emitted('ready')?.at(-1)).toEqual([true])
})
it('自选人员须来自当前候选且每个节点都有选择', async () => {
  vi.mocked(api.workflowStartOptions).mockResolvedValue({
    nodes: [{ id: 'review', name: '经理', candidates: [{ id: '2', name: '张经理' }] }],
  })
  const wrapper = selector()
  await flushPromises()
  expect(wrapper.emitted('ready')?.at(-1)).toEqual([false])
  await wrapper.setProps({ modelValue: { review: ['2'] } })
  expect(wrapper.emitted('ready')?.at(-1)).toEqual([true])
  await wrapper.setProps({ modelValue: { review: ['3'] } })
  expect(wrapper.emitted('ready')?.at(-1)).toEqual([false])
})
it('切换公司后不回填旧审批候选', async () => {
  let finish!: (value: StartOptions) => void
  vi.mocked(api.workflowStartOptions)
    .mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    .mockResolvedValueOnce({ nodes: [] })
  const wrapper = selector()
  session.companyId = '2'
  await flushPromises()
  finish({ nodes: [{ id: 'old', name: '旧公司经理', candidates: [] }] })
  await flushPromises()
  expect(wrapper.text()).not.toContain('旧公司经理')
  expect(wrapper.emitted('ready')?.at(-1)).toEqual([true])
})
it('权限失败阻止提交并提供重试', async () => {
  vi.mocked(api.workflowStartOptions).mockRejectedValueOnce(
    new ApiError(403, 'FORBIDDEN', '无来源权限'),
  )
  const wrapper = selector()
  await flushPromises()
  expect(wrapper.text()).toContain('无来源权限')
  expect(wrapper.emitted('ready')?.at(-1)).toEqual([false])
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(wrapper.emitted('ready')?.at(-1)).toEqual([true])
})
it('冲销预览随日期与来源变化重新核对', async () => {
  vi.mocked(api.workflowReversalStartOptions).mockResolvedValue({ nodes: [] })
  const wrapper = selector()
  await wrapper.setProps({
    businessType: 'document_reversal',
    businessId: undefined,
    preview: { sourceType: 'stock_adjustment', sourceId: '20', businessDate: '2026-11-15' },
  })
  await flushPromises()
  expect(api.workflowReversalStartOptions).toHaveBeenLastCalledWith(
    'stock_adjustment',
    '20',
    '2026-11-15',
  )
  await wrapper.setProps({
    preview: { sourceType: 'stock_adjustment', sourceId: '20', businessDate: '2026-11-15' },
  })
  await flushPromises()
  expect(api.workflowReversalStartOptions).toHaveBeenCalledTimes(1)
  await wrapper.setProps({
    preview: { sourceType: 'stock_adjustment', sourceId: '21', businessDate: '2026-11-16' },
  })
  await flushPromises()
  expect(api.workflowReversalStartOptions).toHaveBeenLastCalledWith(
    'stock_adjustment',
    '21',
    '2026-11-16',
  )
})
it('没有流程关联的旧单据不额外读取工作流', async () => {
  const wrapper = mount(WorkflowProgressPanel, {
    props: { businessType: 'sales_order', businessId: '10' },
    global: { stubs },
  })
  wrappers.push(wrapper)
  await flushPromises()
  expect(api.workflowProgress).not.toHaveBeenCalled()
  expect(wrapper.text()).toBe('')
})
it('进度权限被收回后清空原处理人和审批意见', async () => {
  vi.mocked(api.workflowProgress)
    .mockResolvedValueOnce({
      instances: [{ id: '90', status: 'approved', modelVersion: 1, startedAt: '2026-11-15' }],
      tasks: [
        {
          id: '1',
          nodeKey: 'review',
          nodeKind: 'approver',
          nodeName: '经理复核',
          assigneeId: '2',
          assigneeName: '张经理',
          status: 'approved',
          comment: '原意见',
          createdAt: '2026-11-15',
        },
      ],
    })
    .mockRejectedValueOnce(new ApiError(404, 'NOT_FOUND', '不在权限范围'))
  const wrapper = mount(WorkflowProgressPanel, {
    props: { businessType: 'sales_order', businessId: '10', instanceId: '90' },
    global: { stubs },
  })
  wrappers.push(wrapper)
  await flushPromises()
  expect(wrapper.text()).toContain('原意见')
  session.companyId = '2'
  await flushPromises()
  expect(wrapper.text()).not.toContain('原意见')
  expect(wrapper.text()).toContain('不在权限范围')
})
