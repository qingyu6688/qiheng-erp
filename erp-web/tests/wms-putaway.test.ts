import { beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, defineComponent, inject, provide, reactive } from 'vue'
import type { ComputedRef } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import PutawayTasksPage from '../src/pages/PutawayTasksPage.vue'
import * as api from '../src/api/wms'
import { ApiError } from '../src/api/http'
import type { WmsTask } from '../src/types/wms'

vi.mock('../src/api/wms')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
const session = reactive<{
  companyId: string
  user: { id: string }
  can: (code: string) => boolean
}>({
  companyId: '1',
  user: { id: '21' },
  can: () => true,
})
const task: WmsTask = {
  id: '9007199254740993',
  no: 'PA-001',
  warehouseId: '10',
  sourceType: 'goods_receipt',
  sourceId: '9007199254740994',
  status: 'assigned',
  assigneeId: '21',
  version: 1,
  lines: [
    {
      id: '9007199254740995',
      skuId: '9007199254740996',
      skuCode: 'SKU-01',
      skuName: '测试商品',
      batchNo: 'L01',
      fromLocationCode: 'IN-01',
      recommendedLocationCode: 'A-01',
      planQty: '1.000001',
      doneQty: '0.000001',
    },
  ],
}

const tableRows = Symbol('测试表格行')
const table = defineComponent({
  props: ['data'],
  setup(props) {
    provide(
      tableRows,
      computed(() => props.data),
    )
  },
  template: '<div><slot /></div>',
})
const tableColumn = defineComponent({
  props: ['prop'],
  setup() {
    return { rows: inject<ComputedRef<Record<string, unknown>[]>>(tableRows) }
  },
  template:
    '<div><div v-for="row in rows" :key="row.id"><slot :row="row">{{ row[prop] }}</slot></div></div>',
})
const stubs = {
  ElTable: table,
  ElTableColumn: tableColumn,
  ElButton: {
    props: ['nativeType', 'disabled', 'loading'],
    template:
      '<button :type="nativeType ?? \'button\'" :disabled="disabled || loading"><slot /></button>',
  },
  ElSelect: {
    props: ['modelValue'],
    emits: ['update:modelValue', 'change'],
    template:
      '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value); $emit(\'change\')"><slot /></select>',
  },
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  ElInput: {
    props: ['modelValue', 'type'],
    emits: ['update:modelValue'],
    methods: { select() {}, focus() {} },
    template:
      "<component :is=\"type === 'textarea' ? 'textarea' : 'input'\" :value=\"modelValue\" @input=\"$emit('update:modelValue', $event.target.value)\" />",
  },
  ElTag: { template: '<span><slot /></span>' },
  ElAlert: { props: ['title'], template: '<p role="alert">{{ title }}</p>' },
}

function page() {
  return mount(PutawayTasksPage, { global: { stubs, directives: { loading: () => undefined } } })
}

function button(wrapper: ReturnType<typeof page>, label: string) {
  const match = wrapper.findAll('button').find((item) => item.text() === label)
  if (!match) throw new Error('缺少按钮：' + label)
  return match
}

beforeEach(() => {
  vi.resetAllMocks()
  session.companyId = '1'
  session.can = () => true
  vi.mocked(api.wmsTasks).mockResolvedValue({ items: [task], nextCursor: null })
  vi.mocked(api.wmsTask).mockResolvedValue(task)
  vi.mocked(api.wmsWarehouses).mockResolvedValue({
    items: [
      { id: '10', code: 'WH-01', name: '收货仓', taskMode: 'optional', locationManaged: true },
    ],
  })
})

describe('上架任务', () => {
  it('保留扫码值和精确数量，网络重试复用同一幂等键后按新版本完成', async () => {
    vi.mocked(api.confirmWmsTaskLine)
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '连接超时'))
      .mockResolvedValueOnce({})
    const wrapper = page()
    await flushPromises()
    await button(wrapper, '查看任务').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('0.000001 / 1.000001')
    await button(wrapper, '扫码确认').trigger('click')
    await wrapper.get('#putaway-from').setValue('L:IN-01')
    await wrapper.get('#putaway-to').setValue('L:B-03')
    await wrapper.get('#putaway-batch').setValue('B:SKU-01|L01')
    await wrapper.get('#putaway-qty').setValue('1.000000')
    await wrapper.get('#putaway-serials').setValue('SN-001\n')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('保留当前输入后可重试')
    const call = vi.mocked(api.confirmWmsTaskLine).mock.calls[0]
    expect(call).toEqual([
      task.id,
      task.lines[0]!.id,
      {
        fromLocationCode: 'L:IN-01',
        toLocationCode: 'L:B-03',
        batchNo: 'B:SKU-01|L01',
        qty: '1.000000',
        serialNos: ['SN-001'],
        version: 1,
      },
      expect.any(String),
    ])
    vi.mocked(api.wmsTask).mockResolvedValue({
      ...task,
      version: 2,
      lines: task.lines.map((line) => ({ ...line, doneQty: line.planQty })),
    })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.confirmWmsTaskLine).toHaveBeenNthCalledWith(2, ...call!)
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.text()).toContain('已确认本次上架')
    await button(wrapper, '完成任务').trigger('click')
    await flushPromises()
    expect(api.finishWmsTask).toHaveBeenCalledWith(task.id, 2, expect.any(String))
    wrapper.unmount()
  })

  it('只读用户可看明细且无法领任务或配置仓库', async () => {
    session.can = (permission) => permission === 'wms_task:view'
    vi.mocked(api.wmsTask).mockResolvedValue({ ...task, status: 'created', assigneeId: null })
    const wrapper = page()
    await flushPromises()
    await button(wrapper, '查看任务').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('测试商品')
    expect(wrapper.text()).not.toContain('领取任务')
    expect(wrapper.text()).not.toContain('仓库任务模式')
    wrapper.unmount()
  })

  it('领取后可确认第一笔上架且不再显示领取', async () => {
    vi.mocked(api.wmsTask).mockResolvedValueOnce({
      ...task,
      status: 'created',
      assigneeId: null,
      version: 0,
    })
    const wrapper = page()
    await flushPromises()
    await button(wrapper, '查看任务').trigger('click')
    await flushPromises()
    await button(wrapper, '领取任务').trigger('click')
    await flushPromises()
    expect(api.claimWmsTask).toHaveBeenCalledWith(task.id, 0, expect.any(String))
    expect(wrapper.text()).toContain('扫码确认')
    expect(wrapper.text()).not.toContain('领取任务')
    wrapper.unmount()
  })

  it('切换公司取消旧详情查询并清空任务', async () => {
    let resolveDetail: (value: WmsTask) => void = () => undefined
    vi.mocked(api.wmsTask).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveDetail = resolve
      }),
    )
    const wrapper = page()
    await flushPromises()
    await button(wrapper, '查看任务').trigger('click')
    const signal = vi.mocked(api.wmsTask).mock.calls[0]![1]
    vi.mocked(api.wmsTasks).mockResolvedValue({ items: [], nextCursor: null })
    session.companyId = '2'
    await flushPromises()
    expect(signal.aborted).toBe(true)
    resolveDetail(task)
    await flushPromises()
    expect(wrapper.find('[aria-label="任务明细"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain(task.no)
    wrapper.unmount()
  })
})
