import { afterEach, expect, it, vi } from 'vitest'
import { computed, defineComponent, inject, provide } from 'vue'
import type { ComputedRef } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import InventoryTracePage from '../src/pages/InventoryTracePage.vue'
import * as api from '../src/api/inventory-tracking'
import type { StockMovementTrace } from '../src/types/inventory-tracking'

vi.mock('../src/api/admin', () => ({ allRecords: vi.fn(async () => []) }))
vi.mock('../src/api/inventory-tracking')
vi.mock('../src/stores/session', () => ({
  useSessionStore: () => ({ companyId: '1', can: () => true }),
}))
const route = { query: { batchId: '11', warehouseId: '21', skuId: '31' } }
vi.mock('vue-router', () => ({ useRoute: () => route }))
const row: StockMovementTrace = {
  id: '41',
  warehouseName: '退回仓',
  skuName: '退回商品',
  movementType: 'sales_return_in',
  postingAction: 'reverse',
  onHandDelta: '-1',
  blockedDelta: '0',
  businessDate: '2026-11-15',
  sourceType: 'document_reversal',
  sourceId: '81',
  sourceNo: 'REV-81',
  returnMode: 'sourceless',
  returnSourceId: '51',
  returnSourceNo: 'SR-51',
  serials: [],
}
const table = defineComponent({
  props: ['data'],
  setup(props) {
    provide(
      'trace-test-rows',
      computed(() => props.data),
    )
    return {}
  },
  template: '<div><slot/></div>',
})
const column = defineComponent({
  props: ['label', 'prop'],
  setup() {
    return { rows: inject<ComputedRef<StockMovementTrace[]>>('trace-test-rows') }
  },
  template:
    '<section>{{label}}<div v-for="row in rows" :key="row.id"><slot :row="row">{{row[prop]}}</slot></div></section>',
})
let wrapper: ReturnType<typeof mount> | undefined
afterEach(() => wrapper?.unmount())

it('批次追溯保留冲销编号，原退货在右侧查看且不改变查询条件', async () => {
  vi.mocked(api.trackedBatches).mockResolvedValue({ items: [], nextCursor: null })
  vi.mocked(api.batchTrace).mockResolvedValue({
    batch: {
      id: '11',
      skuId: '31',
      skuCode: 'SKU',
      skuName: '退回商品',
      batchNo: 'LOT',
      status: 'normal',
      expiryState: 'valid',
    },
    balances: [],
    items: [row],
    nextCursor: null,
  })
  wrapper = mount(InventoryTracePage, {
    global: {
      stubs: {
        ElTable: table,
        ElTableColumn: column,
        ElTabs: { template: '<div><slot/></div>' },
        ElTabPane: true,
        ElForm: { template: '<form><slot/></form>' },
        ElFormItem: { template: '<div><slot/></div>' },
        ElInput: true,
        ElInputNumber: true,
        ElTag: true,
        ElSelect: true,
        ElOption: true,
        ElButton: { template: '<button><slot/></button>' },
        ElAlert: true,
        Preview: {
          props: ['kind', 'id'],
          emits: ['close'],
          template:
            '<aside>{{kind}} {{id}}<button @click="$emit(\'close\')">关闭来源</button></aside>',
        },
      },
    },
  })
  await flushPromises()
  expect(wrapper.text()).toContain('REV-81')
  const button = wrapper.findAll('button').find((item) => item.text() === '查看原退货 · SR-51')!
  expect(button).toBeDefined()
  await button.trigger('click')
  await flushPromises()
  expect(wrapper.get('aside').text()).toContain('sales-returns 51')
  await wrapper.get('aside button').trigger('click')
  expect(wrapper.find('aside').exists()).toBe(false)
  expect(route.query).toEqual({ batchId: '11', warehouseId: '21', skuId: '31' })
  expect(wrapper.text()).toContain('REV-81')
})
