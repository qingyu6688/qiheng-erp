import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { computed, defineComponent, inject, provide } from 'vue'
import type { ComputedRef } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { ElCheckbox, ElCheckboxGroup, ElInput } from 'element-plus'
import * as api from '../src/api/admin'
import { useSessionStore } from '../src/stores/session'
import PermissionMatrix from '../src/components/PermissionMatrix.vue'
import RecordRelations from '../src/components/RecordRelations.vue'
import ResourcePage from '../src/pages/ResourcePage.vue'
import RecordEditor from '../src/components/RecordEditor.vue'
import DataFields from '../src/components/DataFields.vue'
import type { DataRow } from '../src/types/admin'
import { confirmCompanyLeave } from '../src/utils/unsaved'

vi.mock('../src/api/admin')
const navigation = vi.hoisted(() => ({ leave: undefined as (() => boolean) | undefined }))
vi.mock('vue-router', () => ({
  onBeforeRouteLeave: (guard: () => boolean) => {
    navigation.leave = guard
  },
}))
const input = defineComponent({
  props: ['modelValue', 'disabled'],
  emits: ['update:modelValue'],
  template:
    '<input :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />',
})
const stubs = {
  ElInput: input,
  ElSelect: { props: ['disabled'], template: '<select :disabled="disabled"><slot /></select>' },
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  ElCheckbox: {
    props: ['disabled'],
    template: '<label><input type="checkbox" :disabled="disabled" /><slot /></label>',
  },
  ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
  ElAlert: { props: ['title'], template: '<p role="alert">{{ title }}</p>' },
  ElTable: { props: ['data'], template: '<div>{{ JSON.stringify(data) }}</div>' },
  ElTableColumn: true,
  ElDialog: { template: '<div role="dialog"><slot /><slot name="footer" /></div>' },
  DataFields: true,
  RouterLink: true,
  ElPopconfirm: true,
  ElTag: true,
}
beforeEach(() => {
  vi.restoreAllMocks()
  vi.resetAllMocks()
  setActivePinia(createPinia())
  const session = useSessionStore()
  session.user = {
    id: '1',
    username: 'admin',
    displayName: '管理员',
    mustChangePassword: false,
    permissions: [
      'sku:update',
      'unit:view',
      'warehouse:view',
      'warehouse:update',
      'user:view',
      'role:view',
      'user:assign_scope',
    ],
    companies: [],
  }
  vi.mocked(api.records).mockResolvedValue({ items: [], nextCursor: null })
  vi.mocked(api.allRecords).mockResolvedValue([{ id: '2', name: '件' }])
})

