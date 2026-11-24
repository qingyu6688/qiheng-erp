import { afterEach, beforeEach, expect, it } from 'vitest'
import { defineComponent, inject, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { vi } from 'vitest'
import { sourcelessSourceTarget } from '../src/utils/sourceless-sources'
import SourcelessSourceLink from '../src/components/sales/SourcelessSourceLink.vue'
import SourcelessSourcePreview from '../src/components/sales/SourcelessSourcePreview.vue'

const denied = reactive(new Set<string>())
const session = reactive({
  companyId: '1',
  user: { id: '7' },
  can: (permission: string): boolean => !denied.has(permission),
})
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
const wrappers: VueWrapper[] = []
const returned = {
  sourceType: 'sales_return_line',
  sourceId: '9007199254740993',
  sourceNo: 'SR-01',
  returnMode: 'sourceless',
}
beforeEach(() => {
  denied.clear()
  session.companyId = '1'
  session.user.id = '7'
})
afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
})
function mountLink(source = returned, original = false): VueWrapper {
  const wrapper = mount(SourcelessSourceLink, {
    props: { source, original },
    global: {
      stubs: {
        Preview: {
          props: ['kind', 'id'],
          emits: ['close'],
          template:
            '<aside>{{kind}} {{id}}<button @click="$emit(\'close\')">关闭来源</button></aside>',
        },
      },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}

it('退货来源保留大编号字符串，查看不要求成本或无原单办理权限', () => {
  denied.add('cost:view')
  denied.add('sales_return:sourceless')
  expect(sourcelessSourceTarget(returned, session.can)).toEqual({
    kind: 'sales-returns',
    id: '9007199254740993',
    number: 'SR-01',
  })
})
it('冲销的主要来源保持冲销单，仅原退货入口读取独立原单编号', () => {
  const reverse = {
    ...returned,
    sourceType: 'document_reversal',
    sourceId: '81',
    sourceNo: 'REV-81',
    returnSourceId: '51',
    returnSourceNo: 'SR-51',
  }
  expect(sourcelessSourceTarget(reverse, session.can)).toBeUndefined()
  expect(sourcelessSourceTarget(reverse, session.can, true)).toEqual({
    kind: 'sales-returns',
    id: '51',
    number: 'SR-51',
  })
  expect(
    sourcelessSourceTarget({ ...reverse, returnSourceId: undefined }, session.can, true),
  ).toBeUndefined()
})
it.each([Number.MAX_SAFE_INTEGER + 1, '../51', '0', '-1', 'https://example.com/51'])(
  '非法来源编号%s不生成查看入口',
  (sourceId) => {
    expect(sourcelessSourceTarget({ ...returned, sourceId }, session.can)).toBeUndefined()
  },
)
it('原单退货及未知业务不误用新模式抽屉', () => {
  expect(
    sourcelessSourceTarget({ ...returned, returnMode: 'original' }, session.can),
  ).toBeUndefined()
  expect(
    sourcelessSourceTarget({ ...returned, sourceType: 'shipment' }, session.can),
  ).toBeUndefined()
})
it('信用额退款必须有退款及退货查看权限，模式和编号均匹配', () => {
  const refund = {
    sourceType: 'refund_request',
    sourceId: '92',
    sourceNo: 'RF-92',
    refundMode: 'sourceless_credit',
  }
  expect(sourcelessSourceTarget(refund, session.can)).toEqual({
    kind: 'refund-requests',
    id: '92',
    number: 'RF-92',
  })
  denied.add('refund_request:view')
  expect(sourcelessSourceTarget(refund, session.can)).toBeUndefined()
  denied.clear()
  denied.add('sales_return:view')
  expect(sourcelessSourceTarget(refund, session.can)).toBeUndefined()
})
it('来源按钮只打开对应只读预览，关闭后不丢失原标签', async () => {
  const wrapper = mountLink()
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(wrapper.get('aside').text()).toContain('sales-returns 9007199254740993')
  await wrapper.get('aside button').trigger('click')
  expect(wrapper.find('aside').exists()).toBe(false)
  expect(wrapper.get('button').text()).toBe('SR-01')
})
it.each(['company', 'user', 'permission'])(
  '当前%s变化关闭已打开来源，避免跨范围遗留',
  async (change) => {
    const wrapper = mountLink()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('aside').exists()).toBe(true)
    if (change === 'company') session.companyId = '2'
    else if (change === 'user') session.user.id = '8'
    else denied.add('sales_return:view')
    await flushPromises()
    expect(wrapper.find('aside').exists()).toBe(false)
  },
)
it('退款预览向内部详情提供只读上下文和正确的单据类型', () => {
  const drawer = defineComponent({
    props: ['kind', 'id'],
    setup: () => ({ readonly: inject('erp.readOnlyPreview', false) }),
    template: '<p>{{kind}} {{id}} {{readonly}}</p>',
  })
  const wrapper = mount(SourcelessSourcePreview, {
    props: { id: '92', kind: 'refund-requests' },
    global: { stubs: { SourcelessDocumentDrawer: drawer } },
  })
  wrappers.push(wrapper)
  expect(wrapper.text()).toBe('refund-requests 92 true')
})
