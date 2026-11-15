import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import PeriodActions from '../src/components/PeriodActions.vue'
import * as periodApi from '../src/api/period'
import * as salesApi from '../src/api/sales'
import { ApiError } from '../src/api/http'
import type { PeriodCheck } from '../src/api/period'

vi.mock('../src/api/period')
vi.mock('../src/api/sales')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => ({ can: () => true }) }))
const input = defineComponent({ props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)"/>' })
const stubs = {
  ElDialog: { props: ['modelValue'], template: '<section v-if="modelValue"><slot/><footer><slot name="footer"/></footer></section>' },
  ElInput: input, ElButton: { template: '<button><slot/></button>' }, ElFormItem: { template: '<div><slot/></div>' },
  ElTable: { name: 'ElTable', props: ['data'], template: '<div class="check-table"><slot/></div>' }, ElTableColumn: true,
  ElTag: { template: '<span><slot/></span>' }, ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' },
}
const period = { id: '20', period: '202611', status: 'open', version: 4 }
const report: PeriodCheck = { id: '20', period: '202611', version: 4, passed: true, items: ['unposted_documents', 'pending_money', 'inventory_reconciliation', 'money_reconciliation', 'cost_residue'].map(check => ({ check, passed: true, count: 0, message: '检查通过' })) }
type Wrapper = ReturnType<typeof mount>
async function click(wrapper: Wrapper, label: string): Promise<void> { const button = wrapper.findAll('button').find(item => item.text() === label); if (!button) throw new Error('未找到按钮：' + label); await button.trigger('click'); await flushPromises() }
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(periodApi.checkPeriod).mockResolvedValue(report)
  vi.mocked(periodApi.changePeriod).mockResolvedValue({ id: '20', status: 'closed' })
})
describe('期间关账操作', () => {
  it('完整显示五项检查，不通过时禁止提交关账', async () => {
    vi.mocked(periodApi.checkPeriod).mockResolvedValue({ ...report, passed: false, items: report.items.map(item => item.check === 'pending_money' ? { ...item, passed: false, count: 2, message: '有两张资金单据待确认' } : item) })
    const wrapper = mount(PeriodActions, { props: { period }, global: { stubs } })
    await click(wrapper, '关账')
    const items = wrapper.findComponent({ name: 'ElTable' }).props('data') as PeriodCheck['items']
    expect(items.map(item => item.check)).toEqual(['unposted_documents', 'pending_money', 'inventory_reconciliation', 'money_reconciliation', 'cost_residue'])
    expect(items.find(item => item.check === 'pending_money')).toEqual(expect.objectContaining({ passed: false, count: 2 }))
    const confirm = wrapper.findAll('button').find(button => button.text() === '确认关账')!
    expect(confirm.attributes()).toHaveProperty('disabled'); await confirm.trigger('click'); expect(periodApi.changePeriod).not.toHaveBeenCalled(); wrapper.unmount()
  })
  it('重新打开必须填写原因，并提交当前版本', async () => {
    const wrapper = mount(PeriodActions, { props: { period: { ...period, status: 'closed' } }, global: { stubs } })
    await click(wrapper, '重新打开'); await click(wrapper, '确认重新打开')
    expect(periodApi.changePeriod).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('请填写重新打开期间的原因')
    await wrapper.get('#period-reopen-reason').setValue('补录银行回单后重新核对'); await click(wrapper, '确认重新打开')
    expect(periodApi.changePeriod).toHaveBeenCalledWith('20', 'reopen', { version: 4, reason: '补录银行回单后重新核对' }, expect.any(String))
    expect(periodApi.checkPeriod).not.toHaveBeenCalled(); expect(wrapper.emitted('changed')).toHaveLength(1); wrapper.unmount()
  })
  it('关账网络未知时只查询原operation和键，成功后不重复关账', async () => {
    vi.mocked(periodApi.changePeriod).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络超时'))
    vi.mocked(salesApi.commandResult).mockResolvedValueOnce({ status: 'succeeded', response: { id: '20', status: 'closed', version: 5, allowedActions: [] } })
    const wrapper = mount(PeriodActions, { props: { period }, global: { stubs } })
    await click(wrapper, '关账'); await click(wrapper, '确认关账')
    const originalKey = vi.mocked(periodApi.changePeriod).mock.calls[0][3]
    expect(wrapper.findAll('button').some(button => button.text() === '确认关账')).toBe(false)
    await click(wrapper, '查询结果')
    expect(salesApi.commandResult).toHaveBeenCalledWith('period.close', originalKey); expect(periodApi.changePeriod).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('changed')).toHaveLength(1); wrapper.unmount()
  })
  it('版本冲突要求关闭刷新，不能用旧版本再提交', async () => {
    vi.mocked(periodApi.changePeriod).mockRejectedValueOnce(new ApiError(409, 'VERSION_CONFLICT', '期间版本已变化'))
    const wrapper = mount(PeriodActions, { props: { period }, global: { stubs } })
    await click(wrapper, '关账'); await click(wrapper, '确认关账')
    expect(wrapper.text()).toContain('期间已更新，请关闭并刷新')
    await click(wrapper, '确认关账'); expect(periodApi.changePeriod).toHaveBeenCalledTimes(1); expect(salesApi.commandResult).not.toHaveBeenCalled(); wrapper.unmount()
  })
  it('未知结果查询404后锁定原因，即使期间版本变化仍用原键和原内容重试', async () => {
    vi.mocked(periodApi.changePeriod).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络超时')).mockResolvedValueOnce({ id: '20', status: 'open' })
    vi.mocked(salesApi.commandResult).mockRejectedValueOnce(new ApiError(404, 'NOT_FOUND', '尚无已提交结果'))
    const wrapper = mount(PeriodActions, { props: { period: { ...period, status: 'closed' } }, global: { stubs } })
    await click(wrapper, '重新打开'); await wrapper.get('#period-reopen-reason').setValue('原始银行回单复核原因'); await click(wrapper, '确认重新打开')
    const original = vi.mocked(periodApi.changePeriod).mock.calls[0]
    await click(wrapper, '查询结果'); expect(salesApi.commandResult).toHaveBeenCalledWith('period.reopen', original[3])
    expect(wrapper.get('#period-reopen-reason').attributes()).toHaveProperty('disabled'); expect(wrapper.text()).toContain('使用原内容重试')
    await wrapper.setProps({ period: { ...period, status: 'closed', version: 9 } }); await click(wrapper, '确认重新打开')
    expect(vi.mocked(periodApi.changePeriod).mock.calls[1]).toEqual(original)
    expect(original[2]).toEqual({ version: 4, reason: '原始银行回单复核原因' }); expect(wrapper.emitted('changed')).toHaveLength(1); wrapper.unmount()
  })
})
