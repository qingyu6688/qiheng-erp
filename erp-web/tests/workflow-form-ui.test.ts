import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { ElForm, ElFormItem, ElInput } from 'element-plus'
import { describe, expect, it } from 'vitest'
import WorkflowFormFields from '../src/components/workflow/WorkflowFormFields.vue'

describe('通用审批表单渲染', () => {
  it('以白名单规则渲染真实表单，并保留金额的十进制文本精度', async () => {
    const wrapper = mount(WorkflowFormFields, {
      props: {
        modelValue: { amount: '10.20' },
        fields: [{ key: 'amount', label: '金额', type: 'number', required: true }],
      },
      global: { components: { ElForm, ElFormItem, ElInput } },
    })
    await nextTick()
    expect(wrapper.text()).toContain('金额')
    const input = wrapper.get('input')
    expect(input.element.value).toBe('10.20')
    await input.setValue('19.05')
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toEqual({ amount: '19.05' })
    wrapper.unmount()
  })
})
