import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElCheckbox, ElCheckboxGroup } from 'element-plus'
import * as api from '../src/api/sales'
import * as files from '../src/api/documents'
import { ApiError } from '../src/api/http'
import CommandDialog from '../src/components/sales/CommandDialog.vue'
import ReceiptAttachmentPicker from '../src/components/sales/ReceiptAttachmentPicker.vue'
import type { SalesDocument } from '../src/types/sales'
import type { Attachment } from '../src/types/documents'

vi.mock('../src/api/sales')
vi.mock('../src/api/documents')
vi.mock('../src/stores/session', () => ({
  useSessionStore: () => ({
    can: (permission: string) => writable || permission !== 'shipment:update',
  }),
}))
let writable = true
const receipt: Attachment = {
  id: '9007199254740993',
  fileName: '签收回单.pdf',
  contentType: 'application/pdf',
  sizeBytes: 64,
  createdAt: '2026-11-15T00:00:00Z',
}
const document: SalesDocument = {
  id: '10',
  documentNo: 'SH-01',
  version: 7,
  status: 'posted',
  allowedActions: ['sign'],
  lines: [{ id: '20', skuName: '商品甲', baseQty: '4.500000' }],
}
const stubs = {
  ElDialog: { template: '<section><slot /><footer><slot name="footer" /></footer></section>' },
  ElInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template:
      '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
  ElForm: { template: '<form><slot /></form>' },
  ElFormItem: { template: '<div><slot /></div>' },
  ElAlert: { props: ['title'], template: '<p role="alert">{{ title }}</p>' },
  ElSelect: true,
  ElOption: true,
}
async function click(wrapper: ReturnType<typeof mount>, text: string): Promise<void> {
  await wrapper
    .findAll('button')
    .find((button) => button.text() === text)!
    .trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  writable = true
  vi.mocked(files.attachments).mockResolvedValue([receipt])
  vi.mocked(files.uploadAttachment).mockResolvedValue({
    ...receipt,
    id: '9007199254740994',
    fileName: '新回单.png',
  })
  vi.mocked(api.salesCommand).mockResolvedValue({ ...document, status: 'signed' })
})
describe('签收回单与原请求保护', () => {
  it('默认签收数量并随签收提交所选回单编号', async () => {
    const wrapper = mount(CommandDialog, {
      props: { kind: 'shipments', document, action: 'sign' },
      global: { stubs: { ...stubs, ReceiptAttachmentPicker: true } },
    })
    expect(wrapper.get('input[aria-label="签收数量"]').element).toHaveProperty('value', '4.500000')
    expect(wrapper.text()).toContain('差异 0')
    wrapper.findComponent(ReceiptAttachmentPicker).vm.$emit('update:modelValue', [receipt.id])
    await click(wrapper, '确认登记签收')
    expect(api.salesCommand).toHaveBeenCalledWith(
      'shipments',
      '10',
      'sign',
      expect.objectContaining({
        version: 7,
        attachmentIds: [receipt.id],
        lines: [{ lineId: '20', signedQty: '4.500000' }],
      }),
      expect.any(String),
    )
    wrapper.unmount()
  })
  it('结果不确定后查询无结果，重试始终使用原数量日期和回单', async () => {
    vi.mocked(api.salesCommand)
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络超时'))
      .mockResolvedValueOnce(document)
    vi.mocked(api.commandResult).mockRejectedValueOnce(new ApiError(404, 'NOT_FOUND', '暂无结果'))
    const wrapper = mount(CommandDialog, {
      props: { kind: 'shipments', document, action: 'sign' },
      global: { stubs: { ...stubs, ReceiptAttachmentPicker: true } },
    })
    wrapper.findComponent(ReceiptAttachmentPicker).vm.$emit('update:modelValue', [receipt.id])
    await click(wrapper, '确认登记签收')
    const first = vi.mocked(api.salesCommand).mock.calls[0]!
    await click(wrapper, '查询结果')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
    expect(wrapper.findComponent(ReceiptAttachmentPicker).props('disabled')).toBe(true)
    await wrapper.get('input[aria-label="签收数量"]').setValue('1')
    wrapper.findComponent(ReceiptAttachmentPicker).vm.$emit('update:modelValue', ['999'])
    await click(wrapper, '确认登记签收')
    expect(vi.mocked(api.salesCommand).mock.calls[1]!.slice(3)).toEqual(first.slice(3))
    expect(first[3]).toMatchObject({
      attachmentIds: [receipt.id],
      lines: [{ lineId: '20', signedQty: '4.500000' }],
    })
    wrapper.unmount()
  })
  it('回单上传期间禁止签收，避免漏掉尚未完成的附件', async () => {
    const wrapper = mount(CommandDialog, {
      props: { kind: 'shipments', document, action: 'sign' },
      global: { stubs: { ...stubs, ReceiptAttachmentPicker: true } },
    })
    wrapper.findComponent(ReceiptAttachmentPicker).vm.$emit('busy', true)
    await flushPromises()
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '确认登记签收')!
        .attributes('disabled'),
    ).toBeDefined()
    await click(wrapper, '确认登记签收')
    expect(api.salesCommand).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
describe('当前发货单附件选择与上传', () => {
  it('只加载当前发货单，上传后自动选中且编号始终为字符串', async () => {
    const wrapper = mount(ReceiptAttachmentPicker, {
      props: { shipmentId: '10', modelValue: [receipt.id] },
      global: { components: { ElCheckbox, ElCheckboxGroup }, stubs },
    })
    await flushPromises()
    expect(files.attachments).toHaveBeenCalledWith('shipment', '10', expect.any(AbortSignal))
    const input = wrapper.get('input[type=file]')
    Object.defineProperty(input.element, 'files', {
      configurable: true,
      value: [new File(['回单'], '新回单.png', { type: 'image/png' })],
    })
    await input.trigger('change')
    await click(wrapper, '上传并选中')
    const [data, key] = vi.mocked(files.uploadAttachment).mock.calls[0]!
    expect(data.get('ownerType')).toBe('shipment')
    expect(data.get('ownerId')).toBe('10')
    expect(key).toBeTruthy()
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([[receipt.id, '9007199254740994']])
    expect(wrapper.emitted('busy')).toEqual([[true], [false]])
    wrapper.unmount()
    expect(vi.mocked(files.attachments).mock.calls[0]![2]?.aborted).toBe(true)
  })
  it('无发货单修改权限时仍可选择已有回单，隐藏上传入口', async () => {
    writable = false
    const wrapper = mount(ReceiptAttachmentPicker, {
      props: { shipmentId: '10', modelValue: [] },
      global: { components: { ElCheckbox, ElCheckboxGroup }, stubs },
    })
    await flushPromises()
    expect(wrapper.find('input[type=file]').exists()).toBe(false)
    await wrapper.get('input[type=checkbox]').setValue(true)
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([[receipt.id]])
    expect(files.uploadAttachment).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('不支持的文件不会发往上传接口', async () => {
    const wrapper = mount(ReceiptAttachmentPicker, {
      props: { shipmentId: '10', modelValue: [] },
      global: { components: { ElCheckbox, ElCheckboxGroup }, stubs },
    })
    await flushPromises()
    const input = wrapper.get('input[type=file]')
    Object.defineProperty(input.element, 'files', { value: [new File(['内容'], '回单.exe')] })
    await input.trigger('change')
    await click(wrapper, '上传并选中')
    expect(wrapper.get('[role=alert]').text()).toContain('单个不超过 20MB')
    expect(files.uploadAttachment).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
