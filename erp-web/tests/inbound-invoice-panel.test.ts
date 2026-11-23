import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import * as api from '../src/api/e-invoice'
import { ApiError } from '../src/api/http'
import { useInboundInvoicePanel } from '../src/hooks/useInboundInvoicePanel'
import { confirmCompanyLeave, hasUnsavedWork } from '../src/utils/unsaved'
import { invoice, match, preview, source } from './fixtures/inbound-invoices'
import type { InboundInvoiceDetail, InboundSupplier } from '../src/types/inbound-invoices'

const routeGuards = vi.hoisted(() => [] as (() => boolean)[])
const messageBox = vi.hoisted(() => ({ confirm: vi.fn<() => Promise<string>>() }))
vi.mock('../src/api/e-invoice')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
vi.mock('element-plus', () => ({ ElMessageBox: messageBox }))
vi.mock('vue-router', () => ({
  onBeforeRouteLeave: (guard: () => boolean) => routeGuards.push(guard),
}))
const session = reactive<{ companyId: string | null; can: (permission: string) => boolean }>({
  companyId: '1',
  can: () => true,
})
const wrappers: ReturnType<typeof mount>[] = []
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  return {
    promise: new Promise<T>((done) => {
      resolve = done
    }),
    resolve: (value) => resolve(value),
  }
}
function panel(id?: string): {
  panel: ReturnType<typeof useInboundInvoicePanel>
  saved: ReturnType<typeof vi.fn>
  closed: ReturnType<typeof vi.fn>
} {
  let state!: ReturnType<typeof useInboundInvoicePanel>
  const saved = vi.fn(),
    closed = vi.fn()
  wrappers.push(
    mount(
      defineComponent({
        setup() {
          state = useInboundInvoicePanel({ id }, saved, closed)
          return () => null
        },
      }),
    ),
  )
  return { panel: state, saved, closed }
}
function fill(state: ReturnType<typeof useInboundInvoicePanel>): void {
  Object.assign(state.draft, {
    supplierId: '11',
    invoiceNo: 'PAPER-1',
    sellerTaxNo: 'TEST12345678',
    sellerName: '验收供应商',
    invoiceDate: '2026-11-23',
    receivedDate: '2026-11-23',
    amount: '113.00',
    taxAmount: '13.00',
  })
}
beforeEach(() => {
  vi.resetAllMocks()
  routeGuards.length = 0
  session.companyId = '1'
  session.can = () => true
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-11-23T02:00:00Z'))
  vi.spyOn(window, 'confirm').mockReturnValue(false)
  messageBox.confirm.mockResolvedValue('confirm')
  vi.mocked(api.inboundInvoice).mockResolvedValue(invoice())
  vi.mocked(api.inboundSuppliers).mockResolvedValue({
    items: [{ id: '11', code: 'SUP', name: '验收供应商', taxNo: 'TEST12345678' }],
    nextCursor: null,
  })
  vi.mocked(api.inboundSources).mockResolvedValue({ items: [source()], nextCursor: null })
  vi.mocked(api.previewInboundInvoice).mockResolvedValue(preview())
  vi.mocked(api.saveInboundInvoice).mockResolvedValue(invoice())
  vi.mocked(api.allocateInboundInvoice).mockResolvedValue(invoice({ version: 1 }))
  vi.mocked(api.voidInboundInvoice).mockResolvedValue(invoice({ version: 1, status: 'voided' }))
})
afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
  expect(hasUnsavedWork.value).toBe(false)
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('进项登记与未知结果', () => {
  it('手工登记不依赖网关和资料查看权限，保存确认后重新读取当前详情', async () => {
    session.can = (permission) => permission !== 'partner:view' && permission !== 'einv:manage'
    const state = panel()
    await flushPromises()
    expect(state.panel.suppliers.value).toHaveLength(1)
    state.panel.chooseSupplier('11')
    fill(state.panel)
    vi.mocked(api.inboundInvoice).mockResolvedValue(
      invoice({ version: 3, matchNotice: '来源已变化，请重新核对' }),
    )
    await state.panel.save()
    expect(api.invoiceProvider).not.toHaveBeenCalled()
    expect(api.saveInboundInvoice).toHaveBeenCalledWith(
      expect.objectContaining({ supplierId: '11', invoiceNo: 'PAPER-1' }),
      expect.any(String),
      undefined,
      expect.any(AbortSignal),
    )
    expect(state.panel.document.value?.version).toBe(3)
    expect(state.panel.mode.value).toBe('view')
    expect(state.saved).toHaveBeenCalledWith('21')
  })
  it('状态0网络失败保留原载荷和幂等键，阻止重复创建、关闭、切换公司及离开页面', async () => {
    vi.mocked(api.saveInboundInvoice)
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络未响应'))
      .mockResolvedValueOnce(invoice())
    const state = panel()
    await flushPromises()
    fill(state.panel)
    await state.panel.save()
    const first = vi.mocked(api.saveInboundInvoice).mock.calls[0]!
    expect(state.panel.pending.value).toBe(true)
    expect(state.panel.locked.value).toBe(true)
    expect(confirmCompanyLeave()).toBe(false)
    expect(routeGuards[0]!()).toBe(false)
    const unloading = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unloading)
    expect(unloading.defaultPrevented).toBe(true)
    await state.panel.close()
    expect(state.closed).not.toHaveBeenCalled()
    state.panel.draft.invoiceNo = '不应进入原请求'
    await state.panel.save()
    expect(api.saveInboundInvoice).toHaveBeenCalledTimes(1)
    await state.panel.retryWrite()
    const second = vi.mocked(api.saveInboundInvoice).mock.calls[1]!
    expect(second[0]).toEqual(first[0])
    expect(second[0].invoiceNo).toBe('PAPER-1')
    expect(second[1]).toBe(first[1])
    expect(state.panel.pending.value).toBe(false)
    expect(state.saved).toHaveBeenCalledTimes(1)
  })
  it('明确业务拒绝解除请求锁，保留录入供修改，未保存内容仍有离开保护', async () => {
    vi.mocked(api.saveInboundInvoice).mockRejectedValue(
      new ApiError(422, 'INVOICE_SUPPLIER_MISMATCH', '票面税号不一致'),
    )
    const state = panel()
    await flushPromises()
    fill(state.panel)
    await state.panel.save()
    expect(state.panel.pending.value).toBe(false)
    expect(state.panel.mode.value).toBe('manual')
    expect(state.panel.draft.invoiceNo).toBe('PAPER-1')
    expect(state.panel.error.value).toBe('票面税号不一致')
    messageBox.confirm.mockRejectedValueOnce('cancel')
    await state.panel.close()
    expect(state.closed).not.toHaveBeenCalled()
    expect(confirmCompanyLeave()).toBe(false)
  })
  it('已确认写入而详情刷新失败时锁住后续编辑，刷新恢复而不重复提交', async () => {
    vi.mocked(api.inboundInvoice)
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '详情暂不可用'))
      .mockResolvedValueOnce(invoice({ version: 5 }))
    const state = panel()
    await flushPromises()
    fill(state.panel)
    await state.panel.save()
    expect(state.panel.pending.value).toBe(false)
    expect(state.panel.refreshNeeded.value).toBe(true)
    expect(state.panel.canMatch.value).toBe(false)
    expect(state.panel.notice.value).toContain('提交已确认')
    await state.panel.retryWrite()
    expect(api.saveInboundInvoice).toHaveBeenCalledTimes(1)
    await state.panel.load()
    expect(state.panel.refreshNeeded.value).toBe(false)
    expect(state.panel.document.value?.version).toBe(5)
  })
})

