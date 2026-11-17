import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import * as api from '../src/api/org'
import { ApiError } from '../src/api/http'
import { useSessionStore } from '../src/stores/session'
import RoleDataScopeEditor from '../src/components/org/RoleDataScopeEditor.vue'
import type { RoleDataScope } from '../src/types/org'

vi.mock('../src/api/org')
const field = defineComponent({
  props: ['modelValue', 'disabled'],
  emits: ['update:modelValue'],
  template: `<input :value="Array.isArray(modelValue) ? modelValue.join(',') : modelValue" :disabled="disabled" @input="$emit('update:modelValue', Array.isArray(modelValue) ? $event.target.value.split(',').filter(Boolean) : $event.target.value)" />`,
})
const stubs = {
  ElForm: { template: '<form><slot /></form>' },
  ElFormItem: { template: '<div><slot /></div>' },
  ElSelect: field,
  ElTreeSelect: field,
  ElOption: true,
  ElAlert: { props: ['title'], template: '<p role="alert">{{ title }}</p>' },
  ElButton: {
    props: ['nativeType', 'disabled'],
    template: '<button :type="nativeType || \'button\'" :disabled="disabled"><slot /></button>',
  },
}
const configuration: RoleDataScope = {
  roleId: '3',
  companyId: '1',
  scopeType: 'dept',
  departmentIds: [],
  version: 4,
  departments: [],
  editable: true,
}
function editor() {
  return mount(RoleDataScopeEditor, { props: { roleId: '3' }, global: { stubs } })
}
beforeEach(() => {
  vi.resetAllMocks()
  setActivePinia(createPinia())
  const session = useSessionStore()
  session.user = {
    id: '1',
    username: 'admin',
    displayName: '管理员',
    mustChangePassword: false,
    permissions: ['role:assign_permission'],
    companies: [],
  }
  session.companyId = '1'
  vi.mocked(api.roleDataScope).mockResolvedValue(configuration)
  vi.mocked(api.saveRoleDataScope).mockResolvedValue({ ...configuration, version: 5 })
})
describe('Y-02 角色部门范围', () => {
  it('保存当前公司范围独立版本，自定义选择随模式清空', async () => {
    const wrapper = editor()
    await flushPromises()
    await wrapper.get('#role-department-scope').setValue('custom')
    await wrapper.get('#role-scope-departments').setValue('10,11')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.saveRoleDataScope).toHaveBeenCalledWith(
      '3',
      { scopeType: 'custom', departmentIds: ['10', '11'], version: 4 },
      expect.any(String),
    )
    await wrapper.get('#role-department-scope').setValue('self')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.saveRoleDataScope).toHaveBeenLastCalledWith(
      '3',
      { scopeType: 'self', departmentIds: [], version: 5 },
      expect.any(String),
    )
    wrapper.unmount()
  })
  it('结果不明时锁住内容并使用原键重试', async () => {
    vi.mocked(api.saveRoleDataScope).mockRejectedValueOnce(
      new ApiError(0, 'NETWORK_ERROR', '连接中断'),
    )
    const wrapper = editor()
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('#role-department-scope').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(vi.mocked(api.saveRoleDataScope).mock.calls[1]).toEqual(
      vi.mocked(api.saveRoleDataScope).mock.calls[0],
    )
    wrapper.unmount()
  })
  it('保存后刷新同一会话不清空成功提示或重新加载范围', async () => {
    const wrapper = editor()
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const session = useSessionStore()
    const current = session.user!
    session.user = { ...current, permissions: [...current.permissions] }
    await flushPromises()
    expect(wrapper.text()).toContain('当前公司的角色部门范围已保存')
    expect(api.roleDataScope).toHaveBeenCalledTimes(1)
    session.user = { ...current, id: '2' }
    await flushPromises()
    expect(wrapper.text()).not.toContain('当前公司的角色部门范围已保存')
    expect(api.roleDataScope).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
  it('切公司会取消旧请求，迟到结果不覆盖新版本', async () => {
    let old!: (value: RoleDataScope) => void
    vi.mocked(api.roleDataScope).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          old = resolve
        }),
    )
    const wrapper = editor()
    await flushPromises()
    const signal = vi.mocked(api.roleDataScope).mock.calls[0]![1]!
    vi.mocked(api.roleDataScope).mockResolvedValue({ ...configuration, companyId: '2', version: 8 })
    useSessionStore().companyId = '2'
    await flushPromises()
    old(configuration)
    await flushPromises()
    expect(signal.aborted).toBe(true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.saveRoleDataScope).toHaveBeenCalledWith(
      '3',
      expect.objectContaining({ version: 8 }),
      expect.any(String),
    )
    wrapper.unmount()
  })
  it('只读管理员不能保存，无公司或角色授权时不读取', async () => {
    vi.mocked(api.roleDataScope).mockResolvedValue({ ...configuration, editable: false })
    const wrapper = editor()
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.saveRoleDataScope).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('拥有全部部门范围')
    useSessionStore().companyId = null
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
    expect(api.roleDataScope).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})
