import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import FileJobsPage from '../src/pages/FileJobsPage.vue'
import * as jobs from '../src/api/file-jobs'
import * as admin from '../src/api/admin'
import { ApiError } from '../src/api/http'
import { csvCell } from '../src/types/file-jobs'
import type { FileJob } from '../src/types/file-jobs'
import type { PageResult } from '../src/types/api'

vi.mock('../src/api/file-jobs')
vi.mock('../src/api/admin')
vi.mock('../src/api/files')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
vi.mock('vue-router', () => ({ useRoute: () => route }))
const session = reactive({ companyId: '1', can: (permission: string): boolean => permission.length > 0 })
const route = reactive({ query: {} as Record<string, string> })
const select = defineComponent({ props: ['modelValue'], emits: ['update:modelValue'], template: '<select :value="modelValue" @change="$emit(\'update:modelValue\',$event.target.value)"><slot/></select>' })
const stubs = {
  ElButton: { props: ['nativeType', 'loading', 'disabled'], template: '<button :type="nativeType ?? \'button\'" :disabled="disabled"><slot/></button>' },
  ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' },
  ElSelect: select, ElOption: { props: ['value', 'label'], template: '<option :value="value">{{label}}</option>' },
  ElRadioGroup: { template: '<div><slot/></div>' }, ElRadioButton: { template: '<span><slot/></span>' },
  ElTable: { name: 'ElTable', props: ['data'], template: '<div/>' }, ElTableColumn: true,
  RouterLink: { props: ['to'], template: '<a :href="to"><slot/></a>' },
}
type Wrapper = ReturnType<typeof mount>
const mounted: Wrapper[] = []
function page(): Wrapper { const wrapper = mount(FileJobsPage, { global: { stubs } }); mounted.push(wrapper); return wrapper }
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }
async function click(wrapper: Wrapper, title: string): Promise<void> { const button = wrapper.findAll('button').find(item => item.text() === title); if (!button) throw new Error('找不到按钮：' + title); await button.trigger('click'); await flushPromises() }
beforeEach(() => {
  vi.resetAllMocks(); session.companyId = '1'; session.can = () => true; route.query = {}
  vi.mocked(admin.allRecords).mockResolvedValue([])
  vi.mocked(jobs.fileJobs).mockResolvedValue({ items: [], nextCursor: null })
  vi.mocked(jobs.fileJob).mockResolvedValue({ id: '10', jobType: 'partner', status: 'succeeded' })
  vi.mocked(jobs.importErrors).mockResolvedValue({ items: [], nextCursor: null })
})
afterEach(() => { for (const wrapper of mounted.splice(0)) wrapper.unmount(); vi.useRealTimers() })