describe('多对多分配与历史', () => {
  it('空候选页仍可翻页，按来源余额处理尾差，修改后必须重新预览', async () => {
    vi.mocked(api.inboundInvoice).mockResolvedValue(
      invoice({ amount: '112.99', untaxedAmount: '99.99' }),
    )
    vi.mocked(api.inboundSources)
      .mockResolvedValueOnce({ items: [], nextCursor: '40' })
      .mockResolvedValueOnce({ items: [source()], nextCursor: null })
    const state = panel('21')
    await flushPromises()
    await state.panel.editMatching()
    expect(state.panel.sourceCursor.value).toBe('40')
    expect(state.panel.sources.value).toHaveLength(0)
    await state.panel.loadSources(true)
    expect(vi.mocked(api.inboundSources).mock.calls[1]![1]).toBe('40')
    state.panel.addSource(source())
    state.panel.coverSource('41')
    expect(state.panel.lines.value[0]).toMatchObject({
      invoiceUntaxedAmount: '99.99',
      payableUntaxedAmount: '100.00',
    })
    vi.mocked(api.previewInboundInvoice).mockResolvedValue(
      preview({
        allocatedUntaxedAmount: '99.99',
        untaxedDifference: '-0.01',
        absoluteDifference: '0.01',
        matchStatus: 'difference',
      }),
    )
    await state.panel.previewMatching()
    expect(state.panel.previewCurrent.value).toBe(true)
    await state.panel.loadSources()
    expect(state.panel.previewCurrent.value).toBe(false)
    await state.panel.previewMatching()
    state.panel.lines.value[0]!.invoiceUntaxedAmount = '99.98'
    expect(state.panel.previewCurrent.value).toBe(false)
    await state.panel.save()
    expect(api.allocateInboundInvoice).not.toHaveBeenCalled()
    await state.panel.previewMatching()
    await state.panel.save()
    expect(api.allocateInboundInvoice).toHaveBeenCalledWith(
      '21',
      expect.objectContaining({
        version: 0,
        lines: [
          expect.objectContaining({
            invoiceUntaxedAmount: '99.98',
            payableUntaxedAmount: '100.00',
          }),
        ],
      }),
      expect.any(String),
      expect.any(AbortSignal),
    )
  })
  it('数电票首次选择绑定同一供应商，不能混入相同税号下的其他供应商来源', async () => {
    vi.mocked(api.inboundInvoice).mockResolvedValue(invoice({ source: 'einv', supplierId: null }))
    const state = panel('21')
    await flushPromises()
    await state.panel.editMatching()
    state.panel.addSource(source())
    state.panel.addSource(source({ id: '42', supplierId: '12' }))
    expect(state.panel.lines.value).toHaveLength(1)
    expect(state.panel.error.value).toContain('同一供应商')
    state.panel.removeSource('41')
    state.panel.addSource(source({ id: '42', supplierId: '12' }))
    expect(state.panel.selectionSupplier.value).toBe('12')
  })
  it('解除核对的503结果保留原版本和空分配，重试后加载已释放历史', async () => {
    vi.mocked(api.inboundInvoice)
      .mockResolvedValueOnce(invoice({ version: 3, allocations: [match()] }))
      .mockResolvedValueOnce(
        invoice({
          version: 5,
          history: {
            items: [match({ releasedReason: '手工解除核对', releasedAt: '2026-11-23T02:00:00Z' })],
            nextCursor: null,
          },
        }),
      )
    vi.mocked(api.allocateInboundInvoice)
      .mockRejectedValueOnce(new ApiError(503, 'UNAVAILABLE', '连接中断'))
      .mockResolvedValueOnce(invoice({ version: 4 }))
    const state = panel('21')
    await flushPromises()
    await state.panel.clear()
    expect(state.panel.pending.value).toBe(true)
    await state.panel.clear()
    expect(messageBox.confirm).toHaveBeenCalledTimes(1)
    await state.panel.retryWrite()
    const calls = vi.mocked(api.allocateInboundInvoice).mock.calls
    expect(calls[0]![1]).toEqual({ version: 3, lines: [] })
    expect(calls[1]![1]).toEqual(calls[0]![1])
    expect(calls[1]![2]).toBe(calls[0]![2])
    expect(state.panel.document.value?.version).toBe(5)
    expect(state.panel.history.value[0]?.releasedReason).toBe('手工解除核对')
  })
  it('版本冲突清除旧预览，刷新前不能按旧版本再写', async () => {
    const state = panel('21')
    await flushPromises()
    await state.panel.editMatching()
    state.panel.addSource(source())
    await state.panel.previewMatching()
    vi.mocked(api.allocateInboundInvoice).mockRejectedValue(
      new ApiError(409, 'VERSION_CONFLICT', '发票已变化'),
    )
    await state.panel.save()
    expect(state.panel.pending.value).toBe(false)
    expect(state.panel.refreshNeeded.value).toBe(true)
    expect(state.panel.previewCurrent.value).toBe(false)
    await state.panel.save()
    expect(api.allocateInboundInvoice).toHaveBeenCalledTimes(1)
  })
  it('数电来源和作废票隐藏手工更正及作废入口，不能提交越权操作', async () => {
    vi.mocked(api.inboundInvoice).mockResolvedValue(invoice({ source: 'einv' }))
    const state = panel('21')
    await flushPromises()
    expect(state.panel.canEdit.value).toBe(false)
    expect(state.panel.canVoid.value).toBe(false)
    await state.panel.editManual()
    await state.panel.voidInvoice('错误操作')
    expect(api.voidInboundInvoice).not.toHaveBeenCalled()
    expect(state.panel.mode.value).toBe('view')
    vi.mocked(api.inboundInvoice).mockResolvedValue(invoice({ status: 'voided' }))
    await state.panel.load()
    expect(state.panel.canMatch.value).toBe(false)
  })
})

