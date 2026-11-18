import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { document, salesCommand } from '../src/api/sales'
import { createPrintPdf, customPrintOutput } from '../src/api/print-design'
import DocumentBatchBar from '../src/components/DocumentBatchBar.vue'
import type { SalesDocument } from '../src/types/sales'

vi.mock('../src/api/sales', () => ({ document: vi.fn(), salesCommand: vi.fn() }))
vi.mock('../src/api/print-design', () => ({ createPrintPdf: vi.fn(), customPrintOutput: vi.fn() }))

const first: SalesDocument = {
  id: '9007199254740993',
  documentNo: 'SO-1',
  version: 4,
  status: 'pending',
  allowedActions: [],
}
const second: SalesDocument = {
  id: '9007199254740995',
  documentNo: 'SO-2',
  version: 7,
  status: 'pending',
  allowedActions: [],
}
const printTemplate = {
  id: '9007199254740999',
  name: '标准模板',
  documentType: 'sales_order',
  shared: false,
  status: 'active' as const,
  isDefault: true,
  version: 1,
}
function bar() {
  return mount(DocumentBatchBar, {
    props: {
      kind: 'sales-orders',
      selected: [first, second],
      canApprove: true,
      canPrint: true,
      canOpenJobs: false,
    },
    global: {
      stubs: {
        ElButton: { template: '<button><slot /></button>' },
        ElDialog: {
          props: ['modelValue'],
          template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>',
        },
        ElSelect: true,
        ElOption: true,
        RouterLink: true,
      },
    },
  })
}
async function click(wrapper: ReturnType<typeof bar>, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((item) => item.text() === text)
  expect(button).toBeDefined()
  await button!.trigger('click')
  await flushPromises()
}

describe('单据批量操作', () => {
  beforeEach(() => vi.resetAllMocks())

  it('逐张核对所选版本和服务端动作，刷新后继续显示部分成功明细', async () => {
    vi.mocked(document)
      .mockResolvedValueOnce({ ...first, allowedActions: ['approve'] })
      .mockResolvedValueOnce({ ...second, version: 8, allowedActions: ['approve'] })
    const wrapper = bar()
    await click(wrapper, '批量审核')
    await click(wrapper, '确认批量审核')
    expect(salesCommand).toHaveBeenCalledTimes(1)
    expect(salesCommand).toHaveBeenCalledWith(
      'sales-orders',
      first.id,
      'approve',
      { version: 4 },
      expect.any(String),
      expect.any(AbortSignal),
    )
    expect(wrapper.emitted('changed')).toHaveLength(1)
    await wrapper.setProps({ selected: [] })
    expect(wrapper.text()).toContain('已完成 1 张，1 张需要检查。')
    expect(wrapper.text()).toContain('SO-2')
    wrapper.unmount()
  })

  it('将所选 ID 保持为字符串提交到已有的批量 PDF 任务', async () => {
    vi.mocked(customPrintOutput).mockResolvedValue({
      templates: [printTemplate],
      templateId: printTemplate.id,
      version: 1,
      model: null,
    })
    vi.mocked(createPrintPdf).mockResolvedValue({ taskId: '91' })
    const wrapper = bar()
    await click(wrapper, '批量打印')
    await click(wrapper, '生成 PDF')
    expect(createPrintPdf).toHaveBeenCalledWith(
      'sales_order',
      printTemplate.id,
      [first.id, second.id],
      1,
      expect.any(String),
      expect.any(AbortSignal),
    )
    expect(wrapper.text()).toContain('已提交 PDF 任务')
    wrapper.unmount()
  })
})
