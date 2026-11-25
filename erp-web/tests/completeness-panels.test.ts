import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { defineComponent, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { ApiError } from '../src/api/http'
import * as costs from '../src/api/cost-revaluations'
import * as quotes from '../src/api/sales-quotes'
import { useCostRevaluationPanel } from '../src/hooks/useCostRevaluationPanel'
import type { CostRevaluationPanel } from '../src/hooks/useCostRevaluationPanel'
import { useSalesQuotePanel } from '../src/hooks/useSalesQuotePanel'
import type { SalesQuotePanel } from '../src/hooks/useSalesQuotePanel'
import type { CostRevaluation, CostRevaluationMetadata } from '../src/types/cost-revaluations'
import type { QuoteMetadata, QuotePricing, SalesQuote } from '../src/types/sales-quotes'
import { confirmCompanyLeave } from '../src/utils/unsaved'

vi.mock('../src/api/cost-revaluations')
vi.mock('../src/api/sales-quotes')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: vi.fn().mockResolvedValue('confirm') } }))
const denied = reactive(new Set<string>())
const session = reactive({ companyId: '1', user: { id: '1' }, can: (permission: string): boolean => !denied.has(permission) })
const wrappers: ReturnType<typeof mount>[] = []
let user = 1000

function costDoc(): CostRevaluation {
  return { id: '9007199254740993', documentNo: 'CR-验收', status: 'approved', warehouseId: '21', warehouseName: '主仓', skuId: '31', skuName: '商品',
    startDate: '2026-11-01', businessDate: '2026-11-24', reason: '历史成本核对', quantity: '9', beforeCost: '140.00', afterCost: '120.00',
    stockDelta: '-20.00', issuedDelta: '20.00', eventCount: 3, version: 3, previewHash: '冻结摘要', wfInstanceId: '77', allowedActions: ['post', 'refresh'], lines: [] }
}
function pricing(): QuotePricing {
  return { priceTaxInclusive: true, goodsAmount: '43.20', orderDiscountAmount: '1', lineDiscountAmount: '1', feeAmount: '0', totalAmount: '43.20',
    untaxedAmount: '38.23', taxAmount: '4.97', lines: [], warnings: [] }
}
function quoteDoc(): SalesQuote {
  return { id: '9007199254740995', documentNo: 'SQ-验收', customerId: '51', customerName: '设备客户', businessDate: '2026-11-15', validTo: '2026-11-30', status: 'issued',
    version: 4, totalAmount: '43.20', input: { customerId: '51', businessDate: '2026-11-15', priceTaxInclusive: true,
      lines: [{ skuId: '31', unitId: '41', warehouseId: '21', qty: '1.000001', unitPrice: '1234567890123456.12' }] },
    pricing: pricing(), opportunityId: '71', opportunityName: '设备采购', wfInstanceId: '', belowMinPrice: false, belowMinPricePolicy: 'warn',
    allowedActions: ['update', 'convert', 'void'], lines: [{ id: '61', lineNo: 1, skuId: '31', skuName: '商品', unitId: '41', unitName: '件' }] }
}
const metadata = (): QuoteMetadata => ({ customers: [{ id: '51', name: '设备客户' }], skus: [{ id: '31', name: '商品' }], warehouses: [{ id: '21', name: '主仓' }], opportunities: [{ id: '71', name: '设备采购' }] })
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}
function panel<T>(setup: (saved: (id: string) => void, closed: () => void) => T): { value: T; saved: ReturnType<typeof vi.fn>; closed: ReturnType<typeof vi.fn>; unmount: () => void } {
  let value!: T
  const saved = vi.fn(), closed = vi.fn()
  const wrapper = mount(defineComponent({ setup() { value = setup(saved, closed); return () => null } }))
  wrappers.push(wrapper)
  return { value, saved, closed, unmount: () => wrapper.unmount() }
}
function costPanel(id?: string, readOnly = false): ReturnType<typeof panel<CostRevaluationPanel>> {
  return panel((saved, closed) => useCostRevaluationPanel(() => id, saved, closed, () => readOnly))
}
function quotePanel(id?: string, readOnly = false): ReturnType<typeof panel<SalesQuotePanel>> {
  return panel((saved, closed) => useSalesQuotePanel(() => id, saved, closed, () => readOnly))
}
function fillCost(value: CostRevaluationPanel): void { Object.assign(value.draft, { warehouseId: '21', skuId: '31', startDate: '2026-11-01', businessDate: '2026-11-24', reason: '历史成本核对' }) }
function fillQuote(value: SalesQuotePanel): void { value.draft.value = { validTo: '2026-11-30', order: quoteDoc().input } }
const offline = (): ApiError => new ApiError(0, 'NETWORK_ERROR', '网络中断，结果尚未确认')

