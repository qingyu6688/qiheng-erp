import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { defineComponent, reactive, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { ApiError } from '../src/api/http'
import * as api from '../src/api/sourceless-returns'
import * as sales from '../src/api/sales'
import * as workflow from '../src/api/workflow'
import { useSourcelessMutation } from '../src/hooks/useSourcelessMutation'
import type { SourcelessKind, SourcelessMutation } from '../src/hooks/useSourcelessMutation'
import type { SalesDocument } from '../src/types/sales'
import type { SourcelessReturnInput } from '../src/types/sourceless-returns'
import type { WorkflowTask } from '../src/types/workflow'
import { confirmCompanyLeave } from '../src/utils/unsaved'

vi.mock('../src/api/sourceless-returns')
vi.mock('../src/api/sales')
vi.mock('../src/api/workflow')
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }))
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
const denied = reactive(new Set<string>())
const session = reactive({
  companyId: '1',
  user: { id: '1' },
  can: (permission: string): boolean => !denied.has(permission),
})
const wrappers: ReturnType<typeof mount>[] = []
let user = 20

beforeEach(() => {
  vi.clearAllMocks()
  denied.clear()
  session.companyId = '1'
  session.user.id = String(++user)
})
afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
})

function doc(id = '101'): SalesDocument {
  return { id, status: 'draft', version: 1, allowedActions: ['submit'] }
}
function input(): SourcelessReturnInput {
  return {
    customerId: '9007199254740993',
    warehouseId: '21',
    businessDate: '2026-11-15',
    reason: '旧系统退回',
    lines: [
      {
        skuId: '31',
        unitId: '41',
        qty: '1.000001',
        qualityResult: 'passed',
        unitPrice: '1234567890123456.12',
        taxRate: '0.13',
      },
    ],
  }
}
function panel(options: { id?: string; kind?: SourcelessKind; readOnly?: boolean } = {}): {
  value: SourcelessMutation
  unmount: () => void
  saved: ReturnType<typeof vi.fn>
  closed: ReturnType<typeof vi.fn>
} {
  let value!: SourcelessMutation
  const id = ref(options.id),
    saved = vi.fn(),
    closed = vi.fn(),
    dirty = ref(false)
  const wrapper = mount(
    defineComponent({
      setup() {
        value = useSourcelessMutation(
          options.kind ?? 'sales-returns',
          () => id.value,
          dirty,
          saved,
          closed,
        )
        return () => null
      },
    }),
    { global: { provide: { 'erp.readOnlyPreview': options.readOnly ?? false } } },
  )
  wrappers.push(wrapper)
  return { value, saved, closed, unmount: () => wrapper.unmount() }
}
const offline = (): ApiError => new ApiError(0, 'NETWORK_ERROR', '网络中断，结果未确认')

it('网络异常后冻结原参数、精确金额和原键，禁止重新保存并只重试原请求', async () => {
  vi.mocked(api.saveSourcelessReturn).mockRejectedValueOnce(offline()).mockResolvedValueOnce(doc())
  const state = panel(),
    form = input()
  await state.value.saveReturn(form)
  const first = vi.mocked(api.saveSourcelessReturn).mock.calls[0]!
  expect(state.value.locked.value).toBe(true)
  form.lines[0]!.qty = '9'
  await state.value.saveReturn(form)
  expect(api.saveSourcelessReturn).toHaveBeenCalledTimes(1)
  await state.value.retry()
  expect(api.saveSourcelessReturn).toHaveBeenLastCalledWith(
    expect.objectContaining({
      lines: [expect.objectContaining({ qty: '1.000001', unitPrice: '1234567890123456.12' })],
    }),
    first[1],
    undefined,
  )
  expect(state.saved).toHaveBeenCalledExactlyOnceWith('101')
  expect(state.value.locked.value).toBe(false)
})

it('查询404或权限错误仍保留原键，成功确认后才解除锁定', async () => {
  vi.mocked(api.saveSourcelessReturn).mockRejectedValueOnce(offline())
  vi.mocked(sales.commandResult)
    .mockRejectedValueOnce(new ApiError(404, 'NOT_FOUND', '尚无结果'))
    .mockRejectedValueOnce(new ApiError(403, 'FORBIDDEN', '当前无权查看'))
    .mockResolvedValueOnce({ status: 'succeeded', response: doc() })
  const state = panel()
  await state.value.saveReturn(input())
  const key = vi.mocked(api.saveSourcelessReturn).mock.calls[0]![1]
  await state.value.verify()
  expect(state.value.locked.value).toBe(true)
  await state.value.verify()
  expect(state.value.locked.value).toBe(true)
  await state.value.verify()
  expect(state.value.locked.value).toBe(false)
  expect(sales.commandResult).toHaveBeenNthCalledWith(3, 'sales_return.create', key)
  expect(state.saved).toHaveBeenCalledExactlyOnceWith('101')
})

