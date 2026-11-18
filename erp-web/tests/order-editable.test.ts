import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import EditableTable from '../src/components/EditableTable.vue'
import OrderLineImport from '../src/components/OrderLineImport.vue'
import { parseOrderPaste, prepareOrderPaste } from '../src/utils/order-line-paste'
import { record } from '../src/api/admin'
import { document as salesDocument, documents } from '../src/api/sales'
import { procurementDocument, procurementList } from '../src/api/procurement'

vi.mock('../src/api/admin')
vi.mock('../src/api/sales')
vi.mock('../src/api/procurement')
const auth = vi.hoisted(() => ({ companyId: '1', visible: true }))
vi.mock('../src/stores/session', () => ({ useSessionStore: () => auth }))

const catalog = [
  { id: '41', code: 'SKU-01', name: '商品甲', status: 'active' },
  { id: '42', code: 'SKU-02', name: '商品乙', status: 'active' },
]
const sku = {
  id: '41',
  defaultTaxRate: '0.13',
  units: [
    { unitId: '51', unitName: '件', rateToBase: '1', isBase: true },
    { unitId: '52', unitName: '箱', rateToBase: '12', isBase: false },
  ],
}
const input = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template:
    '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)">',
})
const stubs = {
  ElButton: { template: '<button><slot /></button>' },
  ElInput: input,
  ElSelect: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template:
      '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>',
  },
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  ElDialog: { template: '<section><slot /><slot name="footer" /></section>' },
  ElAlert: { props: ['title'], template: '<p role="alert">{{ title }}</p>' },
}

beforeEach(() => {
  vi.resetAllMocks()
  Object.assign(auth, { companyId: '1', visible: true, can: () => auth.visible })
  vi.mocked(record).mockResolvedValue(sku)
})

describe('Excel 商品明细粘贴', () => {
  it('保留长数字及小数原文，同一商品只取一次资料', async () => {
    const rows = parseOrderPaste(
      'SKU-01\t0.000001\t9007199254740993.12345678\r\nSKU-01\t2.500000\t\r\n',
    )
    const prepared = await prepareOrderPaste(rows, catalog, record)
    expect(prepared.map((line) => [line.qty, line.unitPrice, line.unitId])).toEqual([
      ['0.000001', '9007199254740993.12345678', '51'],
      ['2.500000', '', '51'],
    ])
    expect(record).toHaveBeenCalledTimes(1)
  })

  it.each(['SKU-01\t0\t2', 'SKU-01\t1e3\t2', 'SKU-01\t1.0000001\t2', 'SKU-01\t2\t-1'])(
    '拒绝无效数值 %s',
    (text) => {
      expect(() => parseOrderPaste(text)).toThrow()
    },
  )

  it('整批校验编码及行数，拒绝未识别商品与超量追加', async () => {
    await expect(
      prepareOrderPaste(parseOrderPaste('MISSING\t1\t2'), catalog, record),
    ).rejects.toThrow('商品编码不存在')
    expect(record).not.toHaveBeenCalled()
    expect(() => parseOrderPaste(Array(501).fill('SKU-01\t1\t').join('\n'))).toThrow('最多500行')
  })
})

