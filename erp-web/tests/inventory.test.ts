import { beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, defineComponent, inject, provide, reactive } from 'vue'
import type { ComputedRef } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import InventoryPage from '../src/pages/InventoryPage.vue'
import * as api from '../src/api/inventory'
import * as admin from '../src/api/admin'
import { ApiError } from '../src/api/http'
import { demandExceedsAvailable } from '../src/types/inventory'
import type { InventoryBalance, InventorySnapshot } from '../src/types/inventory'

vi.mock('../src/api/inventory')
vi.mock('../src/api/admin')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => navigation }))
const route = reactive({ query: {} as Record<string, string> })
const session = reactive<{ companyId: string; can: (permission: string) => boolean }>({
  companyId: '1',
  can: () => true,
})
const navigation = { push: vi.fn(), replace: vi.fn() }
const table = defineComponent({
  name: 'ElTable',
  props: ['data'],
  setup(props) {
    provide(
      'inventory-test-rows',
      computed(() => props.data),
    )
    return {}
  },
  template: '<div><slot /></div>',
})
const column = defineComponent({
  name: 'ElTableColumn',
  props: ['label', 'prop', 'align'],
  setup() {
    return { rows: inject<ComputedRef<InventoryBalance[]>>('inventory-test-rows') }
  },
  template:
    '<section :data-align="align"><h3>{{ label }}</h3><div v-for="row in rows" :key="row.warehouseId + row.skuId"><slot :row="row">{{ row[prop] }}</slot></div></section>',
})
const stubs = {
  ElTable: table,
  ElTableColumn: column,
  ElInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template:
      '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  ElSelect: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template:
      '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>',
  },
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  ElCheckbox: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template:
      '<label><input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" /><slot /></label>',
  },
  ElButton: {
    props: ['nativeType', 'disabled'],
    template: '<button :type="nativeType ?? \'button\'" :disabled="disabled"><slot /></button>',
  },
  ElAlert: { props: ['title'], template: '<p role="alert">{{ title }}</p>' },
  ElTag: { props: ['type'], template: '<span :data-type="type"><slot /></span>' },
  RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
}
function result(warehouseName = '主仓'): InventorySnapshot {
  return {
    items: [
      {
        warehouseId: '9007199254740993',
        warehouseName,
        skuId: '9007199254740994',
        skuCode: 'SKU-01',
        skuName: '商品甲',
        onHandQty: '1000.500000',
        reservedQty: '1',
        blockedQty: '0',
        availableQty: '999.500000',
        pendingDemandQty: '1000.000000',
        unitCost: '12.300000',
        costAmount: '12306.15',
      },
    ],
    nextCursor: null,
    asOf: '2026-11-15T02:05:00Z',
    summary: {
      costAmount: '12306.15',
      blockedQty: '0',
      shortageCount: 1,
      unreceivedCostAmount: '24.60',
      unreceivedCount: 2,
    },
  }
}
function page() {
  return mount(InventoryPage, { global: { stubs } })
}
beforeEach(() => {
  vi.resetAllMocks()
  route.query = {}
  session.companyId = '1'
  session.can = () => true
  vi.mocked(api.inventoryBalances).mockResolvedValue(result())
  vi.mocked(admin.allRecords).mockResolvedValue([
    { id: '9007199254740993', code: 'WH-01', name: '主仓' },
  ])
})
describe('实时库存筛选与联动', () => {
  it('读取仓库和异常条件，查询更新URL并保留字符串编号', async () => {
    route.query = { warehouseId: '9007199254740993', hideZero: 'true', shortageOnly: 'true' }
    const wrapper = page()
    await flushPromises()
    expect(api.inventoryBalances).toHaveBeenLastCalledWith(
      expect.objectContaining({
        warehouseId: '9007199254740993',
        hideZero: true,
        shortageOnly: true,
      }),
      expect.any(AbortSignal),
    )
    expect(wrapper.get('#inventory-warehouse').text()).toContain('WH-01 · 主仓')
    await wrapper.get('#inventory-search').setValue('商品甲')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(navigation.replace).toHaveBeenLastCalledWith({
      path: '/inventory-balances',
      query: {
        keyword: '商品甲',
        warehouseId: '9007199254740993',
        hideZero: 'true',
        shortageOnly: 'true',
      },
    })
    wrapper.unmount()
  })
  it('数量金额格式化、需求超可用标签及台账带仓库商品', async () => {
    const wrapper = page()
    await flushPromises()
    expect(wrapper.text()).toContain('1,000.5')
    expect(wrapper.text()).toContain('12,306.15')
    expect(wrapper.get('[data-type=warning]').text()).toBe('超出可用')
    expect(wrapper.findAll('[data-align=right]')).toHaveLength(7)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '台账')!
      .trigger('click')
    expect(navigation.push).toHaveBeenCalledWith({
      path: '/reports/inventory-flow',
      query: { warehouseId: '9007199254740993', skuId: '9007199254740994', detail: 'true' },
    })
    expect(demandExceedsAvailable('9007199254740993.000001', '9007199254740993.000000')).toBe(true)
    expect(demandExceedsAvailable('999.5', '999.500000')).toBe(false)
    wrapper.unmount()
  })
  it('无成本权限时不显示成本列与成本汇总，台账也受独立权限控制', async () => {
    session.can = (permission) => !['cost:view', 'report:inventory_flow'].includes(permission)
    const wrapper = page()
    await flushPromises()
    expect(wrapper.text()).not.toContain('单位成本')
    expect(wrapper.text()).not.toContain('库存金额')
    expect(wrapper.text()).not.toContain('12,306.15')
    expect(wrapper.text()).not.toContain('24.60')
    expect(wrapper.text()).not.toContain('台账')
    expect(wrapper.text()).toContain('发出未签收')
    wrapper.unmount()
  })
  it('切换公司终止旧库存请求，旧公司回包不回填', async () => {
    let resolveOld: (value: InventorySnapshot) => void = () => undefined
    vi.mocked(api.inventoryBalances).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveOld = resolve
      }),
    )
    const wrapper = page()
    await flushPromises()
    const signal = vi.mocked(api.inventoryBalances).mock.calls[0]![1]!
    session.companyId = '2'
    await flushPromises()
    expect(signal.aborted).toBe(true)
    resolveOld(result('旧公司仓库'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧公司仓库')
    wrapper.unmount()
  })
  it('查询失败显示错误并可重试恢复', async () => {
    vi.mocked(api.inventoryBalances).mockRejectedValueOnce(
      new ApiError(503, 'UNAVAILABLE', '库存暂时不可用'),
    )
    const wrapper = page()
    await flushPromises()
    expect(wrapper.get('[role=alert]').text()).toBe('库存暂时不可用')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '刷新')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.find('[role=alert]').exists()).toBe(false)
    expect(wrapper.text()).toContain('主仓')
    wrapper.unmount()
  })
})