beforeEach(() => {
  vi.resetAllMocks(); denied.clear(); session.companyId = '1'; session.user.id = String(++user)
  vi.mocked(costs.costRevaluation).mockResolvedValue(costDoc())
  vi.mocked(costs.costRevaluationMetadata).mockResolvedValue({ warehouses: [], skus: [], skuLimit: 200 })
  vi.mocked(quotes.salesQuote).mockResolvedValue(quoteDoc())
  vi.mocked(quotes.quoteMetadata).mockResolvedValue(metadata())
  vi.mocked(quotes.quoteUnits).mockResolvedValue([{ unitId: '41', unitName: '件', rateToBase: '1', isBase: true }])
  vi.mocked(quotes.quotePreview).mockResolvedValue({ pricing: pricing() })
})
afterEach(() => { for (const wrapper of wrappers.splice(0)) wrapper.unmount() })

it('成本新建请求结果未知时冻结参数和原键，核对404后仍禁止重新提交', async () => {
  vi.mocked(costs.createCostRevaluation).mockRejectedValueOnce(offline()).mockResolvedValueOnce(costDoc())
  vi.mocked(costs.costRevaluationResult).mockRejectedValue(new ApiError(404, 'NOT_FOUND', '尚未查到结果'))
  const state = costPanel(); await flushPromises(); fillCost(state.value)
  await state.value.save()
  const first = vi.mocked(costs.createCostRevaluation).mock.calls[0]!
  state.value.draft.reason = '已改变的原因'
  await state.value.verify(); await state.value.save()
  expect(state.value.locked.value).toBe(true); expect(costs.createCostRevaluation).toHaveBeenCalledTimes(1)
  expect(await confirmCompanyLeave()).toBe(false)
  await state.value.retry()
  expect(costs.createCostRevaluation).toHaveBeenLastCalledWith(expect.objectContaining({ reason: '历史成本核对' }), first[1], expect.any(AbortSignal))
  expect(state.value.document.value?.id).toBe('9007199254740993')
})

it('成本任务创建后未确认的过账请求可按实际任务编号恢复', async () => {
  vi.mocked(costs.createCostRevaluation).mockResolvedValue(costDoc())
  vi.mocked(costs.commandCostRevaluation).mockRejectedValue(offline())
  const state = costPanel(); await flushPromises(); fillCost(state.value); await state.value.save(); await state.value.act('post')
  const first = vi.mocked(costs.commandCostRevaluation).mock.calls[0]!
  state.unmount()
  const reopened = costPanel(costDoc().id); await flushPromises()
  expect(reopened.value.pending.value?.key).toBe(first[3])
  expect(reopened.value.pending.value?.input).toEqual({ version: 3 })
  expect(reopened.value.locked.value).toBe(true)
})

it('成本只读抽屉禁止创建、提交、执行及重试', async () => {
  const state = costPanel(costDoc().id, true); await flushPromises()
  state.value.workflowReady.value = true
  await state.value.act('post'); await state.value.submit(); await state.value.save(); await state.value.retry()
  expect(costs.commandCostRevaluation).not.toHaveBeenCalled(); expect(costs.createCostRevaluation).not.toHaveBeenCalled()
})

it('成本失去权限后迟到的写响应不能恢复单据或触发保存回调', async () => {
  const wait = deferred<CostRevaluation>(); vi.mocked(costs.createCostRevaluation).mockReturnValue(wait.promise)
  const state = costPanel(); await flushPromises(); fillCost(state.value); const saving = state.value.save()
  denied.add('cost:view'); await flushPromises(); wait.resolve(costDoc()); await saving
  expect(state.value.document.value).toBeUndefined(); expect(state.saved).not.toHaveBeenCalled(); expect(state.closed).toHaveBeenCalled()
})

it('成本切换公司后迟到的商品搜索不能回填旧公司选项', async () => {
  const state = costPanel(); await flushPromises()
  const wait = deferred<CostRevaluationMetadata>(); vi.mocked(costs.costRevaluationMetadata).mockReturnValue(wait.promise)
  const searching = state.value.searchSkus('商品'); session.companyId = '2'; await flushPromises()
  wait.resolve({ warehouses: [{ id: '21', name: '旧公司主仓' }], skus: [{ id: '31', name: '旧公司商品' }], skuLimit: 200 }); await searching
  expect(state.value.metadata.value.skus).toEqual([]); expect(state.value.metadata.value.warehouses).toEqual([])
})

it('成本详情403后清除内容并关闭抽屉', async () => {
  vi.mocked(costs.costRevaluation).mockRejectedValue(new ApiError(403, 'FORBIDDEN', '查看权限已撤销'))
  const state = costPanel(costDoc().id); await flushPromises()
  expect(state.value.document.value).toBeUndefined(); expect(state.closed).toHaveBeenCalledOnce()
})

