import { describe, expect, it } from 'vitest'
import { orderDetailProgress } from '../src/utils/order-detail-progress'

describe('订单详情履约进度', () => {
  it('以实际发货、签收与可履约基础数量计算，并区分已确认往来结清与整单结清', () => {
    const state = orderDetailProgress('sales-orders', {
      status: 'approved',
      settlementStatus: 'full',
      lines: [
        { progress: { baseQty: '10', closedQty: '3', shippedQty: '6', signedQty: '4' } },
        { progress: { baseQty: '10', closedQty: '0', shippedQty: '4', signedQty: '4' } },
      ],
    })
    expect(state.quantities.map((item) => item.progress)).toMatchObject([
      { actual: '10', required: '17', percent: '58.8%', complete: false },
      { actual: '8', required: '17', percent: '47%', complete: false },
    ])
    expect(state.steps.map(({ key, state }) => [key, state])).toEqual([
      ['submit', 'done'],
      ['approve', 'done'],
      ['shipped', 'current'],
      ['signed', 'pending'],
      ['settle', 'pending'],
    ])
  })

  it('发完未签时进入签收步骤，完整签收后仍按结算状态判断', () => {
    const input = {
      status: 'closed',
      settlementStatus: 'partial',
      lines: [{ progress: { baseQty: '1', closedQty: '0', shippedQty: '1', signedQty: '0.5' } }],
    }
    expect(
      orderDetailProgress('sales-orders', input).steps.find((step) => step.state === 'current')
        ?.key,
    ).toBe('signed')
    input.lines[0]!.progress.signedQty = '1'
    expect(
      orderDetailProgress('sales-orders', input).steps.find((step) => step.state === 'current')
        ?.key,
    ).toBe('settle')
    expect(
      orderDetailProgress('sales-orders', { ...input, settlementStatus: 'full' }).steps.every(
        (step) => step.state === 'done',
      ),
    ).toBe(true)
  })

  it('采购保留超收实数，关闭量可超过订单数量但不产生负分母', () => {
    const input = {
      status: 'closed',
      settlementStatus: 'partial',
      lines: [{ progress: { baseQty: '10', closedQty: '0', receivedQty: '11' } }],
    }
    const full = orderDetailProgress('purchase-orders', input)
    expect(full.quantities[0]?.progress).toMatchObject({
      actual: '11',
      required: '10',
      percent: '100%',
    })
    const closed = orderDetailProgress('purchase-orders', {
      status: 'closed',
      settlementStatus: 'none',
      lines: [{ progress: { baseQty: '1', closedQty: '1.1', receivedQty: '0' } }],
    })
    expect(closed.quantities[0]?.progress).toMatchObject({
      actual: '0',
      required: '0',
      percent: null,
      skipped: true,
    })
    expect(closed.steps.find((step) => step.key === 'received')?.state).toBe('skipped')
    expect(closed.steps.some((step) => step.state === 'current')).toBe(false)
  })

  it('保留六位小数精度和小于千分之一的进度，部分事实缺失时不给出比例', () => {
    const input = {
      status: 'approved',
      lines: [
        {
          progress: {
            baseQty: '9007199254740993.000001',
            closedQty: '9007199254740993.000000',
            receivedQty: '0.000001',
          },
        },
      ],
    }
    expect(orderDetailProgress('purchase-orders', input).quantities[0]?.progress).toMatchObject({
      required: '0.000001',
      percent: '100%',
    })
    input.lines[0]!.progress.closedQty = '0'
    expect(orderDetailProgress('purchase-orders', input).quantities[0]?.progress?.percent).toBe(
      '<0.1%',
    )
    expect(
      orderDetailProgress('sales-orders', input).quantities.every((row) => row.progress === null),
    ).toBe(true)
  })

  it('待审核、变更待审核及取消状态不误指向下游操作', () => {
    const current = (status: string) =>
      orderDetailProgress('purchase-orders', { status }).steps.find(
        (step) => step.state === 'current',
      )
    expect(current('draft')?.key).toBe('submit')
    expect(current('pending')?.key).toBe('approve')
    expect(current('change_pending')?.label).toBe('审核变更')
    expect(current('cancelled')).toBeUndefined()
  })
})
