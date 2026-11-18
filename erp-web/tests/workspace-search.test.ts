import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, expect, test, vi } from 'vitest'
import WorkspaceSearch from '../src/components/WorkspaceSearch.vue'
import { records } from '../src/api/admin'
import { browseDocuments } from '../src/api/document-browser'
import { useSessionStore } from '../src/stores/session'

vi.mock('../src/api/admin')
vi.mock('../src/api/document-browser')

afterEach(() => {
  vi.resetAllMocks()
  vi.useRealTimers()
})

test('全局搜索只查询可见业务入口，切换公司清除旧响应', async () => {
  vi.useFakeTimers()
  const pinia = createPinia()
  setActivePinia(pinia)
  const session = useSessionStore()
  session.companyId = '101'
  let respond!: (data: Awaited<ReturnType<typeof browseDocuments>>) => void
  vi.mocked(browseDocuments).mockImplementationOnce(
    () => new Promise((resolve) => { respond = resolve }),
  ).mockResolvedValue({ items: [], total: 0, nextCursor: null, statusCounts: {} })
  const wrapper = mount(WorkspaceSearch, {
    props: {
      modelValue: true,
      menu: [{ path: 'sales-orders', title: '销售订单', module: '销售' }],
    },
    global: {
      plugins: [pinia],
      stubs: {
        ElDialog: { template: '<div><slot /></div>' },
        ElInput: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)">',
        },
        RouterLink: { template: '<a><slot /></a>' },
      },
    },
  })
  try {
    await wrapper.get('input').setValue('SO-101')
    await vi.advanceTimersByTimeAsync(250)
    expect(browseDocuments).toHaveBeenCalledExactlyOnceWith(
      'sales-orders', { documentNo: 'SO-101', limit: 4, withTotal: false }, expect.any(AbortSignal),
    )
    expect(records).not.toHaveBeenCalled()
    session.companyId = '202'
    expect(vi.mocked(browseDocuments).mock.calls[0]?.[2]?.aborted).toBe(true)
    respond({
      items: [{ id: '1', documentNo: 'SO-101', status: 'draft', version: 0, allowedActions: [] }],
      total: 1, nextCursor: null, statusCounts: {},
    })
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    expect(wrapper.text()).not.toContain('SO-101')
    expect(wrapper.text()).toContain('没有匹配的结果')
  } finally {
    wrapper.unmount()
  }
})