it('未确认时阻止关闭和切公司，强制卸载后重开仍恢复同一请求', async () => {
  vi.mocked(api.saveSourcelessReturn).mockRejectedValueOnce(offline()).mockResolvedValueOnce(doc())
  const first = panel()
  await first.value.saveReturn(input())
  const key = vi.mocked(api.saveSourcelessReturn).mock.calls[0]![1]
  expect(first.value.mayLeave()).toBe(false)
  expect(confirmCompanyLeave()).toBe(false)
  const event = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(event)
  expect(event.defaultPrevented).toBe(true)
  first.unmount()
  const reopened = panel()
  expect(reopened.value.locked.value).toBe(true)
  await reopened.value.retry()
  expect(vi.mocked(api.saveSourcelessReturn).mock.calls[1]![1]).toBe(key)
  expect(reopened.saved).toHaveBeenCalledWith('101')
})

it('公司改变后的迟到响应不写入新公司，原公司可按原键确认', async () => {
  let resolve!: (value: SalesDocument) => void
  vi.mocked(api.saveSourcelessReturn).mockReturnValueOnce(
    new Promise((done) => {
      resolve = done
    }),
  )
  const state = panel()
  const write = state.value.saveReturn(input())
  session.companyId = '2'
  resolve(doc())
  await write
  await flushPromises()
  expect(state.saved).not.toHaveBeenCalled()
  expect(state.closed).toHaveBeenCalledTimes(1)
  state.unmount()
  session.companyId = '1'
  vi.mocked(sales.commandResult).mockResolvedValueOnce({ status: 'succeeded', response: doc() })
  const restored = panel()
  await restored.value.verify()
  expect(restored.saved).toHaveBeenCalledWith('101')
})

it('撤销办理权限后不能重试，旧请求仍可通过结果查询确认', async () => {
  vi.mocked(api.saveSourcelessReturn).mockRejectedValueOnce(offline())
  const state = panel()
  await state.value.saveReturn(input())
  denied.add('sales_return:create')
  await state.value.retry()
  expect(api.saveSourcelessReturn).toHaveBeenCalledTimes(1)
  expect(state.value.locked.value).toBe(true)
  vi.mocked(sales.commandResult).mockResolvedValueOnce({ status: 'succeeded', response: doc() })
  await state.value.verify()
  expect(state.saved).toHaveBeenCalledWith('101')
})

it('查看权限撤销后迟到的成功响应只解除原请求锁，不回填已无权查看的单据', async () => {
  let resolve!: (value: SalesDocument) => void
  vi.mocked(api.saveSourcelessReturn).mockReturnValueOnce(
    new Promise((done) => {
      resolve = done
    }),
  )
  const state = panel(),
    write = state.value.saveReturn(input())
  denied.add('sales_return:view')
  resolve(doc())
  await write
  expect(state.saved).not.toHaveBeenCalled()
  expect(state.closed).toHaveBeenCalledExactlyOnceWith()
  expect(state.value.locked.value).toBe(false)
})

it('未获成本权限不能开始带手填成本的新请求，也不留下空的未确认请求', async () => {
  denied.add('cost:view')
  const state = panel(),
    form = input()
  form.lines[0]!.manualUnitCost = '5'
  form.lines[0]!.manualCostReason = '历史台账'
  await state.value.saveReturn(form)
  expect(api.saveSourcelessReturn).not.toHaveBeenCalled()
  expect(state.value.locked.value).toBe(false)
})

it('工作流恢复使用业务单据编号，不把审批任务编号当成退货单', async () => {
  const task: WorkflowTask = {
    id: '601',
    instanceId: '701',
    nodeKey: 'review',
    nodeName: '成本复核',
    nodeKind: 'approver',
    assigneeId: session.user.id,
    status: 'pending',
    version: 3,
    createdAt: '2026-11-15',
    canHandle: true,
  }
  vi.mocked(workflow.workflowTaskAction).mockRejectedValueOnce(offline())
  const state = panel({ id: '501' })
  await state.value.review(task, 'approve', '核对成本')
  vi.mocked(sales.commandResult).mockResolvedValueOnce({
    status: 'succeeded',
    response: doc('601'),
  })
  await state.value.verify()
  expect(sales.commandResult).toHaveBeenCalledWith('workflow_task.approve', expect.any(String))
  expect(state.saved).toHaveBeenCalledExactlyOnceWith('501')
})

it('只读演示即使拥有编辑权限也不能创建、修改或提交', async () => {
  const state = panel({ readOnly: true, id: '501' })
  await state.value.saveReturn(input())
  await state.value.command('submit', { version: 1 })
  expect(api.saveSourcelessReturn).not.toHaveBeenCalled()
  expect(sales.salesCommand).not.toHaveBeenCalled()
  expect(state.value.can('create')).toBe(false)
})