describe('文件任务页面', () => {
  it('导出深链接只初始化一次，卸载中止列表与详情请求', async () => {
    route.query = { tab: 'export', jobId: '10' }; const wrapper = page(); await flushPromises()
    expect(jobs.fileJobs).toHaveBeenCalledTimes(1); expect(jobs.fileJobs).toHaveBeenCalledWith('export', expect.anything(), expect.any(AbortSignal))
    expect(admin.allRecords).toHaveBeenCalledTimes(1)
    const listSignal = vi.mocked(jobs.fileJobs).mock.calls[0][2], detailSignal = vi.mocked(jobs.fileJob).mock.calls[0][2]
    wrapper.unmount(); expect(listSignal?.aborted).toBe(true); expect(detailSignal?.aborted).toBe(true)
  })
  it('较晚返回的旧列表不能覆盖刷新结果', async () => {
    const old = deferred<PageResult<FileJob>>(), latest = deferred<PageResult<FileJob>>()
    vi.mocked(jobs.fileJobs).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    const wrapper = page(); await flushPromises(); await click(wrapper, '刷新任务')
    latest.resolve({ items: [{ id: '22', status: 'succeeded' }], nextCursor: null }); await flushPromises()
    old.resolve({ items: [{ id: '11', status: 'failed' }], nextCursor: null }); await flushPromises()
    expect(wrapper.findComponent({ name: 'ElTable' }).props('data')).toEqual([{ id: '22', status: 'succeeded' }])
    expect(vi.mocked(jobs.fileJobs).mock.calls[0][2]?.aborted).toBe(true)
  })
  it('切换公司时忽略旧仓库响应，避免重新发起旧初始化', async () => {
    const old = deferred<Awaited<ReturnType<typeof admin.allRecords>>>(), latest = deferred<Awaited<ReturnType<typeof admin.allRecords>>>()
    vi.mocked(admin.allRecords).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    const wrapper = page(); session.companyId = '2'; await flushPromises()
    latest.resolve([{ id: '200', name: '新公司仓库' }]); await flushPromises()
    old.resolve([{ id: '100', name: '旧公司仓库' }]); await flushPromises()
    await wrapper.get('#import-type').setValue('opening_stock')
    expect(wrapper.get('#import-warehouse').text()).toContain('新公司仓库'); expect(wrapper.text()).not.toContain('旧公司仓库')
    expect(jobs.fileJobs).toHaveBeenCalledTimes(1)
  })
  it('旧详情及错误响应不能覆盖新选择的任务', async () => {
    const old = deferred<FileJob>(), latest = deferred<FileJob>()
    vi.mocked(jobs.fileJob).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    route.query = { jobId: '10' }; const wrapper = page(); await flushPromises()
    route.query = { jobId: '20' }; await flushPromises()
    latest.resolve({ id: '20', status: 'succeeded', jobType: 'opening_stock', targets: [{ rowNo: 2, targetType: 'stock_adjustment', targetId: '80' }] }); await flushPromises()
    old.resolve({ id: '10', status: 'partial', jobType: 'partner', failedRows: 1 }); await flushPromises()
    expect(wrapper.get('h2').text()).toContain('库存期初'); expect(wrapper.get('a').attributes('href')).toBe('/stock-adjustments/80')
    expect(jobs.importErrors).not.toHaveBeenCalled(); expect(vi.mocked(jobs.fileJob).mock.calls[0][2]?.aborted).toBe(true)
  })
  it('上传超时重试复用幂等键，并保持表单原始文件', async () => {
    vi.mocked(jobs.createImport).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '请求超时')).mockResolvedValueOnce({ id: '30', status: 'pending' })
    const wrapper = page(); await flushPromises(); await wrapper.get('#import-type').setValue('partner')
    const file = new File(['code,name,shortName,taxNo,roles\nA,单位,,,customer'], 'partners.csv', { type: 'text/csv' })
    Object.defineProperty(wrapper.get('#import-file').element, 'files', { value: [file], configurable: true }); await wrapper.get('#import-file').trigger('change')
    await wrapper.get('form').trigger('submit'); await flushPromises(); expect(wrapper.text()).toContain('请求超时')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    const [first, second] = vi.mocked(jobs.createImport).mock.calls
    expect(first[1]).toBe(second[1]); expect(first[0].get('file')).toBe(file); expect(second[0].get('file')).toBe(file)
    expect(first[0].get('jobType')).toBe('partner'); expect(first[0].has('warehouseId')).toBe(false)
  })
  it('缺少成本权限时隐藏库存期初导入，错误CSV文本防止公式执行', async () => {
    session.can = permission => permission !== 'cost:view'; const wrapper = page(); await flushPromises()
    expect(wrapper.get('#import-type').text()).not.toContain('库存期初')
    expect(csvCell(' =SUM(A1:A2)')).toBe('"\' =SUM(A1:A2)"'); expect(csvCell('甲,"乙"')).toBe('"甲,""乙"""')
    expect(csvCell('001234567890123456789')).toBe('"001234567890123456789"')
  })
  it('任务重试超时保留原幂等键，仅在明确成功后刷新任务', async () => {
    route.query = { jobId: '10' }; vi.mocked(jobs.fileJob).mockResolvedValue({ id: '10', jobType: 'partner', status: 'failed', canRetry: true })
    vi.mocked(jobs.retryImport).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '重试请求超时')).mockResolvedValueOnce({ id: '10', status: 'pending' })
    const wrapper = page(); await flushPromises(); await click(wrapper, '重试未完成行'); await click(wrapper, '重试未完成行')
    const [first, second] = vi.mocked(jobs.retryImport).mock.calls
    expect(first[0]).toBe('10'); expect(first[1]).toBe(second[1]); expect(jobs.createImport).not.toHaveBeenCalled()
  })
})