describe('读取顺序与作用域', () => {
  it('切换公司后旧详情不能回填，旧公司请求已取消', async () => {
    const old = deferred<InboundInvoiceDetail>()
    vi.mocked(api.inboundInvoice).mockReturnValueOnce(old.promise)
    const state = panel('21')
    await flushPromises()
    const signal = vi.mocked(api.inboundInvoice).mock.calls[0]![1]
    session.companyId = '2'
    await flushPromises()
    old.resolve(invoice({ sellerName: '旧公司数据' }))
    await flushPromises()
    expect(signal?.aborted).toBe(true)
    expect(state.closed).toHaveBeenCalled()
    expect(state.panel.document.value).toBeUndefined()
  })
  it('供应商旧搜索和历史旧分页不能覆盖已刷新的状态', async () => {
    const oldSupplier = deferred<{ items: InboundSupplier[]; nextCursor: string | null }>()
    const state = panel('21')
    await flushPromises()
    await state.panel.editManual()
    vi.mocked(api.inboundSuppliers)
      .mockReturnValueOnce(oldSupplier.promise)
      .mockResolvedValueOnce({
        items: [{ id: '12', code: 'NEW', name: '新检索', taxNo: null }],
        nextCursor: null,
      })
    const oldRequest = state.panel.loadSuppliers('OLD')
    await state.panel.loadSuppliers('NEW')
    oldSupplier.resolve({
      items: [{ id: '13', code: 'OLD', name: '旧检索', taxNo: null }],
      nextCursor: null,
    })
    await oldRequest
    expect(state.panel.suppliers.value[0]?.id).toBe('12')
    await state.panel.cancelEdit()
    const oldHistory = deferred<{ items: ReturnType<typeof match>[]; nextCursor: string | null }>()
    vi.mocked(api.inboundInvoice).mockResolvedValue(
      invoice({ history: { items: [], nextCursor: '71' } }),
    )
    await state.panel.load()
    vi.mocked(api.inboundHistory).mockReturnValueOnce(oldHistory.promise)
    const historyRequest = state.panel.loadHistory()
    vi.mocked(api.inboundInvoice).mockResolvedValue(invoice({ version: 2 }))
    await state.panel.load()
    oldHistory.resolve({ items: [match({ releasedReason: '旧分页' })], nextCursor: null })
    await historyRequest
    expect(state.panel.history.value).toHaveLength(0)
    expect(state.panel.document.value?.version).toBe(2)
  })
})
