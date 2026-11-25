import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PaymentTermsField from '../src/components/PaymentTermsField.vue'
import { paymentTermLabel, readPaymentTerm } from '../src/utils/payment-terms'

describe('账期设置', () => {
  it('区分现结、按天、月结及未设账期', () => {
    expect(paymentTermLabel('days', 0)).toBe('现结')
    expect(paymentTermLabel('days', 30)).toBe('业务日期后 30 天')
    expect(paymentTermLabel('monthly', 28)).toBe('月结次月 28 日')
    expect(paymentTermLabel(null, null)).toBe('未设账期')
    expect(readPaymentTerm(undefined)).toEqual({ type: null, value: null })
  })

  it('可切换继承、按天、月结和不设账期，并限制输入范围', async () => {
    const wrapper = mount(PaymentTermsField, { props: { label: '订单账期', allowInherit: true, modelValue: null } })
    await wrapper.get('select').setValue('monthly')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([{ type: 'monthly', value: 1 }])
    await wrapper.setProps({ modelValue: { type: 'monthly', value: 1 } })
    expect(wrapper.get('input').attributes()).toMatchObject({ min: '1', max: '28' })
    await wrapper.get('input').setValue('28')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([{ type: 'monthly', value: 28 }])
    await wrapper.get('select').setValue('days')
    await wrapper.setProps({ modelValue: { type: 'days', value: 0 } })
    expect(wrapper.get('input').attributes()).toMatchObject({ min: '0', max: '365' })
    await wrapper.get('select').setValue('none')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([{ type: null, value: null }])
    await wrapper.get('select').setValue('inherit')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([null])
  })
})