describe('明细键盘操作', () => {
  it('50行内按列移动、跨行、尾行新增和删除当前行，Tab能离开表格', async () => {
    const host = defineComponent({
      components: { EditableTable },
      setup() {
        return {
          rows: ref(
            Array.from({ length: 50 }, (_, index) => ({ id: index, qty: '1', price: '2' })),
          ),
        }
      },
      template:
        '<EditableTable :count="rows.length" @add="rows.push({ id: 50, qty: \'1\', price: \'2\' })" @remove="rows.splice($event, 1)"><tr v-for="row in rows" :key="row.id" data-edit-row><td><input v-model="row.qty"></td><td><input v-model="row.price"></td></tr></EditableTable>',
    })
    const wrapper = mount(host, { attachTo: document.body })
    try {
      const scroll = wrapper.get('[role=region][aria-label="商品明细横向滚动明细"]')
      expect(scroll.attributes('tabindex')).toBe('0')
      const fields = () => wrapper.findAll('input')
      fields()[0]!.element.focus()
      await fields()[0]!.trigger('keydown', { key: 'Enter' })
      expect(fields()[1]!.element).toBe(document.activeElement)
      await fields()[1]!.trigger('keydown', { key: 'ArrowDown' })
      expect(fields()[3]!.element).toBe(document.activeElement)
      await fields()[3]!.trigger('keydown', { key: 'Tab', shiftKey: true })
      expect(fields()[2]!.element).toBe(document.activeElement)
      fields().at(-1)!.element.focus()
      await fields().at(-1)!.trigger('keydown', { key: 'Enter' })
      expect(wrapper.findAll('tbody tr')).toHaveLength(51)
      expect(fields()[100]!.element).toBe(document.activeElement)
      await fields()[100]!.trigger('keydown', { key: 'Delete', ctrlKey: true })
      expect(wrapper.findAll('tbody tr')).toHaveLength(50)
      const event = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })
      fields().at(-1)!.element.dispatchEvent(event)
      expect(event.defaultPrevented).toBe(false)
    } finally {
      wrapper.unmount()
    }
  })

  it('下拉选项打开时保留回车和方向键，批量粘贴先交给确认入口', async () => {
    const wrapper = mount(EditableTable, {
      props: { count: 1 },
      slots: {
        default:
          '<tr data-edit-row><td><input role="combobox" aria-expanded="true"></td><td><input aria-label="数量"></td></tr>',
      },
      attachTo: document.body,
    })
    try {
      const select = wrapper.get('input[role=combobox]')
      for (const key of ['ArrowDown', 'Enter']) {
        const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })
        select.element.dispatchEvent(event)
        expect(event.defaultPrevented).toBe(false)
      }
      const paste = new Event('paste', { bubbles: true, cancelable: true })
      Object.defineProperty(paste, 'clipboardData', { value: { getData: () => 'SKU-01\t2\t3' } })
      wrapper.get('input[aria-label=数量]').element.dispatchEvent(paste)
      expect(paste.defaultPrevented).toBe(true)
      expect(wrapper.emitted('paste')).toEqual([['SKU-01\t2\t3']])
    } finally {
      wrapper.unmount()
    }
  })
})

describe('历史订单复制', () => {
  it('历史价格口径不同则用本单参考价，保留原单位和数量', async () => {
    const history = {
      id: '70',
      status: 'approved',
      version: 4,
      allowedActions: [],
      priceTaxInclusive: false,
      lines: [{ id: '80', skuId: '41', unitId: '52', qty: '2.500000', unitPrice: '4.00000000' }],
    }
    vi.mocked(documents).mockResolvedValue({ items: [history], nextCursor: null })
    vi.mocked(salesDocument).mockResolvedValue(history)
    const wrapper = mount(OrderLineImport, {
      props: {
        kind: 'sales-orders',
        count: 0,
        partnerId: '60',
        disabled: false,
        skus: catalog,
        priceTaxInclusive: true,
      },
      global: { stubs },
    })
    try {
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '从历史订单复制')!
        .trigger('click')
      await flushPromises()
      await wrapper.get('select').setValue('70')
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '追加明细')!
        .trigger('click')
      await flushPromises()
      expect(wrapper.emitted('import')?.[0]?.[0]).toEqual([
        expect.objectContaining({ unitId: '52', qty: '2.500000', unitPrice: '' }),
      ])
    } finally {
      wrapper.unmount()
    }
  })

  it.each(['sales-orders', 'purchase-orders'] as const)(
    '按当前伙伴检索 %s 并保留原单位及精确数量',
    async (kind) => {
      const history = {
        id: '70',
        documentNo: 'ORDER-70',
        status: 'approved',
        version: 4,
        allowedActions: [],
        lines: [{ id: '80', skuId: '41', unitId: '52', qty: '2.500000', unitPrice: '4.00000000' }],
      }
      vi.mocked(documents).mockResolvedValue({ items: [history], nextCursor: null })
      vi.mocked(procurementList).mockResolvedValue({ items: [history], nextCursor: null })
      vi.mocked(salesDocument).mockResolvedValue(history)
      vi.mocked(procurementDocument).mockResolvedValue(history)
      const wrapper = mount(OrderLineImport, {
        props: { kind, count: 0, partnerId: '60', disabled: false, skus: catalog },
        global: { stubs },
      })
      try {
        await wrapper
          .findAll('button')
          .find((button) => button.text() === '从历史订单复制')!
          .trigger('click')
        await flushPromises()
        const list = kind === 'sales-orders' ? documents : procurementList
        expect(list).toHaveBeenCalledWith(
          kind,
          expect.objectContaining(
            kind === 'sales-orders' ? { customerId: '60' } : { supplierId: '60' },
          ),
        )
        await wrapper.get('select').setValue('70')
        await wrapper
          .findAll('button')
          .find((button) => button.text() === '追加明细')!
          .trigger('click')
        await flushPromises()
        expect(wrapper.emitted('import')?.[0]?.[0]).toEqual([
          expect.objectContaining({
            skuId: '41',
            unitId: '52',
            qty: '2.500000',
            unitPrice: '4.00000000',
          }),
        ])
      } finally {
        wrapper.unmount()
      }
    },
  )
})
