import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import * as api from '../src/api/org'
import * as admin from '../src/api/admin'
import { ApiError } from '../src/api/http'
import { useSessionStore } from '../src/stores/session'
import UserOrganizationEditor from '../src/components/org/UserOrganizationEditor.vue'
import DepartmentEditor from '../src/components/org/DepartmentEditor.vue'
import OrganizationPage from '../src/pages/org/OrganizationPage.vue'
import RecordEditor from '../src/components/RecordEditor.vue'
import { resourceSpecs } from '../src/pages/resources'
import { departmentTree } from '../src/types/org'
import type { Department, UserOrganization } from '../src/types/org'

vi.mock('../src/api/org')
vi.mock('../src/api/admin')
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }))
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
  ElInput: field,
  ElInputNumber: field,
  ElOption: true,
  ElAlert: { props: ['title'], template: '<p role="alert">{{ title }}</p>' },
  ElButton: {
    props: ['nativeType', 'disabled'],
    template: '<button :type="nativeType || \'button\'" :disabled="disabled"><slot /></button>',
  },
  RecordFrame: { template: '<section><slot /><slot name="footer" /></section>' },
}
const department: Department = {
  id: '10',
  parentId: '0',
  code: 'A',
  name: '销售部',
  sortNo: 0,
  path: '/10/',
  depth: 1,
  status: 'active',
  version: 3,
  memberCount: 0,
  leaderUserId: null,
  leaderName: null,
}
const configuration: UserOrganization = {
  userId: '2',
  companyId: '1',
  version: 4,
  departments: [{ id: '10', primary: true }],
  postIds: ['20'],
}
function editor() {
  return mount(UserOrganizationEditor, { props: { userId: '2' }, global: { stubs } })
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
    companies: [],
    permissions: ['user:update'],
  }
  session.companyId = '1'
  vi.mocked(api.userOrganization).mockResolvedValue(structuredClone(configuration))
  vi.mocked(api.assignmentOptions).mockResolvedValue({
    departments: [department],
    posts: [{ id: '20', code: 'P', name: '业务员', status: 'active', version: 0 }],
  })
  vi.mocked(api.assignOrganization).mockResolvedValue({ ...configuration, version: 5 })
  vi.mocked(api.companyUsers).mockResolvedValue({ items: [], nextCursor: null })
})
describe('Y-01 用户任职', () => {
  it('使用当前公司的组织版本保存，不修改用户资料版本', async () => {
    const wrapper = editor()
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.assignOrganization).toHaveBeenCalledWith(
      '2',
      { version: 4, departments: [{ id: '10', primary: true }], postIds: ['20'] },
      expect.any(String),
    )
    expect(wrapper.text()).toContain('当前公司的部门与岗位已保存')
    expect(wrapper.emitted('dirty')?.at(-1)).toEqual([false])
    wrapper.unmount()
  })
  it('网络结果不确定时锁定内容，并用相同幂等键重试', async () => {
    vi.mocked(api.assignOrganization).mockRejectedValueOnce(
      new ApiError(0, 'NETWORK_ERROR', '连接中断'),
    )
    const wrapper = editor()
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('#organization-departments').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const calls = vi.mocked(api.assignOrganization).mock.calls
    expect(calls).toHaveLength(2)
    expect(calls[1]).toEqual(calls[0])
    wrapper.unmount()
  })
  it('公司切换会中断旧加载，迟到结果不会覆盖新公司', async () => {
    let resolveOld!: (value: UserOrganization) => void
    vi.mocked(api.userOrganization).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve
        }),
    )
    const wrapper = editor()
    await flushPromises()
    const oldSignal = vi.mocked(api.userOrganization).mock.calls[0]![1]!
    vi.mocked(api.userOrganization).mockResolvedValue({
      ...configuration,
      companyId: '3',
      version: 8,
      departments: [],
      postIds: [],
    })
    useSessionStore().companyId = '3'
    await flushPromises()
    resolveOld(configuration)
    await flushPromises()
    expect(oldSignal.aborted).toBe(true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.assignOrganization).toHaveBeenCalledWith(
      '2',
      { version: 8, departments: [], postIds: [] },
      expect.any(String),
    )
    wrapper.unmount()
  })
  it('没有公司或更新权限时不读取任职，撤权后清空已加载表单', async () => {
    useSessionStore().companyId = null
    const wrapper = editor()
    await flushPromises()
    expect(api.userOrganization).not.toHaveBeenCalled()
    useSessionStore().companyId = '1'
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(true)
    useSessionStore().user!.permissions = []
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
    wrapper.unmount()
  })
  it('修改后重新加载需确认，取消时保留未保存内容', async () => {
    const wrapper = editor()
    await flushPromises()
    await wrapper.get('#organization-posts').setValue('')
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重新加载组织配置')!
      .trigger('click')
    expect(confirm).toHaveBeenCalled()
    expect(api.userOrganization).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('dirty')?.at(-1)).toEqual([true])
    wrapper.unmount()
    confirm.mockRestore()
  })
})
describe('Y-01 部门维护', () => {
  it('搜索树保留祖先，含下级切换重置成员分页，公司切换清除旧结果', async () => {
    useSessionStore().user!.permissions.push('org_department:view')
    const child = {
      ...department,
      id: '11',
      name: '华东组',
      code: 'EAST',
      parentId: '10',
      path: '/10/11/',
      depth: 2,
    }
    const sibling = { ...department, id: '12', name: '采购部', code: 'BUY', path: '/12/' }
    vi.mocked(api.departments).mockResolvedValue([department, child, sibling])
    vi.mocked(api.departmentMembers)
      .mockResolvedValueOnce({
        items: [{ id: '2', username: 'first', displayName: '直属成员', status: 'active' }],
        nextCursor: '2',
      })
      .mockResolvedValue({
        items: [{ id: '3', username: 'child', displayName: '下级成员', status: 'active' }],
        nextCursor: null,
      })
    const wrapper = mount(OrganizationPage, {
      global: {
        stubs: {
          ...stubs,
          ElTable: {
            props: ['data'],
            template: '<div class="members">{{ JSON.stringify(data) }}</div>',
          },
          ElTableColumn: true,
        },
      },
    })
    await flushPromises()
    await wrapper.get('[aria-label="搜索部门"]').setValue('east')
    await flushPromises()
    const visible = wrapper
      .findAll('.el-tree-node__content')
      .filter((node) => node.isVisible())
      .map((node) => node.text())
    expect(visible).toContain('销售部')
    expect(visible).toContain('华东组')
    expect(visible).not.toContain('采购部')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await flushPromises()
    expect(api.departmentMembers).toHaveBeenLastCalledWith(
      '10',
      undefined,
      expect.any(AbortSignal),
      true,
    )
    expect(wrapper.get('.members').text()).toContain('下级成员')
    expect(wrapper.get('.members').text()).not.toContain('直属成员')
    useSessionStore().companyId = '3'
    await flushPromises()
    expect(wrapper.get('input[type="checkbox"]').element).toHaveProperty('checked', false)
    expect(api.departmentMembers).toHaveBeenLastCalledWith(
      '10',
      undefined,
      expect.any(AbortSignal),
      false,
    )
    expect(wrapper.get('[aria-label="搜索部门"]').element).toHaveProperty('value', '')
    useSessionStore().user!.permissions = []
    await flushPromises()
    expect(wrapper.find('.members').exists()).toBe(false)
    wrapper.unmount()
  })
  it('真实部门树组件在加载节点后正常渲染详情与成员', async () => {
    useSessionStore().user!.permissions.push('org_department:view')
    vi.mocked(api.departments).mockResolvedValue([department])
    vi.mocked(api.departmentMembers).mockResolvedValue({ items: [], nextCursor: null })
    const wrapper = mount(OrganizationPage, {
      global: { stubs: { ...stubs, ElTable: true, ElTableColumn: true } },
    })
    await flushPromises()
    expect(wrapper.get('.el-tree').text()).toContain('销售部')
    expect(wrapper.get('section[aria-label="部门详情"]').text()).toContain('销售部')
    wrapper.unmount()
  })
  it('岗位编辑超时后锁定内容，并查询更新操作的原幂等结果', async () => {
    vi.mocked(admin.writeRecord).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '连接中断'))
    vi.mocked(admin.record).mockResolvedValue({ id: '20', status: 'succeeded' })
    const wrapper = mount(RecordEditor, {
      props: {
        spec: resourceSpecs['org/posts']!,
        row: { id: '20', code: 'P', name: '业务岗', sortNo: 0, version: 2 },
      },
      global: { stubs: { ...stubs, DataFields: true } },
    })
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '保存')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '查询结果')!
      .trigger('click')
    await flushPromises()
    expect(admin.record).toHaveBeenCalledWith(
      expect.stringContaining('operation=org_post.update&key='),
      undefined,
    )
    expect(wrapper.emitted('saved')).toHaveLength(1)
    wrapper.unmount()
  })
  it('组树保留顺序并复制节点，拖拽不会修改服务端快照', () => {
    const child = { ...department, id: '11', parentId: '10', path: '/10/11/', depth: 2 }
    const tree = departmentTree([department, child])
    expect(tree[0]!.children[0]!.id).toBe('11')
    tree[0]!.name = '移动后的临时值'
    expect(department.name).toBe('销售部')
    expect('children' in department).toBe(false)
  })
  it('编辑提交原部门版本，负责人留空转换为 null', async () => {
    vi.mocked(api.saveDepartment).mockResolvedValue(department)
    const wrapper = mount(DepartmentEditor, {
      props: { row: department, departments: [department] },
      global: { stubs },
    })
    await flushPromises()
    await wrapper.get('#department-name').setValue('新销售部')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '保存部门')!
      .trigger('click')
    await flushPromises()
    expect(api.saveDepartment).toHaveBeenCalledWith(
      { code: 'A', name: '新销售部', parentId: '0', leaderUserId: null, sortNo: 0, version: 3 },
      expect.any(String),
      '10',
    )
    wrapper.unmount()
  })
  it('缺少编码或名称不提交请求', async () => {
    const wrapper = mount(DepartmentEditor, { props: { departments: [] }, global: { stubs } })
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '保存部门')!
      .trigger('click')
    await flushPromises()
    expect(api.saveDepartment).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请填写部门编码和名称')
    wrapper.unmount()
  })
})
