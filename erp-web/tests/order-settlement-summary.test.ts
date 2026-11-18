import { createPinia } from 'pinia'
import { nextTick } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { orderSettlementSummary, type OrderSettlementSummary } from '../src/api/order-related'
import OrderDetailOverview from '../src/components/OrderDetailOverview.vue'
import { useSessionStore } from '../src/stores/session'

vi.mock('../src/api/order-related', () => ({ orderSettlementSummary: vi.fn() }))
const query = vi.mocked(orderSettlementSummary)
const permissions = ['sales_order:view', 'settlement:view', 'shipment:view', 'receipt:view']

function open(grants = permissions) {
  const pinia = createPinia()
  const session = useSessionStore(pinia)
  session.user = {
    id: '9',
    username: 'reviewer',
    displayName: '复核员',
    mustChangePassword: false,
    permissions: grants,
    companies: ['1', '2'].map((id) => ({
      id,
      code: id,
      name: id,
      allWarehouses: true,
      warehouseIds: [],
    })),
  }
  session.selectCompany('1')
  const wrapper = mount(OrderDetailOverview, {
    props: { kind: 'sales-orders', order: { id: '71', version: 2, status: 'approved' } },
    global: {
      plugins: [pinia],
      stubs: { StatusTag: true, ElButton: { template: '<button><slot /></button>' } },
    },
  })
  return { session, wrapper }
}

describe('订单结算金额', () => {
  beforeEach(() => query.mockReset())

  it('受限范围显示服务端未清额，零保留两位，缺少资金单权限时隐藏已核销收款', async () => {
    query.mockResolvedValue({ scope: 'visible', allocatedCashAmount: null, openAmount: '0.00' })
    const { wrapper } = open(permissions.filter((grant) => grant !== 'receipt:view'))
    await flushPromises()
    expect(wrapper.get('[data-order-metric="open-amount"]').text()).toBe('0.00')
    expect(wrapper.find('[data-order-metric="allocated-cash"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('当前可见范围')
    wrapper.unmount()
  })

  it('已完成的旧公司请求无法覆盖新公司结果，权限收回后移除金额', async () => {
    let finish: ((value: OrderSettlementSummary) => void) | undefined
    query.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    query.mockResolvedValue({
      scope: 'company',
      allocatedCashAmount: '9007199254740993.01',
      openAmount: '12.34',
    })
    const { wrapper, session } = open()
    const oldSignal = query.mock.calls[0]![2]
    session.selectCompany('2')
    await flushPromises()
    expect(oldSignal.aborted).toBe(true)
    finish!({ scope: 'company', allocatedCashAmount: '999.99', openAmount: '999.99' })
    await flushPromises()
    expect(wrapper.get('[data-order-metric="allocated-cash"]').text()).toBe(
      '9,007,199,254,740,993.01',
    )
    expect(wrapper.get('[data-order-metric="open-amount"]').text()).toBe('12.34')
    session.user!.permissions = ['sales_order:view']
    await nextTick()
    expect(wrapper.find('[data-order-metric="open-amount"]').exists()).toBe(false)
    expect(wrapper.find('[data-order-metric="allocated-cash"]').exists()).toBe(false)
    expect(query).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('读取失败保留订单信息，重试成功后才显示数值', async () => {
    query.mockRejectedValueOnce(new Error('读取失败'))
    query.mockResolvedValue({ scope: 'company', allocatedCashAmount: '7.00', openAmount: '18.00' })
    const { wrapper } = open()
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toContain('结算金额暂时无法读取')
    expect(wrapper.find('[data-order-metric="open-amount"]').exists()).toBe(false)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-order-metric="open-amount"]').text()).toBe('18.00')
    wrapper.unmount()
  })
})