it('报价未知结果重试保留精确金额、数量和原请求键', async () => {
  vi.mocked(quotes.saveSalesQuote).mockRejectedValueOnce(offline()).mockResolvedValueOnce(quoteDoc())
  const state = quotePanel(); await flushPromises(); fillQuote(state.value); await state.value.save()
  const first = vi.mocked(quotes.saveSalesQuote).mock.calls[0]!
  state.value.draft.value.order.lines[0]!.qty = '9'; await state.value.save()
  expect(quotes.saveSalesQuote).toHaveBeenCalledTimes(1); expect(await confirmCompanyLeave()).toBe(false)
  await state.value.retry()
  expect(quotes.saveSalesQuote).toHaveBeenLastCalledWith(expect.objectContaining({ order: expect.objectContaining({ lines: [expect.objectContaining({ qty: '1.000001', unitPrice: '1234567890123456.12' })] }) }), first[1], undefined, expect.any(AbortSignal))
})

it('报价编辑保留已选商机，并在搜索结果中保留原选商品和客户名称', async () => {
  const state = quotePanel(quoteDoc().id); await flushPromises()
  vi.mocked(quotes.quoteMetadata).mockResolvedValue({ customers: [], warehouses: [], skus: [], opportunities: [] })
  await state.value.edit()
  expect(state.value.draft.value.opportunityId).toBe('71')
  expect(state.value.metadata.value.customers).toContainEqual({ id: '51', name: '设备客户' })
  expect(state.value.metadata.value.skus).toContainEqual({ id: '31', name: '商品' })
})

it('报价保存后忽略迟到的旧价格预览', async () => {
  const wait = deferred<{ pricing: QuotePricing }>(); vi.mocked(quotes.quotePreview).mockReturnValue(wait.promise)
  vi.mocked(quotes.saveSalesQuote).mockResolvedValue(quoteDoc())
  const state = quotePanel(); await flushPromises(); fillQuote(state.value); await flushPromises()
  const calculating = state.value.calculate(); await state.value.save()
  wait.resolve({ pricing: { ...pricing(), totalAmount: '999.00' } }); await calculating
  expect(state.value.pricing.value?.totalAmount).toBe('43.20'); expect(state.value.editing.value).toBe(false)
})

it('报价只读预览禁止修改、确认和转单', async () => {
  const state = quotePanel(quoteDoc().id, true); await flushPromises()
  await state.value.edit(); await state.value.act('convert'); await state.value.submit(); await state.value.save()
  expect(state.value.editing.value).toBe(false)
  expect(quotes.commandSalesQuote).not.toHaveBeenCalled(); expect(quotes.saveSalesQuote).not.toHaveBeenCalled()
})

it('报价缺少销售订单创建权限时不能转单', async () => {
  denied.add('sales_order:create')
  const state = quotePanel(quoteDoc().id); await flushPromises(); await state.value.act('convert')
  expect(quotes.commandSalesQuote).not.toHaveBeenCalled()
})

it('低价报价的公司规则已改为禁止时，不再发送确认生效请求', async () => {
  vi.mocked(quotes.salesQuote).mockResolvedValue({ ...quoteDoc(), status: 'draft', belowMinPrice: true, belowMinPricePolicy: 'block', allowedActions: ['update', 'submit'] })
  const state = quotePanel(quoteDoc().id); await flushPromises(); await state.value.submit()
  expect(quotes.commandSalesQuote).not.toHaveBeenCalled()
})

it('报价失权后迟到的写响应不能恢复单据和价格', async () => {
  const wait = deferred<SalesQuote>(); vi.mocked(quotes.saveSalesQuote).mockReturnValue(wait.promise)
  const state = quotePanel(); await flushPromises(); fillQuote(state.value); const saving = state.value.save()
  denied.add('sales_quote:view'); await flushPromises(); wait.resolve(quoteDoc()); await saving
  expect(state.value.document.value).toBeUndefined(); expect(state.value.pricing.value).toBeUndefined(); expect(state.saved).not.toHaveBeenCalled()
})

it('报价切换公司清除选项和价格，并丢弃迟到的旧公司选项', async () => {
  const state = quotePanel(); await flushPromises()
  state.value.pricing.value = pricing()
  const wait = deferred<QuoteMetadata>(); vi.mocked(quotes.quoteMetadata).mockReturnValue(wait.promise)
  const loading = state.value.loadMetadata('设备'); session.companyId = '2'; await flushPromises(); wait.resolve(metadata()); await loading
  expect(state.value.metadata.value.customers).toEqual([]); expect(state.value.pricing.value).toBeUndefined(); expect(state.value.units.value).toEqual({})
})

it('报价详情404后清除原单并关闭预览', async () => {
  vi.mocked(quotes.salesQuote).mockRejectedValue(new ApiError(404, 'NOT_FOUND', '报价已不在当前范围'))
  const state = quotePanel(quoteDoc().id); await flushPromises()
  expect(state.value.document.value).toBeUndefined(); expect(state.closed).toHaveBeenCalledOnce()
})
