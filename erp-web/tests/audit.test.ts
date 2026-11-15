import { beforeEach, expect, it, vi } from 'vitest'
import { defineComponent, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import AuditPage from '../src/pages/AuditPage.vue'
import * as api from '../src/api/audit'
import * as admin from '../src/api/admin'
import { ApiError } from '../src/api/http'
const security = reactive({ companyId: '1', permissions: ['audit:view', 'admin:outbox'] })
vi.mock('../src/api/audit')
vi.mock('../src/api/admin')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => ({ get companyId() { return security.companyId }, company: { id: '1' }, user: { companies: [{ id: '1', name: '公司甲' }] }, can: (permission: string) => security.permissions.includes(permission) }) }))
const input = defineComponent({ props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' })
const select = defineComponent({ props: ['modelValue'], emits: ['update:modelValue'], template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>' })
const stubs = { ElInput: input, ElSelect: select, ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' }, ElRadioGroup: select, ElRadioButton: { props: ['value'], template: '<option :value="value"><slot /></option>' }, ElButton: { props: ['nativeType', 'disabled'], template: '<button :type="nativeType" :disabled="disabled"><slot /></button>' }, ElTable: { props: ['data'], template: '<div class="table-result">{{ JSON.stringify(data) }}</div>' }, ElTableColumn: true, ElAlert: { props: ['title'], template: '<p role="alert">{{ title }}</p>' }, ElDialog: true, ElPopconfirm: true }
beforeEach(() => { vi.resetAllMocks(); security.companyId = '1'; security.permissions = ['audit:view', 'admin:outbox']; vi.mocked(api.auditLogs).mockResolvedValue({ items: [], nextCursor: null }); vi.mocked(api.outboxEvents).mockResolvedValue({ items: [], nextCursor: null }); vi.mocked(admin.allRecords).mockResolvedValue([]) })
it('审计筛选保留大对象编号与日期原值', async () => {
  const wrapper = mount(AuditPage, { global: { stubs } }); await flushPromises()
  await wrapper.get('#audit-entity-id').setValue('9007199254740993'); await wrapper.get('#audit-action').setValue('shipment.sign'); await wrapper.get('#audit-from').setValue('2026-11-01'); await wrapper.get('#audit-to').setValue('2026-11-15'); await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(api.auditLogs).toHaveBeenLastCalledWith(expect.objectContaining({ entityId: '9007199254740993', action: 'shipment.sign', dateFrom: '2026-11-01', dateTo: '2026-11-15', limit: 20 })); wrapper.unmount()
})
it('异常事件默认查询当前公司失败事件，切换状态重置游标', async () => {
  const wrapper = mount(AuditPage, { global: { stubs } }); await flushPromises(); await wrapper.get('select[aria-label="审计与事件"]').setValue('events'); await flushPromises()
  expect(api.outboxEvents).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'dead', cursor: undefined })); await wrapper.get('#event-status').setValue('done'); await wrapper.get('form').trigger('submit'); await flushPromises(); expect(api.outboxEvents).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'done' })); wrapper.unmount()
})
it('公司切换丢弃旧公司的审计结果', async () => {
  let complete: (value: Awaited<ReturnType<typeof api.auditLogs>>) => void = () => undefined
  vi.mocked(api.auditLogs).mockReturnValueOnce(new Promise(resolve => { complete = resolve }))
  const wrapper = mount(AuditPage, { global: { stubs } }); security.companyId = '2'; await flushPromises(); complete({ items: [{ id: '1', action: '旧公司操作', entityType: 'shipment', createdAt: '' }], nextCursor: null }); await flushPromises(); expect(wrapper.text()).not.toContain('旧公司操作'); wrapper.unmount()
})
it('查询失败显示中文原因并允许重新查询', async () => {
  vi.mocked(api.auditLogs).mockRejectedValueOnce(new ApiError(403, 'FORBIDDEN', '审计权限已调整'))
  const wrapper = mount(AuditPage, { global: { stubs } }); await flushPromises(); expect(wrapper.get('[role="alert"]').text()).toContain('审计权限已调整'); await wrapper.get('form').trigger('submit'); await flushPromises(); expect(wrapper.find('[role="alert"]').exists()).toBe(false); wrapper.unmount()
})
