import { expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import FinancialAmount from '../src/components/FinancialAmount.vue'

it.each([
  ['20.00', '+20.00'],
  ['-20.00', '−20.00'],
  ['9007199254740993.12', '+9,007,199,254,740,993.12'],
])('金额 %s 显示明确的变化方向并保留精度', (value, expected) => {
  const wrapper = mount(FinancialAmount, { props: { value, signed: true } })
  expect(wrapper.text()).toBe(expected)
  expect(wrapper.classes()).not.toContain('is-muted')
})

it.each(['0', '0.00', '-0.00'])('零金额 %s 弱化显示且不标为增加或减少', (value) => {
  const wrapper = mount(FinancialAmount, { props: { value, signed: true } })
  expect(wrapper.text()).toBe('0.00')
  expect(wrapper.classes()).toContain('is-muted')
})

it.each([undefined, null, '无权限'])('缺失或不可读取的金额不显示为零', (value) => {
  const wrapper = mount(FinancialAmount, { props: { value, signed: true } })
  expect(wrapper.text()).toBe('—')
  expect(wrapper.classes()).toContain('is-muted')
})

it('费用金额保留普通金额口径，默认不添加正号', () => {
  const wrapper = mount(FinancialAmount, { props: { value: '22.60' } })
  expect(wrapper.text()).toBe('22.60')
})
