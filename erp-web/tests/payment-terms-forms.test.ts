import { beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import RecordEditor from '../src/components/RecordEditor.vue'
import DataFields from '../src/components/DataFields.vue'
import { resourceSpecs } from '../src/pages/resources'
import * as admin from '../src/api/admin'

vi.mock('../src/api/admin')
vi.mock('../src/api/platform-rules', () => ({ customFields: async () => [] }))
const stubs = {
  RecordFrame: { template: '<div><slot/><slot name="footer"/></div>' },
  DataFields: defineComponent({ name: 'DataFields', emits: ['change'], template: '<div />' }),
  ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot/></button>' },
  ElAlert: { props: ['title'], template: '<p>{{title}}</p>' },
}

beforeEach(() => {
  vi.resetAllMocks()
  setActivePinia(createPinia())
  vi.mocked(admin.allRecords).mockResolvedValue([])
  vi.mocked(admin.writeRecord).mockResolvedValue({ id: '1' })
})

it('保存往来单位时提交各角色账期，移除角色时不会保留该角色的账期参数', async () => {
  const wrapper = mount(RecordEditor, { props: { spec: resourceSpecs.partners!, row: {
    id: '1', code: 'P1', name: '往来单位', version: 1, roles: ['customer', 'supplier'],
    paymentTerms: { customer: { type: 'days', value: 30 }, supplier: { type: 'monthly', value: 28 } },
  } }, global: { stubs } })
  await flushPromises()
  await wrapper.findAll('.payment-term-field select')[0]!.setValue('days')
  await wrapper.get('input[aria-label="客户账期天数"]').setValue('7')
  wrapper.findComponent(DataFields).vm.$emit('change', 'roles', ['customer'])
  await flushPromises()
  const save = wrapper.findAll('button').find(button => button.text() === '保存')!
  await save.trigger('click')
  await flushPromises()
  expect(vi.mocked(admin.writeRecord).mock.calls[0]?.[1]).toMatchObject({
    roles: ['customer'], paymentTerms: { customer: { type: 'days', value: 7 } },
  })
  expect((vi.mocked(admin.writeRecord).mock.calls[0]?.[1] as Record<string, unknown>).paymentTerms)
    .not.toHaveProperty('supplier')
  wrapper.unmount()
})
