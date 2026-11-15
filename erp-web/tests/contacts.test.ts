import { beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { useSessionStore } from '../src/stores/session'
import RecordRelations from '../src/components/RecordRelations.vue'
import DataFields from '../src/components/DataFields.vue'
import type { FormField } from '../src/types/admin'

vi.mock('../src/api/admin', () => ({
  records: vi
    .fn()
    .mockResolvedValue({
      items: [{ id: '2', name: '联系人', email: 'work@example.test' }],
      nextCursor: null,
    }),
  record: vi.fn(),
  allRecords: vi.fn(),
  writeRecord: vi.fn(),
}))

beforeEach(() => setActivePinia(createPinia()))

async function contactFields(full: boolean): Promise<string[]> {
  const session = useSessionStore()
  session.user = {
    id: '1',
    username: 'sales',
    displayName: '业务员',
    mustChangePassword: false,
    permissions: [
      'partner:view',
      'partner_contact:create',
      ...(full ? ['partner_contact:view'] : []),
    ],
    companies: [],
  }
  const wrapper = shallowMount(RecordRelations, {
    props: { resource: 'partners', row: { id: '3', name: '客户' } },
    global: {
      stubs: {
        RecordFrame: {
          template: '<section><slot /><footer><slot name="footer" /></footer></section>',
        },
        'el-dialog': { template: '<section><slot /></section>' },
        'el-table': { template: '<div />' },
        'el-table-column': true,
        'el-popconfirm': true,
        'el-alert': true,
        'el-button': true,
        'el-option': true,
        'el-select': true,
        'el-input': true,
        'el-checkbox': true,
      },
    },
  })
  await flushPromises()
  const keys = (wrapper.findComponent(DataFields).props('fields') as FormField[]).map((f) => f.key)
  wrapper.unmount()
  return keys
}

it('无联系方式权限时联系人表单保留姓名邮箱并省略电话地址', async () => {
  const keys = await contactFields(false)
  expect(keys).toContain('name')
  expect(keys).toContain('email')
  expect(keys).not.toContain('mobile')
  expect(keys).not.toContain('address')
})

it('具有联系方式权限时允许维护电话地址', async () => {
  expect(await contactFields(true)).toEqual(expect.arrayContaining(['mobile', 'address']))
})