describe('角色权限矩阵', () => {
  it('按资源和动作排布，保留权限码与不可授予项目', async () => {
    const wrapper = mount(PermissionMatrix, {
      props: {
        permissions: [
          { code: 'warehouse:view', name: '查看仓库' },
          { code: 'warehouse:create', name: '新增仓库' },
          { code: 'user:reset_password', name: '重置用户密码' },
        ],
        modelValue: ['warehouse:create'],
      },
      global: { components: { ElCheckbox, ElCheckboxGroup, ElInput } },
    })
    expect(wrapper.get('table[aria-label="角色权限矩阵"]').text()).toContain(
      '资源查看新建修改提交过账其他',
    )
    expect(wrapper.get('input[value="warehouse:create"]').attributes('disabled')).toBeDefined()
    expect(
      (wrapper.get('input[value="warehouse:create"]').element as HTMLInputElement).checked,
    ).toBe(true)
    await wrapper.get('input[value="warehouse:view"]').setValue(true)
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([
      ['warehouse:create', 'warehouse:view'],
    ])
    await wrapper.get('#permission-search').setValue('重置')
    expect(wrapper.text()).toContain('重置用户密码')
    expect(wrapper.find('input[value="warehouse:view"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('已选 1 项')
    wrapper.unmount()
  })
})

describe('基础资料右侧维护面板', () => {
  it('银行账号列表只显示后四位，授权编辑仍保留原值', async () => {
    const account = {
      id: '3',
      code: 'BANK',
      name: '银行账户',
      status: 'active',
      version: 1,
      accountNo: '6222000012345678',
    }
    const session = useSessionStore()
    session.user!.permissions.push(
      'cash_account:view',
      'cash_account:update',
      'cash_account_no:view',
    )
    vi.mocked(api.records).mockResolvedValue({ items: [account], nextCursor: null })
    vi.mocked(api.record).mockResolvedValue(account)
    const table = defineComponent({
      props: ['data'],
      setup(props) {
        provide(
          'resource-test-row',
          computed(() => props.data?.[0]),
        )
        return {}
      },
      template: '<div><slot /></div>',
    })
    const column = defineComponent({
      props: ['label'],
      setup() {
        return { row: inject<ComputedRef<DataRow>>('resource-test-row') }
      },
      template: '<div v-if="row">{{ label }}<slot :row="row" /></div>',
    })
    const wrapper = mount(ResourcePage, {
      props: { resource: 'cash-accounts' },
      global: { stubs: { ...stubs, ElTable: table, ElTableColumn: column } },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('•••• 5678')
    expect(wrapper.text()).not.toContain(account.accountNo)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '编辑')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(DataFields).props('values')).toHaveProperty(
      'accountNo',
      account.accountNo,
    )
    wrapper.unmount()
  })
  it('服务端标记已引用单位时只读，保存仅提交业务字段', async () => {
    vi.mocked(api.record).mockResolvedValue({
      id: '3',
      name: '测试SKU',
      version: 7,
      units: [
        { unitId: '2', unitName: '件', rateToBase: '1.000000', isBase: true, referenced: true },
      ],
      barcodes: [],
    })
    const wrapper = mount(RecordRelations, {
      props: { resource: 'skus', row: { id: '3', name: '测试SKU' }, inline: true },
      global: { stubs },
    })
    await flushPromises()
    expect(wrapper.get('[role=region]').attributes('aria-label')).toBe('单位与条码 · 测试SKU')
    expect(wrapper.get('input[aria-label="换算率"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('已被单据引用，不能修改单位、换算率或移除。')
    const remove = wrapper.findAll('button').find((button) => button.text() === '移除')!
    expect(remove.attributes('disabled')).toBeDefined()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '保存单位换算')!
      .trigger('click')
    await flushPromises()
    expect(api.writeRecord).toHaveBeenCalledWith(
      'skus/3/units',
      { version: 7, units: [{ unitId: '2', rateToBase: '1.000000', isBase: true }] },
      'PUT',
    )
    await wrapper.get('#sku-barcodes-tab').trigger('click')
    expect(wrapper.get('#sku-barcodes-panel').isVisible()).toBe(true)
    expect(wrapper.get('#sku-units-panel').isVisible()).toBe(false)
    wrapper.unmount()
  })
  it('已有指定仓库范围显示名称选项，刷新不清空原仓库', async () => {
    vi.mocked(api.record).mockResolvedValue({
      id: '3',
      version: 2,
      roleIds: [],
      dataScopes: [{ companyId: '8', scopeType: 'warehouse', warehouseId: '9' }],
    })
    vi.mocked(api.allRecords).mockResolvedValue([{ id: '9', name: '华东主仓' }])
    const wrapper = mount(RecordRelations, {
      props: { resource: 'users', row: { id: '3', displayName: '库管' }, inline: true },
      global: { stubs },
    })
    await flushPromises()
    expect(api.allRecords).toHaveBeenCalledWith('warehouses', false, '8')
    expect(wrapper.get('select[aria-label="范围仓库"] option').text()).toBe('华东主仓')
    expect(wrapper.text()).not.toContain('加载失败')
    wrapper.unmount()
  })
  it('用户与角色页签切换加载新资料，旧响应不覆盖', async () => {
    let resolveOld: (value: Awaited<ReturnType<typeof api.records>>) => void = () => undefined
    vi.mocked(api.records)
      .mockReturnValueOnce(
        new Promise((resolve) => {
          resolveOld = resolve
        }),
      )
      .mockResolvedValueOnce({ items: [{ id: '2', name: '角色资料' }], nextCursor: null })
    const wrapper = mount(ResourcePage, { props: { resource: 'users' }, global: { stubs } })
    await wrapper.setProps({ resource: 'roles' })
    await flushPromises()
    expect(api.records).toHaveBeenLastCalledWith('roles', undefined, expect.any(Object))
    resolveOld({ items: [{ id: '1', name: '旧用户资料' }], nextCursor: null })
    await flushPromises()
    expect(wrapper.text()).toContain('角色资料')
    expect(wrapper.text()).not.toContain('旧用户资料')
    wrapper.unmount()
  })
  it('有未保存修改时保护关面板、路由离开与公司切换，保存后解除', async () => {
    const session = useSessionStore()
    session.user!.permissions.push('user:create')
    vi.spyOn(session, 'refresh').mockResolvedValue()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const wrapper = mount(ResourcePage, { props: { resource: 'users' }, global: { stubs } })
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '新建用户')!
      .trigger('click')
    await flushPromises()
    const editor = wrapper.findComponent(RecordEditor)
    editor.vm.$emit('dirty', true)
    await flushPromises()
    editor.vm.$emit('close')
    await flushPromises()
    expect(wrapper.findComponent(RecordEditor).exists()).toBe(true)
    expect(navigation.leave?.()).toBe(false)
    expect(confirmCompanyLeave()).toBe(false)
    confirm.mockReturnValue(true)
    editor.vm.$emit('saved')
    await flushPromises()
    expect(wrapper.findComponent(RecordEditor).exists()).toBe(false)
    confirm.mockClear()
    expect(navigation.leave?.()).toBe(true)
    expect(confirmCompanyLeave()).toBe(true)
    expect(confirm).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('保存单位换算不会丢失尚未保存的条码，全部保存后才清除dirty', async () => {
    const initial = {
      id: '3',
      name: 'SKU',
      version: 7,
      units: [{ unitId: '2', unitName: '件', rateToBase: '1', isBase: true }],
      barcodes: [],
    }
    vi.mocked(api.record).mockResolvedValue(initial)
    const wrapper = mount(RecordRelations, {
      props: { resource: 'skus', row: { id: '3', name: 'SKU' }, inline: true },
      global: { stubs },
    })
    await flushPromises()
    await wrapper.get('#sku-barcodes-tab').trigger('click')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '添加条码')!
      .trigger('click')
    await wrapper.get('input[aria-label="条码"]').setValue('EAN-01')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '保存单位换算')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.get('input[aria-label="条码"]').element).toHaveProperty('value', 'EAN-01')
    expect(wrapper.emitted('dirty')?.at(-1)).toEqual([true])
    vi.mocked(api.record).mockResolvedValue({
      ...initial,
      barcodes: [{ unitId: '', barcode: 'EAN-01' }],
    })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '保存条码')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.emitted('dirty')?.at(-1)).toEqual([false])
    wrapper.unmount()
  })
})
