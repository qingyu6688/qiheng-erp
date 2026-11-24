import { beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { mount } from '@vue/test-utils'
import SalesReturnSourceTag from '../src/components/sales/SalesReturnSourceTag.vue'

const security = reactive({ cost: true })
vi.mock('../src/stores/session', () => ({
  useSessionStore: () => ({
    can: (permission: string): boolean => permission === 'cost:view' && security.cost,
  }),
}))

describe('退货模式与冻结成本来源标记', () => {
  beforeEach(() => {
    security.cost = true
  })

  it.each([
    ['average', '加权平均成本'],
    ['last_in', '最近入库成本'],
    ['manual', '人工复核成本'],
  ])('无原单退货显示冻结来源 %s', (costSource, label) => {
    const wrapper = mount(SalesReturnSourceTag, { props: { mode: 'sourceless', costSource } })
    expect(wrapper.text()).toContain('无原单退货')
    expect(wrapper.text()).toContain(label)
  })

  it('成本权限撤销后立即隐藏来源，模式保留', async () => {
    const wrapper = mount(SalesReturnSourceTag, {
      props: { mode: 'sourceless', costSource: 'manual' },
    })
    security.cost = false
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toBe('无原单退货')
  })

  it('按原单退货不显示新模式成本来源', () => {
    const wrapper = mount(SalesReturnSourceTag, {
      props: { mode: 'original', costSource: 'manual' },
    })
    expect(wrapper.text()).toBe('按原单退货')
  })

  it('旧数据及未知来源不显示原始字段', () => {
    expect(mount(SalesReturnSourceTag).text()).toBe('')
    expect(
      mount(SalesReturnSourceTag, {
        props: { mode: 'sourceless', costSource: '内部成本快照' },
      }).text(),
    ).toBe('无原单退货')
  })
})
