import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import * as api from '../src/api/wms-operations'
import WmsPackingDocuments from '../src/pages/wms/WmsPackingDocuments.vue'
import { shipmentPackingList } from '../src/pages/wms/packing-documents'
import type { ShipmentBox } from '../src/pages/wms/packing-documents'

vi.mock('../src/api/wms-operations')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))

const session = reactive<{ companyId: string; can: (permission: string) => boolean }>({
  companyId: '1',
  can: () => true,
})
const first: ShipmentBox = {
  id: '9007199254740993',
  shipmentId: '9007199254740994',
  shipmentNo: 'FH-001',
  warehouseId: '10',
  boxNo: '02',
  status: 'done',
  version: 1,
}
const second = { ...first, id: '9007199254740995', boxNo: '10' }
const cancelled = { ...first, id: '9007199254740996', boxNo: '03', status: 'cancelled' }
const foreignWarehouse = { ...first, id: '9007199254740997', warehouseId: '20' }
const foreignShipment = { ...first, id: '9007199254740998', shipmentId: '11' }
const line = {
  id: '9007199254740999',
  skuCode: 'SKU-01',
  skuName: '测试商品',
  batchNo: 'L-01',
  qty: '1.000001',
  serialNos: ['SN-001', 'SN-002'],
}

function page() {
  return mount(WmsPackingDocuments, {
    props: { shipment: first },
    global: {
      stubs: {
        ElButton: { template: '<button><slot /></button>' },
        ElAlert: { props: ['title'], template: '<p role="alert">{{ title }}</p>' },
      },
    },
  })
}

function button(wrapper: ReturnType<typeof page>, label: string) {
  const item = wrapper.findAll('button').find((row) => row.text() === label)
  if (!item) throw new Error('未找到按钮：' + label)
  return item
}

beforeEach(() => {
  vi.resetAllMocks()
  session.companyId = '1'
  session.can = () => true
  vi.mocked(api.workList)
    .mockResolvedValueOnce({ items: [second, cancelled, foreignWarehouse], nextCursor: '20' })
    .mockResolvedValueOnce({ items: [first, second, foreignShipment], nextCursor: null })
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

it('跨页收集当前发货单全部已完成箱，向标签接口提交一份 PDF', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  const pdf = new Blob(['%PDF'], { type: 'application/pdf' })
  const createUrl = vi.fn(() => 'blob:box-labels')
  vi.stubGlobal(
    'URL',
    class extends URL {
      static createObjectURL = createUrl
      static revokeObjectURL = vi.fn()
    },
  )
  const download = vi
    .spyOn(HTMLAnchorElement.prototype, 'click')
    .mockImplementation(() => undefined)
  vi.mocked(api.createLabels).mockResolvedValue(pdf)
  const wrapper = page()
  await button(wrapper, '下载整单箱标签').trigger('click')
  await flushPromises()

  expect(api.workList).toHaveBeenNthCalledWith(2, 'packs', {
    shipmentId: first.shipmentId,
    warehouseId: first.warehouseId,
    cursor: '20',
  })
  expect(api.createLabels).toHaveBeenCalledExactlyOnceWith('10', '70x40', 'pdf', [
    { id: first.id, type: 'box', copies: 1 },
    { id: second.id, type: 'box', copies: 1 },
  ])
  expect(createUrl).toHaveBeenCalledWith(pdf)
  expect(download.mock.contexts[0]).toHaveProperty('download', 'FH-001-箱标签.pdf')
  await vi.advanceTimersByTimeAsync(1000)
  wrapper.unmount()
})

it('有查看权限且没有标签打印权限时仍可查看并打印分箱清单', async () => {
  session.can = (permission) => permission === 'wms_pack:view'
  vi.mocked(api.workDetail)
    .mockResolvedValueOnce({ ...first, lines: [line] })
    .mockResolvedValueOnce({ ...second, lines: [{ ...line, id: '2', skuCode: 'SKU-02' }] })
  const documentCopy = document.implementation.createHTMLDocument()
  const print = vi.fn()
  vi.spyOn(window, 'open').mockReturnValue({
    document: documentCopy,
    print,
    focus: vi.fn(),
  } as unknown as Window)
  const wrapper = page()
  expect(wrapper.findAll('button').map((item) => item.text())).not.toContain('下载整单箱标签')

  await button(wrapper, '查看装箱清单').trigger('click')
  await flushPromises()
  expect(
    wrapper.findAll('tbody tr').map((row) => row.findAll('td').map((cell) => cell.text())),
  ).toEqual([
    ['SKU-01', '测试商品', 'L-01', '1.000001', 'SN-001、SN-002'],
    ['SKU-02', '测试商品', 'L-01', '1.000001', 'SN-001、SN-002'],
  ])
  await button(wrapper, '打印装箱清单').trigger('click')
  expect(documentCopy.title).toBe('装箱清单 · FH-001')
  expect([...documentCopy.querySelectorAll('h3')].map((title) => title.textContent)).toEqual([
    '箱号 02',
    '箱号 10',
  ])
  expect(print).toHaveBeenCalledOnce()
  expect(api.createLabels).not.toHaveBeenCalled()
  wrapper.unmount()
})

it('翻页过程中切换公司时停止读取并撤销旧整单打印', async () => {
  const seen = vi.fn(() => {
    session.companyId = '2'
    return { items: [first], nextCursor: '20' }
  })
  vi.mocked(api.workList)
    .mockReset()
    .mockImplementationOnce(async () => seen())
  const wrapper = page()
  await button(wrapper, '下载整单箱标签').trigger('click')
  await flushPromises()
  expect(seen).toHaveBeenCalledOnce()
  expect(api.workList).toHaveBeenCalledOnce()
  expect(api.createLabels).not.toHaveBeenCalled()
  wrapper.unmount()
})

it('箱明细已取消时拒绝展示过期装箱清单', async () => {
  vi.mocked(api.workDetail)
    .mockResolvedValueOnce({ ...first, status: 'cancelled', lines: [line] })
    .mockResolvedValueOnce({ ...second, lines: [line] })
  await expect(shipmentPackingList(first, () => true)).rejects.toMatchObject({
    code: 'PACK_CHANGED',
  })
})
